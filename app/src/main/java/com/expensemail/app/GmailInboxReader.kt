package com.expensemail.app

import android.text.Html
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64
import java.time.LocalDate
import java.time.ZoneId

data class BankEmail(
    val id: String,
    val sender: String,
    val subject: String,
    val body: String,
    val receivedAtMillis: Long? = null
)

data class GmailMessagePage(val ids: List<String>, val nextPageToken: String?)

/** Preserve field boundaries in HTML receipt tables before Android strips markup. */
internal fun addHtmlTableBoundaries(html: String): String = html
    .replace(Regex("(?i)</(?:td|th)\\s*>")) { "${it.value} " }
    .replace(Regex("(?i)</tr\\s*>")) { "${it.value}<br/>" }

/** Cancellation state and active HTTP requests owned by one sync run. */
class GmailSyncCancellation {
    private val cancelled = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun cancel() {
        cancelled.set(true)
        connections.toList().forEach { runCatching { it.disconnect() } }
    }

    fun ensureActive() {
        if (cancelled.get()) throw CancellationException("Gmail sync was cancelled.")
    }

    fun register(connection: HttpURLConnection) {
        connections.add(connection)
        ensureActive()
    }

    fun unregister(connection: HttpURLConnection) { connections.remove(connection) }
}

internal class GmailApiException(
    val statusCode: Int,
    val reason: String,
    val retryAfterSeconds: Long?,
    message: String
) : IllegalStateException(message) {
    val isQuotaLimit: Boolean
        get() = statusCode == 429 || reason in setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded") ||
            message.orEmpty().contains("quota exceeded", ignoreCase = true) ||
            message.orEmpty().contains("units per minute per user", ignoreCase = true)
}

/** Fetches recent alerts matching the supported bank sender domains. Email stays on-device. */
object GmailInboxReader {
    const val READ_ONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    /** Lists one complete page. Callers can persist [GmailMessagePage.nextPageToken] after committing it. */
    fun listBankAlertPage(
        accessToken: String,
        startDate: LocalDate,
        endDate: LocalDate,
        pageToken: String? = null,
        cancellation: GmailSyncCancellation? = null,
    ): GmailMessagePage {
        require(!endDate.isBefore(startDate)) { "End date must be on or after start date." }
        val localZone = ZoneId.systemDefault()
        val startSeconds = startDate.atStartOfDay(localZone).toEpochSecond() - 1
        val endSeconds = endDate.plusDays(1).atStartOfDay(localZone).toEpochSecond()
        val search = "after:$startSeconds before:$endSeconds {from:bca@bca.co.id from:noreply.livin@bankmandiri.co.id from:noreply@jago.com from:noreply_UOB@uob.co.id}"
        val query = URLEncoder.encode(search, "UTF-8")
        val page = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val response = getJson("$BASE/messages?q=$query&maxResults=50$page", accessToken, cancellation)
        val messages = response.optJSONArray("messages")
        val ids = (0 until (messages?.length() ?: 0))
            .mapNotNull { messages?.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        val nextPageToken = response.optString("nextPageToken").takeIf { it.isNotBlank() && it != "null" }
        return GmailMessagePage(ids, nextPageToken)
    }

    /** Fetches one message; an empty body is returned for missing or unsupported MIME content. */
    fun getBankEmail(accessToken: String, id: String, cancellation: GmailSyncCancellation? = null): BankEmail {
        require(id.isNotBlank()) { "Message ID must not be blank." }
        val message = getJson("$BASE/messages/${URLEncoder.encode(id, "UTF-8")}?format=full", accessToken, cancellation)
        val receivedAtMillis = message.optString("internalDate").toLongOrNull()
        val payload = message.optJSONObject("payload")
            ?: return BankEmail(id, "", "Bank transaction alert", "", receivedAtMillis)
        val headers = payload.optJSONArray("headers")
        var sender = ""
        var subject = "Bank transaction alert"
        for (j in 0 until (headers?.length() ?: 0)) {
            val header = headers?.optJSONObject(j) ?: continue
            when (header.optString("name").lowercase()) {
                "from" -> sender = header.optString("value")
                "subject" -> subject = header.optString("value", subject)
            }
        }
        return BankEmail(id, sender, subject, extractText(payload), receivedAtMillis)
    }

    /** Returns the Gmail mailbox address associated with the current OAuth token. */
    fun getAccountEmail(accessToken: String, cancellation: GmailSyncCancellation? = null): String =
        getJson("$BASE/profile", accessToken, cancellation).optString("emailAddress")
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Gmail account email was unavailable.")

    private fun getJson(url: String, token: String, cancellation: GmailSyncCancellation? = null): JSONObject {
        var attempt = 0
        while (true) {
            cancellation?.ensureActive()
            try {
                return getJsonOnce(url, token, cancellation)
            } catch (e: GmailApiException) {
                cancellation?.ensureActive()
                if (!e.isQuotaLimit || attempt >= 3) throw e
                val waitSeconds = e.retryAfterSeconds?.coerceIn(1, 30) ?: (1L shl attempt)
                try {
                    var remainingMillis = waitSeconds * 1000
                    while (remainingMillis > 0) {
                        cancellation?.ensureActive()
                        val pauseMillis = minOf(remainingMillis, 100L)
                        Thread.sleep(pauseMillis)
                        remainingMillis -= pauseMillis
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancellation?.ensureActive()
                    throw e
                }
                cancellation?.ensureActive()
                attempt++
            }
        }
    }

    private fun getJsonOnce(url: String, token: String, cancellation: GmailSyncCancellation?): JSONObject {
        cancellation?.ensureActive()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            cancellation?.register(connection)
            cancellation?.ensureActive()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val error = runCatching { JSONObject(response).optJSONObject("error") }.getOrNull()
                val detail = error?.optString("message")?.takeIf { it.isNotBlank() }
                val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                throw GmailApiException(code, reason, retryAfter, detail ?: "Gmail request failed ($code).")
            }
            return JSONObject(response)
        } finally {
            cancellation?.unregister(connection)
            connection.disconnect()
        }
    }

