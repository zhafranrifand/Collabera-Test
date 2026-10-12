package com.expensemail.app

import org.json.JSONArray
import org.json.JSONObject

/** Portable, versioned snapshot of local transaction and Gmail sync state. */
data class ExpenseBackup(
    val transactions: List<Expense>,
    val syncLedger: SyncLedger,
)

data class BackupMergePreview(
    val transactionsAdded: Int,
    val duplicateTransactionIds: Int,
    val duplicateGmailMessages: Int,
    val dismissedTransactionsSkipped: Int,
    val ledgerEntriesAdded: Int,
    val dismissalsPreserved: Int,
    val cursorsAdded: Int,
    val lastReportImported: Boolean,
)

data class BackupMergeResult(
    val backup: ExpenseBackup,
    val preview: BackupMergePreview,
)

object ExpenseBackupCodec {
    const val CURRENT_VERSION = 1
    const val MAX_INPUT_BYTES = 25 * 1024 * 1024

    fun encode(transactions: List<Expense>, syncLedger: SyncLedger): String {
        val root = JSONObject().apply {
            put("format", "pocketlog-backup")
            put("version", CURRENT_VERSION)
            put("transactions", JSONArray().apply {
                JSONArray(encodeExpenses(transactions.sortedBy { it.id })).let { encoded ->
                    for (index in 0 until encoded.length()) put(encoded.getJSONObject(index))
                }
            })
            put("syncLedger", JSONObject(SyncLedgerCodec.encode(syncLedger)))
        }
        return canonicalJson(root)
    }

