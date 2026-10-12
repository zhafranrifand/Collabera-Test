package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ExpenseBackupTest {
    @Test
    fun backupRoundTripsTransactionsAndCompleteSyncLedgerDeterministically() {
        val expense = expense("tx-1", "BCA, online", 12_500, subject = "Paid \"today\"")
        val ledger = SyncLedger(
            entries = listOf(
                SyncLedgerEntry("USER@example.com", "gmail-1", SyncMessageState.IMPORTED, expenseId = "tx-1", sender = "bca@example.com", subject = "receipt", receivedAtMillis = 10L, checkedAtMillis = 20L, parserVersion = 3),
                SyncLedgerEntry("user@example.com", "gmail-2", SyncMessageState.DISMISSED, checkedAtMillis = 30L, skipReason = "not a transaction"),
            ),
            cursors = listOf(SyncCursor("USER@example.com", "2026-01-01", "2026-12-31", "page-2", 40L)),
            lastReport = SyncReport(
                accountKey = "USER@example.com", mode = SyncMode.NORMAL, startDate = "2026-01-01", endDate = "2026-12-31",
                startedAtMillis = 1L, finishedAtMillis = 2L, checked = 1, added = 1, skipped = 0, rechecked = 0,
                partial = false, hasMore = true, oldestFetchedAtMillis = 1L,
            ),
            legacyMigrated = true,
        )

        val encoded = ExpenseBackupCodec.encode(listOf(expense), ledger)
        val decoded = ExpenseBackupCodec.decode(encoded)

        assertEquals(ExpenseBackup(listOf(expense), SyncLedgerCodec.decode(SyncLedgerCodec.encode(ledger))), decoded)
        assertEquals(encoded, ExpenseBackupCodec.encode(listOf(expense), ledger))
        assertTrue(encoded.contains("gmail-2"))
        assertFalse(encoded.contains("emailBody"))
        assertFalse(encoded.contains("oauthToken"))
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedJsonIsRejected() {
        ExpenseBackupCodec.decode("not json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedBackupVersionIsRejected() {
        ExpenseBackupCodec.decode("""{"format":"pocketlog-backup","version":99,"transactions":[],"syncLedger":{}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun transactionMissingRequiredFieldsIsRejectedInsteadOfDefaulted() {
        val root = JSONObject(ExpenseBackupCodec.encode(listOf(expense("id", "Merchant", 42L)), SyncLedger()))
        root.getJSONArray("transactions").getJSONObject(0).remove("amount")

        ExpenseBackupCodec.decode(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedBackupIsRejectedBeforeJsonParsing() {
        ExpenseBackupCodec.decode("x".repeat(ExpenseBackupCodec.MAX_INPUT_BYTES + 1))
    }

    @Test
    fun mergeKeepsExistingTransactionAndDeduplicatesAccountScopedGmailIds() {
        val existing = expense("same-id", "Local merchant", 100L)
        val localLedger = SyncLedger().withEntry(SyncLedgerEntry("a@example.com", "already-imported", SyncMessageState.IMPORTED))
        val imported = listOf(
            existing.copy(merchant = "Backup merchant"),
            expense("new-id", "Duplicate Gmail", 200L, account = "A@example.com", message = "already-imported"),
            expense("new-id-2", "Other account", 300L, account = "b@example.com", message = "already-imported"),
            expense("new-id-3", "New item", 400L, account = "a@example.com", message = "new-message"),
        )

        val merged = ExpenseBackupCodec.merge(ExpenseBackup(listOf(existing), localLedger), ExpenseBackup(imported, SyncLedger()))

        assertEquals(listOf(existing, imported.last(), imported[2]).sortedBy { it.id }, merged.backup.transactions)
        assertEquals("Local merchant", merged.backup.transactions.single { it.id == "same-id" }.merchant)
        assertEquals(1, merged.preview.duplicateTransactionIds)
        assertEquals(1, merged.preview.duplicateGmailMessages)
        assertEquals(2, merged.preview.transactionsAdded)
    }

    @Test
    fun mergePreservesDismissalAndDoesNotRestoreDismissedMessageAsTransaction() {
        val current = ExpenseBackup(
            transactions = emptyList(),
            syncLedger = SyncLedger().withEntry(SyncLedgerEntry("user@example.com", "msg-1", SyncMessageState.DISMISSED, skipReason = "dismissed locally")),
        )
        val imported = ExpenseBackup(
            transactions = listOf(expense("would-resurrect", "Merchant", 100L, message = "msg-1")),
            syncLedger = SyncLedger().withEntry(SyncLedgerEntry("USER@example.com", "msg-1", SyncMessageState.SKIPPED, skipReason = "parser miss")),
        )

        val merged = ExpenseBackupCodec.merge(current, imported)

        assertTrue(merged.backup.transactions.isEmpty())
        assertEquals(SyncMessageState.DISMISSED, merged.backup.syncLedger.entryFor("user@example.com", "msg-1")?.state)
        assertEquals("dismissed locally", merged.backup.syncLedger.entryFor("user@example.com", "msg-1")?.skipReason)
        assertEquals(1, merged.preview.dismissedTransactionsSkipped)
        assertEquals(1, merged.preview.dismissalsPreserved)
    }

    @Test
    fun importedDismissalOverridesNonDismissedLocalLedgerState() {
        val current = ExpenseBackup(emptyList(), SyncLedger().withEntry(SyncLedgerEntry("a@example.com", "msg", SyncMessageState.SKIPPED)))
        val imported = ExpenseBackup(emptyList(), SyncLedger().withEntry(SyncLedgerEntry("A@example.com", "msg", SyncMessageState.DISMISSED)))

        val result = ExpenseBackupCodec.merge(current, imported)

        assertEquals(SyncMessageState.DISMISSED, result.backup.syncLedger.entryFor("a@example.com", "msg")?.state)
    }

    @Test
    fun currentDeviceCursorWinsWhenBothBackupsHaveSameQuery() {
        val currentCursor = SyncCursor("user@example.com", "2026-01-01", "2026-12-31", "local-page", 20L)
        val importedCursor = SyncCursor("USER@example.com", "2026-01-01", "2026-12-31", "backup-page", 10L)
        val differentQuery = SyncCursor("user@example.com", "2025-01-01", "2025-12-31", "other-page", 5L)

        val result = ExpenseBackupCodec.merge(
            ExpenseBackup(emptyList(), SyncLedger(cursors = listOf(currentCursor))),
            ExpenseBackup(emptyList(), SyncLedger(cursors = listOf(importedCursor, differentQuery))),
        )

        assertEquals("local-page", result.backup.syncLedger.cursorFor("user@example.com", "2026-01-01", "2026-12-31")?.pageToken)
        assertEquals("other-page", result.backup.syncLedger.cursorFor("user@example.com", "2025-01-01", "2025-12-31")?.pageToken)
        assertEquals(1, result.preview.cursorsAdded)
    }

    @Test
    fun restoredTransactionPromotesCurrentSkippedLedgerEntryToImported() {
        val transaction = expense("restored", "Merchant", 42L, account = "user@example.com", message = "gmail-message")
        val currentLedger = SyncLedger().withEntry(
            SyncLedgerEntry("USER@example.com", "gmail-message", SyncMessageState.SKIPPED, skipReason = "parser miss"),
        )

        val result = ExpenseBackupCodec.merge(
            ExpenseBackup(emptyList(), currentLedger),
            ExpenseBackup(listOf(transaction), SyncLedger()),
        )

        val entry = result.backup.syncLedger.entryFor("user@example.com", "gmail-message")
        assertEquals(SyncMessageState.IMPORTED, entry?.state)
        assertEquals("restored", entry?.expenseId)
        assertEquals(null, entry?.skipReason)
        assertEquals(1, result.preview.transactionsAdded)
    }

    @Test
    fun importedDismissalCannotOverrideCurrentImportedRecord() {
        val transaction = expense("current", "Local merchant", 42L, account = "user@example.com", message = "gmail-message")
        val currentLedger = SyncLedger().withEntry(
            SyncLedgerEntry("user@example.com", "gmail-message", SyncMessageState.IMPORTED, expenseId = "current"),
        )
        val importedLedger = SyncLedger().withEntry(
            SyncLedgerEntry("USER@example.com", "gmail-message", SyncMessageState.DISMISSED, skipReason = "stale dismissal"),
        )

        val result = ExpenseBackupCodec.merge(
            ExpenseBackup(listOf(transaction), currentLedger),
            ExpenseBackup(emptyList(), importedLedger),
        )

        assertEquals(SyncMessageState.IMPORTED, result.backup.syncLedger.entryFor("user@example.com", "gmail-message")?.state)
        assertEquals("current", result.backup.syncLedger.entryFor("user@example.com", "gmail-message")?.expenseId)
        assertEquals(0, result.preview.dismissedTransactionsSkipped)
    }

    @Test
    fun importedLedgerExpenseIdTracksSurvivingTransactionWhenGmailKeyMatches() {
        val currentTransaction = expense("local-transaction", "Local merchant", 42L, account = "user@example.com", message = "shared-message")
        val importedTransaction = expense("backup-transaction", "Backup merchant", 43L, account = "USER@example.com", message = "shared-message")
        val currentLedger = SyncLedger().withEntry(
            SyncLedgerEntry("user@example.com", "shared-message", SyncMessageState.IMPORTED, expenseId = "stale-id"),
        )
        val importedLedger = SyncLedger().withEntry(
            SyncLedgerEntry("USER@example.com", "shared-message", SyncMessageState.IMPORTED, expenseId = "backup-transaction"),
        )

        val result = ExpenseBackupCodec.merge(
            ExpenseBackup(listOf(currentTransaction), currentLedger),
            ExpenseBackup(listOf(importedTransaction), importedLedger),
        )

        assertEquals(listOf(currentTransaction), result.backup.transactions)
        assertEquals("local-transaction", result.backup.syncLedger.entryFor("user@example.com", "shared-message")?.expenseId)
    }

    @Test
    fun csvEscapesCommasQuotesAndLineBreaksAndIncludesStatus() {
        val csv = ExpenseCsvExporter.export(listOf(expense("id", "Shop, \"One\"\nBranch", 42L, approved = true, subject = "Paid, yes")))

        assertTrue(csv.startsWith("\"id\",\"merchant\",\"amount_idr\""))
        assertTrue(csv.contains("\"Shop, \"\"One\"\"\nBranch\""))
        assertTrue(csv.contains("\"Confirmed\""))
        assertTrue(csv.contains("\"Paid, yes\""))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun csvPrefixesFormulaLikeCellsToPreventSpreadsheetExecution() {
        val csv = ExpenseCsvExporter.export(
            listOf(expense("id", " =HYPERLINK(\"https://bad.example\")", 42L, subject = "@SUM(1,2)")),
        )

        assertTrue(csv.contains("\"' =HYPERLINK(\"\"https://bad.example\"\")\""))
        assertTrue(csv.contains("\"'@SUM(1,2)\""))
    }

    private fun expense(
        id: String,
        merchant: String,
        amount: Long,
        account: String = "user@example.com",
        message: String = "gmail-$id",
        approved: Boolean = false,
        subject: String? = null,
    ) = Expense(
        id = id,
        merchant = merchant,
        amount = amount,
        date = "12 Oct 2026",
        category = "Other",
        approved = approved,
        gmailAccountEmail = account,
        gmailMessageId = message,
        emailSubject = subject,
    )
}