    private fun extractText(part: JSONObject): String =
        extractPlainText(part).ifBlank { extractHtmlText(part) }

    private fun extractPlainText(part: JSONObject): String {
        if (isAttachment(part)) return ""
        if (part.optString("mimeType").equals("text/plain", ignoreCase = true)) return decode(part)
        val parts = part.optJSONArray("parts") ?: return ""
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it) }
            .firstNotNullOfOrNull { extractPlainText(it).takeIf(String::isNotBlank) }
            .orEmpty()
    }

    private fun extractHtmlText(part: JSONObject): String {
        if (isAttachment(part)) return ""
        val mime = part.optString("mimeType")
        if (mime.equals("text/html", ignoreCase = true)) {
            val decoded = decode(part)
            if (decoded.isBlank()) return ""
            val withoutNonContent = addHtmlTableBoundaries(decoded
                .replace(Regex("(?is)<(style|script)\\b[^>]*>.*?</\\1\\s*>"), " ")
                .replace(Regex("(?is)<!--.*?-->"), " "))
            return Html.fromHtml(withoutNonContent, Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace("\uFFFC", "")
                .replace(Regex("[\\t\\u00A0 ]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }
        val parts = part.optJSONArray("parts") ?: return ""
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it) }
            .firstNotNullOfOrNull { extractHtmlText(it).takeIf(String::isNotBlank) }
            .orEmpty()
    }

    private fun decode(part: JSONObject): String {
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        return if (data.isBlank()) "" else
            runCatching { String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8) }.getOrDefault("")
    }

    private fun isAttachment(part: JSONObject): Boolean =
        part.optString("filename").isNotBlank() ||
            part.optJSONObject("body")?.optString("attachmentId").orEmpty().isNotBlank()
}
