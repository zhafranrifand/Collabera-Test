package com.expensemail.app

import org.json.JSONArray
import org.json.JSONObject

/** Only transaction details and email headers are retained; message bodies are never saved. */
internal fun encodeExpenses(expenses: List<Expense>): String = JSONArray().apply {
    expenses.forEach { expense ->
        put(JSONObject().apply {
            put("id", expense.id)
            put("merchant", expense.merchant)
            put("amount", expense.amount)
            put("date", expense.date)
            put("category", expense.category)
            put("source", expense.source)
            put("approved", expense.approved)
            put("bank", expense.bank)
            put("recordType", expense.recordType)
            expense.gmailMessageId?.let { put("gmailMessageId", it) }
            expense.gmailAccountEmail?.let { put("gmailAccountEmail", it) }
            expense.emailSender?.let { put("emailSender", it) }
            expense.emailSubject?.let { put("emailSubject", it) }
            expense.emailReceivedAtMillis?.let { put("emailReceivedAtMillis", it) }
        })
    }
}.toString()

/** Missing email fields in older saved records remain null rather than implying provenance. */
internal fun decodeExpenses(
    raw: String,
    fallbackDate: String = "Unknown date"
): List<Expense> {
    if (raw.isBlank()) return emptyList()
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
        val json = array.getJSONObject(index)
        Expense(
            id = json.optString("id").ifBlank { "legacy-$index-${json.toString().hashCode()}" },
            merchant = json.optString("merchant", "Unknown"),
            amount = json.optLong("amount"),
            date = json.optString("date", fallbackDate),
            category = json.optString("category", "Other"),
            source = json.optString("source", "Email alert"),
            approved = json.optBoolean("approved"),
            bank = json.optString("bank", "Unknown bank"),
            recordType = json.optString("recordType", "Expense"),
            gmailMessageId = json.optionalString("gmailMessageId"),
            gmailAccountEmail = json.optionalString("gmailAccountEmail"),
            emailSender = json.optionalString("emailSender"),
            emailSubject = json.optionalString("emailSubject"),
            emailReceivedAtMillis = json.optLong("emailReceivedAtMillis")
                .takeIf { json.has("emailReceivedAtMillis") && !json.isNull("emailReceivedAtMillis") && it > 0 }
        )
    }
}

private fun JSONObject.optionalString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null
