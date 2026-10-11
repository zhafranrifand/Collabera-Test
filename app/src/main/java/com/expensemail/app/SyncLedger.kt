package com.expensemail.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A Gmail message ID is unique within its mailbox, not across connected accounts. */
internal const val LEGACY_UNSCOPED_ACCOUNT = "__legacy_unscoped__"

internal fun normalizedAccountKey(accountKey: String): String =
    accountKey.trim().lowercase(Locale.ROOT).also { require(it.isNotBlank()) { "Account key is required" } }

enum class SyncMessageState { IMPORTED, SKIPPED, DISMISSED, LEGACY_UNKNOWN }

data class SyncLedgerEntry(
    val accountKey: String,
    val messageId: String,
    val state: SyncMessageState,
    val expenseId: String? = null,
    val sender: String? = null,
    val subject: String? = null,
    val receivedAtMillis: Long? = null,
    val checkedAtMillis: Long = 0L,
    val parserVersion: Int? = null,
    val skipReason: String? = null,
) {
    init {
        require(accountKey.isNotBlank()) { "Account key is required" }
        require(messageId.isNotBlank()) { "Message ID is required" }
    }

    val key: Pair<String, String> get() = normalizedAccountKey(accountKey) to messageId
}

enum class SyncMode { NORMAL, RECHECK_SKIPPED }

/** The page token is valid only for this account and exact Gmail date query. */
data class SyncCursor(
    val accountKey: String,
    val startDate: String,
    val endDate: String,
    val pageToken: String?,
    val updatedAtMillis: Long,
) {
    init {
        require(accountKey.isNotBlank()) { "Account key is required" }
        require(startDate.isNotBlank() && endDate.isNotBlank()) { "Date bounds are required" }
        require(pageToken == null || pageToken.isNotBlank()) { "Page token cannot be blank" }
    }

    val key: Triple<String, String, String>
        get() = Triple(normalizedAccountKey(accountKey), startDate, endDate)
}

/** Counts refer to messages successfully fetched and inspected in this run. */
data class SyncReport(
    val accountKey: String,
    val mode: SyncMode,
    val startDate: String,
    val endDate: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val checked: Int,
    val added: Int,
    val skipped: Int,
    val rechecked: Int,
    val alreadySeen: Int = 0,
    val partial: Boolean,
    val error: String? = null,
    val hasMore: Boolean,
    /** Earliest received timestamp among full messages opened in this batch. */
    val oldestFetchedAtMillis: Long? = null,
) {
    init {
        require(accountKey.isNotBlank()) { "Account key is required" }
        require(checked >= 0 && added >= 0 && skipped >= 0 && rechecked >= 0 && alreadySeen >= 0) { "Counts cannot be negative" }
        require(added + skipped <= checked) { "Outcome counts cannot exceed checked" }
        require(rechecked <= checked) { "Rechecked cannot exceed checked" }
    }
}

/** Immutable state; callers persist the result of each update before exposing it to the UI. */
data class SyncLedger(
    val entries: List<SyncLedgerEntry> = emptyList(),
    val cursors: List<SyncCursor> = emptyList(),
    val lastReport: SyncReport? = null,
    val legacyMigrated: Boolean = false,
) {
    fun entryFor(accountKey: String, messageId: String): SyncLedgerEntry? {
        val key = normalizedAccountKey(accountKey) to messageId
        return entries.firstOrNull { it.key == key }
    }

    fun entryForLegacy(messageId: String): SyncLedgerEntry? =
        entryFor(LEGACY_UNSCOPED_ACCOUNT, messageId)

    fun withEntry(entry: SyncLedgerEntry): SyncLedger =
        copy(entries = entries.filterNot { it.key == entry.key } + entry.copy(accountKey = normalizedAccountKey(entry.accountKey)))

    fun skippedForRecheck(accountKey: String, limit: Int): List<SyncLedgerEntry> {
        require(limit >= 0) { "Limit cannot be negative" }
        val account = normalizedAccountKey(accountKey)
        return entries.asSequence()
            .filter { it.key.first == account && it.state == SyncMessageState.SKIPPED }
            .sortedWith(compareBy<SyncLedgerEntry> { it.checkedAtMillis }.thenBy { it.messageId })
            .take(limit)
            .toList()
    }

    fun recheckableCount(accountKey: String): Int {
        val account = normalizedAccountKey(accountKey)
        return entries.count { it.key.first == account && it.state == SyncMessageState.SKIPPED }
    }

    fun cursorFor(accountKey: String, startDate: String, endDate: String): SyncCursor? {
        val key = Triple(normalizedAccountKey(accountKey), startDate, endDate)
        return cursors.firstOrNull { it.key == key }
    }

    fun withCursor(cursor: SyncCursor): SyncLedger =
        copy(cursors = cursors.filterNot { it.key == cursor.key } + cursor.copy(accountKey = normalizedAccountKey(cursor.accountKey)))

    fun withoutCursor(accountKey: String, startDate: String, endDate: String): SyncLedger {
        val key = Triple(normalizedAccountKey(accountKey), startDate, endDate)
        return copy(cursors = cursors.filterNot { it.key == key })
    }
}

/** Versioned JSON stored beside expenses in one SharedPreferences transaction. */
object SyncLedgerCodec {
    const val CURRENT_VERSION = 1