    /** Rejects malformed files, other formats, and future versions rather than importing partial data. */
    fun decode(raw: String): ExpenseBackup {
        require(raw.length <= MAX_INPUT_BYTES) { "Backup file is too large to import." }
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "Backup file is too large to import." }
        val root = JSONObject(raw)
        require(root.optString("format") == "pocketlog-backup") { "This is not a Pocketlog backup." }
        val version = root.optInt("version", -1)
        require(version == CURRENT_VERSION) { "Unsupported backup version: $version" }
        require(root.has("transactions") && !root.isNull("transactions")) { "Backup is missing transactions." }
        require(root.has("syncLedger") && root.optJSONObject("syncLedger") != null) { "Backup is missing Gmail sync history." }
        val transactionArray = root.getJSONArray("transactions")
        for (index in 0 until transactionArray.length()) {
            validateBackupTransaction(transactionArray.getJSONObject(index), index)
        }
        val transactions = decodeExpenses(transactionArray.toString())
        val ledger = SyncLedgerCodec.decode(canonicalJson(root.getJSONObject("syncLedger")))
        return ExpenseBackup(transactions, ledger)
    }

    /** Merge imported state into the current device state. Current transactions win conflicts. */
    fun merge(current: ExpenseBackup, imported: ExpenseBackup): BackupMergeResult {
        val currentIds = current.transactions.mapTo(hashSetOf()) { it.id }
        val currentGmailKeys = current.transactions.mapNotNullTo(hashSetOf()) { it.gmailKeyOrNull() }.apply {
            current.syncLedger.entries.asSequence()
                .filter { it.state == SyncMessageState.IMPORTED }
                .mapTo(this) { it.key }
        }
        val dismissedKeys = (current.syncLedger.entries + imported.syncLedger.entries)
            .asSequence()
            .filter { it.state == SyncMessageState.DISMISSED }
            .map { it.key }
            .toSet()

        var duplicateIds = 0
        var duplicateMessages = 0
        var dismissedSkipped = 0
        val seenIncomingIds = hashSetOf<String>()
        val seenIncomingGmailKeys = hashSetOf<Pair<String, String>>()
        val additions = mutableListOf<Expense>()
        imported.transactions.sortedBy { it.id }.forEach { expense ->
            val gmailKey = expense.gmailKeyOrNull()
            when {
                expense.id in currentIds || !seenIncomingIds.add(expense.id) -> duplicateIds++
                gmailKey != null && (gmailKey in currentGmailKeys || !seenIncomingGmailKeys.add(gmailKey)) -> duplicateMessages++
                gmailKey != null && gmailKey in dismissedKeys -> dismissedSkipped++
                else -> additions += expense
            }
        }

        val currentEntries = current.syncLedger.entries.associateBy { it.key }
        val importedEntries = imported.syncLedger.entries.associateBy { it.key }
        val mergedTransactions = (current.transactions + additions).sortedBy { it.id }
        val transactionByGmailKey = mergedTransactions.mapNotNull { expense ->
            expense.gmailKeyOrNull()?.let { it to expense.id }
        }.toMap()
        val mergedEntries = (currentEntries.keys + importedEntries.keys).sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .mapNotNull { key ->
                val local = currentEntries[key]
                val remote = importedEntries[key]
                when {
                    local?.state == SyncMessageState.DISMISSED -> local
                    local?.state == SyncMessageState.IMPORTED -> local
                    remote?.state == SyncMessageState.DISMISSED -> remote
                    local != null -> local
                    else -> remote
                }
            }
            .map { entry ->
                val transactionId = transactionByGmailKey[entry.key]
                when {
                    transactionId == null || entry.state == SyncMessageState.DISMISSED -> entry
                    entry.state != SyncMessageState.IMPORTED ->
                        entry.copy(state = SyncMessageState.IMPORTED, expenseId = transactionId, skipReason = null)
                    entry.expenseId != transactionId -> entry.copy(expenseId = transactionId)
                    else -> entry
                }
            }
        // Keep the device's page position when both snapshots contain the same scoped query.
        val mergedCursors = (imported.syncLedger.cursors.associateBy { it.key } +
            current.syncLedger.cursors.associateBy { it.key }).values
            .sortedWith(compareBy<SyncCursor> { it.key.first }.thenBy { it.startDate }.thenBy { it.endDate })
        val reportImported = current.syncLedger.lastReport == null && imported.syncLedger.lastReport != null
        val ledger = SyncLedger(
            entries = mergedEntries,
            cursors = mergedCursors,
            lastReport = current.syncLedger.lastReport ?: imported.syncLedger.lastReport,
            legacyMigrated = current.syncLedger.legacyMigrated || imported.syncLedger.legacyMigrated,
        )
        val result = ExpenseBackup(
            transactions = mergedTransactions,
            syncLedger = ledger,
        )
        return BackupMergeResult(
            backup = result,
            preview = BackupMergePreview(
                transactionsAdded = additions.size,
                duplicateTransactionIds = duplicateIds,
                duplicateGmailMessages = duplicateMessages,
                dismissedTransactionsSkipped = dismissedSkipped,
                ledgerEntriesAdded = (importedEntries.keys - currentEntries.keys).size,
                dismissalsPreserved = mergedEntries.count { it.state == SyncMessageState.DISMISSED },
                cursorsAdded = (imported.syncLedger.cursors.map { it.key }.toSet() - current.syncLedger.cursors.map { it.key }.toSet()).size,
                lastReportImported = reportImported,
            ),
        )
    }

    private fun Expense.gmailKeyOrNull(): Pair<String, String>? {
        val account = gmailAccountEmail?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val messageId = gmailMessageId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return normalizedAccountKey(account) to messageId
    }

    private fun validateBackupTransaction(json: JSONObject, index: Int) {
        val requiredStrings = listOf("id", "merchant", "date", "category", "source", "bank", "recordType")
        requiredStrings.forEach { name ->
            require(json.has(name) && !json.isNull(name) && json.opt(name) is String) {
                "Transaction $index is missing a valid '$name'."
            }
        }
        require(json.getString("id").isNotBlank()) { "Transaction $index has a blank ID." }
        val amount = json.opt("amount")
        require(amount is Number && amount.toString().toLongOrNull() != null) {
            "Transaction $index is missing a valid 'amount'."
        }
        require(json.opt("approved") is Boolean) { "Transaction $index is missing a valid 'approved' status." }
        listOf("gmailMessageId", "gmailAccountEmail", "emailSender", "emailSubject").forEach { name ->
            require(!json.has(name) || json.isNull(name) || json.opt(name) is String) {
                "Transaction $index has an invalid '$name'."
            }
        }
        require(!json.has("emailReceivedAtMillis") || json.isNull("emailReceivedAtMillis") ||
            (json.opt("emailReceivedAtMillis") as? Number)?.toString()?.toLongOrNull() != null) {
            "Transaction $index has an invalid 'emailReceivedAtMillis'."
        }
    }
}

/** CSV is intended for spreadsheet analysis; it is not a backup and cannot be restored. */
object ExpenseCsvExporter {
    private val headers = listOf(
        "id", "merchant", "amount_idr", "date", "category", "source", "status", "bank", "record_type",
        "gmail_account", "gmail_message_id", "email_sender", "email_subject", "email_received_at_millis",
    )

    fun export(transactions: List<Expense>): String = buildString {
        append(headers.joinToString(",") { it.csvCell() }).append("\r\n")
        transactions.sortedWith(compareBy<Expense> { it.date }.thenBy { it.id }).forEach { item ->
            val values = listOf(
                item.id, item.merchant, item.amount.toString(), item.date, item.category, item.source,
                if (item.approved) "Confirmed" else "Needs review", item.bank, item.recordType,
                item.gmailAccountEmail.orEmpty(), item.gmailMessageId.orEmpty(), item.emailSender.orEmpty(),
                item.emailSubject.orEmpty(), item.emailReceivedAtMillis?.toString().orEmpty(),
            )
            append(values.joinToString(",") { it.csvCell() }).append("\r\n")
        }
    }

    private fun String.csvCell(): String {
        val firstNonWhitespace = firstOrNull { !it.isWhitespace() }
        val formulaLike = firstOrNull() in listOf('\t', '\r', '\n') || firstNonWhitespace in listOf('=', '+', '-', '@')
        val safeValue = if (formulaLike) "'$this" else this
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }
}

private fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
        "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
