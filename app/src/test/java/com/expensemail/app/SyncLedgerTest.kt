package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SyncLedgerTest {
    @Test
    fun roundTripRetainsProvenanceCursorAndPartialReport() {
        val entry = SyncLedgerEntry(
            accountKey = " FANDI@example.com ",
            messageId = "gmail-1",
            state = SyncMessageState.IMPORTED,
            expenseId = "expense-1",
            sender = "BCA <bca@bca.co.id>",
            subject = "Payment alert",
            receivedAtMillis = 100L,
            checkedAtMillis = 200L,
            parserVersion = 2,
        )
        val cursor = SyncCursor("FANDI@example.com", "2026-01-01", "2026-01-31", null, 300L)
        val report = SyncReport(
            accountKey = "FANDI@example.com",
            mode = SyncMode.NORMAL,
            startDate = "2026-01-01",
            endDate = "2026-01-31",
            startedAtMillis = 100L,
            finishedAtMillis = 300L,
            checked = 2,
            added = 1,
            skipped = 1,
            rechecked = 0,
            alreadySeen = 3,
            partial = true,
            error = "Gmail is temporarily limiting sync",
            hasMore = true,
            oldestFetchedAtMillis = 150L,
        )
        val original = SyncLedger(legacyMigrated = true)
            .withEntry(entry)
            .withCursor(cursor)
            .copy(lastReport = report)

        val decoded = SyncLedgerCodec.decode(SyncLedgerCodec.encode(original))

        assertEquals("fandi@example.com", decoded.entryFor("FANDI@example.com", "gmail-1")?.accountKey)
        assertEquals(entry.copy(accountKey = "fandi@example.com"), decoded.entryFor("fandi@example.com", "gmail-1"))
        assertEquals(cursor.copy(accountKey = "fandi@example.com"), decoded.cursorFor("fandi@example.com", "2026-01-01", "2026-01-31"))
        assertEquals(report.copy(accountKey = "fandi@example.com"), decoded.lastReport)
        assertEquals(3, decoded.lastReport?.alreadySeen)
        assertEquals(150L, decoded.lastReport?.oldestFetchedAtMillis)
        assertTrue(decoded.legacyMigrated)
    }

    @Test
    fun olderReportWithoutAlreadySeenDecodesAsZero() {
        val report = SyncReport(
            accountKey = "a@example.com",
            mode = SyncMode.NORMAL,
            startDate = "2026-01-01",
            endDate = "2026-01-31",
            startedAtMillis = 1L,
            finishedAtMillis = 2L,
            checked = 1,
            added = 0,
            skipped = 1,
            rechecked = 0,
            partial = false,
            hasMore = false,
        )
        val olderJson = JSONObject(SyncLedgerCodec.encode(SyncLedger(lastReport = report)))
        olderJson.getJSONObject("lastReport").remove("alreadySeen")
        olderJson.getJSONObject("lastReport").remove("oldestFetchedAtMillis")

        assertEquals(0, SyncLedgerCodec.decode(olderJson.toString()).lastReport?.alreadySeen)
        assertNull(SyncLedgerCodec.decode(olderJson.toString()).lastReport?.oldestFetchedAtMillis)
    }

    @Test
    fun legacyMigrationMarksScannedOnlyAsUnknownAndImportedWinsOverlap() {
        val migrated = SyncLedgerCodec.migrateLegacy(
            scannedIds = setOf("parsed-or-skipped", "known-imported", ""),
            importedIds = setOf("known-imported", "older-imported"),
        )

        assertEquals(SyncMessageState.LEGACY_UNKNOWN, migrated.entryForLegacy("parsed-or-skipped")?.state)
        assertEquals(SyncMessageState.IMPORTED, migrated.entryForLegacy("known-imported")?.state)
        assertEquals(SyncMessageState.IMPORTED, migrated.entryForLegacy("older-imported")?.state)
        assertEquals(3, migrated.entries.size)
        assertEquals(migrated, SyncLedgerCodec.migrateLegacy(setOf("new"), emptySet(), migrated))
        assertEquals(0, migrated.recheckableCount(LEGACY_UNSCOPED_ACCOUNT))
    }

    @Test
    fun skippedSelectionIsAccountScopedAndOldestFirst() {
        val ledger = SyncLedger()
            .withEntry(SyncLedgerEntry("a@example.com", "later", SyncMessageState.SKIPPED, checkedAtMillis = 20L))
            .withEntry(SyncLedgerEntry("b@example.com", "other-account", SyncMessageState.SKIPPED, checkedAtMillis = 1L))
            .withEntry(SyncLedgerEntry("a@example.com", "first", SyncMessageState.SKIPPED, checkedAtMillis = 10L))
            .withEntry(SyncLedgerEntry("a@example.com", "done", SyncMessageState.IMPORTED, checkedAtMillis = 0L))

        assertEquals(listOf("first", "later"), ledger.skippedForRecheck("A@example.com", 10).map { it.messageId })
        assertEquals(listOf("first"), ledger.skippedForRecheck("a@example.com", 1).map { it.messageId })
        assertEquals(2, ledger.recheckableCount("a@example.com"))
        assertNull(ledger.entryFor("a@example.com", "other-account"))
        assertEquals(SyncMessageState.SKIPPED, ledger.entryFor("b@example.com", "other-account")?.state)
    }

    @Test
    fun cursorOnlyMatchesExactAccountAndDateRange() {
        val ledger = SyncLedger().withCursor(SyncCursor("a@example.com", "2026-01-01", "2026-01-31", "next", 5L))

        assertEquals("next", ledger.cursorFor("A@example.com", "2026-01-01", "2026-01-31")?.pageToken)
        assertNull(ledger.cursorFor("b@example.com", "2026-01-01", "2026-01-31"))
        assertNull(ledger.cursorFor("a@example.com", "2026-02-01", "2026-02-28"))
        assertTrue(ledger.withoutCursor("a@example.com", "2026-01-01", "2026-01-31").cursors.isEmpty())
        assertFalse(ledger.cursors.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownNewerVersionIsNotSilentlyDiscarded() {
        SyncLedgerCodec.decode("""{"version":999,"entries":[]}""")
    }
}
