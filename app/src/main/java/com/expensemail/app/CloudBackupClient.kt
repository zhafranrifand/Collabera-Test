package com.expensemail.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class CloudBackupSnapshot(val id: String, val uploadedAt: String)

/** Thin HTTPS client for the Pocketlog encrypted snapshot API. Never logs tokens or payloads. */
object CloudBackupClient {
    private const val BASE_URL = "https://collabera-test-production.up.railway.app"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun upload(idToken: String, envelope: CloudBackupEnvelope): CloudBackupSnapshot {
        val response = request("POST", "/v1/backups", idToken, envelope.toJson().toString())
        val json = JSONObject(response)
        val id = json.optString("id")
        val uploadedAt = json.optString("uploadedAt")
        require(id.isNotBlank() && uploadedAt.isNotBlank()) { "The backup service returned an incomplete upload receipt." }
        return CloudBackupSnapshot(id, uploadedAt)
    }

    fun list(idToken: String): List<CloudBackupSnapshot> {
        val json = JSONObject(request("GET", "/v1/backups?limit=50", idToken))
        val snapshots = json.optJSONArray("snapshots") ?: error("The backup service returned an invalid snapshot list.")
        return (0 until snapshots.length()).map { index ->
            val item = snapshots.getJSONObject(index)
            val id = item.optString("id")
            val uploadedAt = item.optString("uploadedAt")
            require(id.isNotBlank() && uploadedAt.isNotBlank()) { "The backup service returned invalid snapshot metadata." }
            CloudBackupSnapshot(id, uploadedAt)
        }
    }

    fun restore(idToken: String, snapshotId: String): CloudBackupRestoreResponse {
        require(snapshotId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) {
            "Invalid cloud backup snapshot ID."
        }
        return CloudBackupEnvelopeCodec.parseRestoreResponse(request("GET", "/v1/backups/$snapshotId", idToken))
    }

    fun fetchKey(idToken: String): CloudBackupKeyMetadata =
        CloudBackupEnvelopeCodec.parseKeyMetadata(request("GET", "/v1/backup/key", idToken))

    private fun request(method: String, path: String, idToken: String, body: String? = null): String {
        require(idToken.isNotBlank()) { "Connect an authorized Google account first." }
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $idToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                throw CloudBackupException(status, message ?: "Cloud backup request failed ($status).")
            }
            return response
        } catch (error: CloudBackupException) {
            throw error
        } catch (error: IOException) {
            throw IOException("Could not reach the cloud backup service. Check your internet connection and try again.", error)
        } finally {
            connection.disconnect()
        }
    }
}

class CloudBackupException(val statusCode: Int, message: String) : IOException(message)