    fun encode(ledger: SyncLedger): String = JSONObject().apply {
        put("version", CURRENT_VERSION)
        put("legacyMigrated", ledger.legacyMigrated)
        put("entries", JSONArray().apply {
            ledger.entries.sortedWith(compareBy<SyncLedgerEntry> { it.key.first }.thenBy { it.messageId }).forEach { entry ->
                put(JSONObject().apply {
                    put("accountKey", normalizedAccountKey(entry.accountKey))
                    put("messageId", entry.messageId)
                    put("state", entry.state.name)
                    putNullable("expenseId", entry.expenseId)
                    putNullable("sender", entry.sender)
                    putNullable("subject", entry.subject)
                    putNullable("receivedAtMillis", entry.receivedAtMillis)
                    put("checkedAtMillis", entry.checkedAtMillis)
                    putNullable("parserVersion", entry.parserVersion)
                    putNullable("skipReason", entry.skipReason)
                })
            }
        })
        put("cursors", JSONArray().apply {
            ledger.cursors.sortedWith(compareBy<SyncCursor> { it.key.first }.thenBy { it.startDate }.thenBy { it.endDate }).forEach { cursor ->
                put(JSONObject().apply {
                    put("accountKey", normalizedAccountKey(cursor.accountKey))
                    put("startDate", cursor.startDate)
                    put("endDate", cursor.endDate)
                    putNullable("pageToken", cursor.pageToken)
                    put("updatedAtMillis", cursor.updatedAtMillis)
                })
            }
        })
        putNullable("lastReport", ledger.lastReport?.toJson())
    }.toString()

    fun decode(raw: String?): SyncLedger {
        if (raw.isNullOrBlank()) return SyncLedger()
        val json = JSONObject(raw)
        val version = json.getInt("version")
        require(version == CURRENT_VERSION) { "Unsupported sync ledger version: $version" }
        val entries = linkedMapOf<Pair<String, String>, SyncLedgerEntry>()
        json.optJSONArray("entries")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val entry = SyncLedgerEntry(
                    accountKey = item.getString("accountKey"),
                    messageId = item.getString("messageId"),
                    state = SyncMessageState.valueOf(item.getString("state")),
                    expenseId = item.optNullableString("expenseId"),
                    sender = item.optNullableString("sender"),
                    subject = item.optNullableString("subject"),
                    receivedAtMillis = item.optNullableLong("receivedAtMillis"),
                    checkedAtMillis = item.optLong("checkedAtMillis", 0L),
                    parserVersion = item.optNullableInt("parserVersion"),
                    skipReason = item.optNullableString("skipReason"),
                ).let { it.copy(accountKey = normalizedAccountKey(it.accountKey)) }
                entries[entry.key] = entry
            }
        }
        val cursors = linkedMapOf<Triple<String, String, String>, SyncCursor>()
        json.optJSONArray("cursors")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val cursor = SyncCursor(
                    accountKey = item.getString("accountKey"),
                    startDate = item.getString("startDate"),
                    endDate = item.getString("endDate"),
                    pageToken = item.optNullableString("pageToken"),
                    updatedAtMillis = item.getLong("updatedAtMillis"),
                ).let { it.copy(accountKey = normalizedAccountKey(it.accountKey)) }
                cursors[cursor.key] = cursor
            }
        }
        val report = json.optJSONObject("lastReport")?.let(::syncReportFromJson)
        return SyncLedger(
            entries = entries.values.toList(),
            cursors = cursors.values.toList(),
            lastReport = report,
            legacyMigrated = json.optBoolean("legacyMigrated", false),
        )
    }

    /** Old scanned IDs are ambiguous: the previous app included imports and parser misses together. */
    fun migrateLegacy(scannedIds: Set<String>, importedIds: Set<String>, existing: SyncLedger = SyncLedger()): SyncLedger {
        if (existing.legacyMigrated) return existing
        val entries = existing.entries.associateByTo(linkedMapOf()) { it.key }
        for (id in (scannedIds + importedIds).filter(String::isNotBlank).sorted()) {
            val key = LEGACY_UNSCOPED_ACCOUNT to id
            if (key in entries) continue
            entries[key] = SyncLedgerEntry(
                accountKey = LEGACY_UNSCOPED_ACCOUNT,
                messageId = id,
                state = if (id in importedIds) SyncMessageState.IMPORTED else SyncMessageState.LEGACY_UNKNOWN,
            )
        }
        return existing.copy(entries = entries.values.toList(), legacyMigrated = true)
    }
}

private fun JSONObject.putNullable(name: String, value: Any?) {
    if (value != null) put(name, value)
}

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) getLong(name) else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) getInt(name) else null

private fun SyncReport.toJson(): JSONObject = JSONObject().apply {
    put("accountKey", normalizedAccountKey(accountKey))
    put("mode", mode.name)
    put("startDate", startDate)
    put("endDate", endDate)
    put("startedAtMillis", startedAtMillis)
    put("finishedAtMillis", finishedAtMillis)
    put("checked", checked)
    put("added", added)
    put("skipped", skipped)
    put("rechecked", rechecked)
    put("alreadySeen", alreadySeen)
    put("partial", partial)
    putNullable("error", error)
    put("hasMore", hasMore)
    putNullable("oldestFetchedAtMillis", oldestFetchedAtMillis)
}

private fun syncReportFromJson(json: JSONObject): SyncReport = SyncReport(
    accountKey = json.getString("accountKey"),
    mode = SyncMode.valueOf(json.getString("mode")),
    startDate = json.getString("startDate"),
    endDate = json.getString("endDate"),
    startedAtMillis = json.getLong("startedAtMillis"),
    finishedAtMillis = json.getLong("finishedAtMillis"),
    checked = json.getInt("checked"),
    added = json.getInt("added"),
    skipped = json.getInt("skipped"),
    rechecked = json.getInt("rechecked"),
    alreadySeen = json.optInt("alreadySeen", 0),
    partial = json.getBoolean("partial"),
    error = json.optNullableString("error"),
    hasMore = json.getBoolean("hasMore"),
    oldestFetchedAtMillis = json.optNullableLong("oldestFetchedAtMillis"),
)
