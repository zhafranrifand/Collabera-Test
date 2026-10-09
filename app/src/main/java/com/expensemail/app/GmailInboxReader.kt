package com.expensemail.app

import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.time.LocalDate
import java.time.ZoneOffset

data class BankEmail(val id: String, val sender: String, val subject: String, val body: String)

/** Fetches recent alerts matching the supported bank sender domains. Email stays on-device. */
object GmailInboxReader {
    const val READ_ONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    fun recentBankAlerts(accessToken: String, alreadyImported: Set<String>, startDate: LocalDate, endDate: LocalDate): List<BankEmail> {
        require(!endDate.isBefore(startDate)) { "End date must be on or after start date." }
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
        val endSeconds = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val query = URLEncoder.encode("after:$startSeconds before:$endSeconds {from:bca.co.id from:bankmandiri.co.id from:jago.com}", "UTF-8")
        val list = getJson("$BASE/messages?q=$query&maxResults=100", accessToken)
        val messages = list.optJSONArray("messages") ?: return emptyList()
        val result = mutableListOf<BankEmail>()
        for (i in 0 until messages.length()) {
            val id = messages.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isBlank() || id in alreadyImported) continue
            val message = getJson("$BASE/messages/$id?format=full", accessToken)
            val internalDate = message.optString("internalDate").toLongOrNull() ?: continue
            val arrived = java.time.Instant.ofEpochMilli(internalDate).atZone(ZoneOffset.UTC).toLocalDate()
            if (arrived.isBefore(startDate) || arrived.isAfter(endDate)) continue
            val payload = message.optJSONObject("payload") ?: continue
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
            val body = extractText(payload)
            if (body.isNotBlank()) result += BankEmail(id, sender, subject, body)
        }
        return result
    }

    private fun getJson(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Gmail request failed ($code).")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(part: JSONObject): String {
        val mime = part.optString("mimeType")
        val body = part.optJSONObject("body")
        val data = body?.optString("data").orEmpty()
        if (data.isNotBlank()) {
            val decoded = runCatching { String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8) }.getOrDefault("")
            if (decoded.isNotBlank()) {
                return if (mime.equals("text/html", ignoreCase = true)) {
                    Html.fromHtml(decoded, Html.FROM_HTML_MODE_LEGACY).toString()
                } else decoded
            }
        }
        val parts = part.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).joinToString("\n") { index ->
            extractText(parts.optJSONObject(index) ?: JSONObject())
        }.trim()
    }
}
