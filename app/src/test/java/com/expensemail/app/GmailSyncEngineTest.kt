package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GmailSyncEngineTest {
    private val start = LocalDate.of(2026, 10, 1)
    private val end = LocalDate.of(2026, 10, 31)
    private val received = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class FakeStore(initial: SyncSnapshot = SyncSnapshot(emptyList(), SyncLedger())) : GmailSyncStore {
        var snapshot = initial
        override fun load() = snapshot
        override fun commit(snapshot: SyncSnapshot) { this.snapshot = snapshot }
    }

    private class FakeGateway(
        var pages: Map<String?, GmailMessagePage>,
        val emails: MutableMap<String, BankEmail>,
        var account: String = "User@Example.com",
    ) : GmailSyncGateway {
        var getCount = 0
        var listCount = 0
        var failOnceFor: String? = null
        var notFoundFor: String? = null
        var invalidPageTokenOnce: String? = null
        override fun getAccountEmail(accessToken: String) = account
        override fun listBankAlertPage(accessToken: String, startDate: LocalDate, endDate: LocalDate, pageToken: String?): GmailMessagePage {
            listCount++
            if (invalidPageTokenOnce != null && invalidPageTokenOnce == pageToken) {
                invalidPageTokenOnce = null
                throw GmailApiException(400, "invalidArgument", null, "Invalid page token")
            }
            return pages[pageToken] ?: error("No page for $pageToken")
        }
        override fun getBankEmail(accessToken: String, id: String): BankEmail {
            getCount++
            if (notFoundFor == id) throw GmailApiException(404, "notFound", null, "Message not found")
            if (failOnceFor == id) {
                failOnceFor = null
                error("Temporary read failure")
            }
            return emails[id] ?: error("No email for $id")
        }
    }

    private fun email(id: String, receivedAt: Long = received) = BankEmail(id, "bca@bca.co.id", "Receipt $id", "payment", receivedAt)
    private fun expense() = Expense(merchant = "Cafe", amount = 10_000, date = "9 Oct 2026", category = "Food & drink")

    @Test fun partialFailureResumesCurrentPageWithoutDuplicatingCommittedExpense() {
        val gateway = FakeGateway(
            pages = mapOf(null to GmailMessagePage(listOf("a", "b", "c"), null)),
            emails = mutableMapOf("a" to email("a"), "b" to email("b"), "c" to email("c")),
        ).apply { failOnceFor = "b" }
        val store = FakeStore()
        val engine = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() }, nowMillis = { 100L })

        val first = engine.run("token", start, end)
        assertEquals("get=${gateway.getCount}, list=${gateway.listCount}, error=${first.error}", 1, first.checked)
        assertEquals(1, first.added)
        assertEquals(received, first.oldestFetchedAtMillis)
        assertTrue(first.partial)
        assertTrue(first.hasMore)
        assertNotNull(store.snapshot.ledger.cursorFor("user@example.com", start.toString(), end.toString()))
        assertEquals(1, store.snapshot.expenses.size)

        val resumed = engine.run("token", start, end, GmailSyncRequestMode.CONTINUE)
        assertEquals(2, resumed.checked)
        assertEquals(2, resumed.added)
        assertEquals(1, resumed.alreadySeen)
        assertFalse(resumed.hasMore)
        assertEquals(3, store.snapshot.expenses.size)
        assertEquals(3, store.snapshot.expenses.mapNotNull { it.gmailMessageId }.toSet().size)
    }

    @Test fun recheckSkippedCanPromoteToImportedAndRotatesItsTimestamp() {
        val gateway = FakeGateway(mapOf(null to GmailMessagePage(listOf("a"), null)), mutableMapOf("a" to email("a")))
        val store = FakeStore()
        var canParse = false
        val engine = GmailSyncEngine(gateway, store, parser = { _, _ -> if (canParse) expense() else null }, nowMillis = { 100L })

        val initial = engine.run("token", start, end)
        assertEquals(1, initial.skipped)
        val oldStamp = store.snapshot.ledger.entryFor("user@example.com", "a")!!.checkedAtMillis

        canParse = true
        val rechecked = engine.run("token", start, end, GmailSyncRequestMode.RECHECK_SKIPPED)
        assertEquals(1, rechecked.checked)
        assertEquals(1, rechecked.rechecked)
        assertEquals(1, rechecked.added)
        assertEquals(SyncMessageState.IMPORTED, store.snapshot.ledger.entryFor("user@example.com", "a")!!.state)
        assertTrue(store.snapshot.ledger.entryFor("user@example.com", "a")!!.checkedAtMillis > oldStamp)
        assertEquals(1, store.snapshot.expenses.size)
    }

    @Test fun selectedSkippedRecheckTouchesOnlyRequestedMessages() {
        val older = LocalDate.of(2026, 9, 7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ledger = SyncLedger(entries = listOf("a", "b", "c").map { id ->
            SyncLedgerEntry("user@example.com", id, SyncMessageState.SKIPPED, receivedAtMillis = older)
        })
        val gateway = FakeGateway(emptyMap(), mutableMapOf(
            "a" to email("a", older), "b" to email("b", older), "c" to email("c", older),
        ))
        val store = FakeStore(SyncSnapshot(emptyList(), ledger))
        val report = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() })
            .run("token", start, end, GmailSyncRequestMode.RECHECK_SKIPPED, setOf("b", "c"))

        assertEquals(2, report.checked)
        assertEquals(2, report.added)
        assertEquals(2, gateway.getCount)
        assertEquals(SyncMessageState.SKIPPED, store.snapshot.ledger.entryFor("user@example.com", "a")?.state)
        assertEquals(SyncMessageState.IMPORTED, store.snapshot.ledger.entryFor("user@example.com", "b")?.state)
        assertEquals(SyncMessageState.IMPORTED, store.snapshot.ledger.entryFor("user@example.com", "c")?.state)
    }

    @Test fun legacyUnknownRecheckAttachesProvenanceToOldGmailExpense() {
        val old = expense().copy(source = "Gmail · Receipt a", approved = true)
        val ledger = SyncLedger(entries = listOf(
            SyncLedgerEntry(LEGACY_UNSCOPED_ACCOUNT, "a", SyncMessageState.LEGACY_UNKNOWN),
        ))
        val gateway = FakeGateway(emptyMap(), mutableMapOf("a" to email("a")))
        val store = FakeStore(SyncSnapshot(listOf(old), ledger))

        val report = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() })
            .run("token", start, end, GmailSyncRequestMode.RECHECK_SKIPPED)

        assertEquals(1, report.checked)
        assertEquals(1, report.rechecked)
        assertEquals(0, report.added)
        assertEquals(1, store.snapshot.expenses.size)
        assertEquals(old.id, store.snapshot.expenses.single().id)
        assertTrue(store.snapshot.expenses.single().approved)
        assertEquals("a", store.snapshot.expenses.single().gmailMessageId)
        assertEquals("user@example.com", store.snapshot.expenses.single().gmailAccountEmail)
        assertEquals("bca@bca.co.id", store.snapshot.expenses.single().emailSender)
        assertEquals("Receipt a", store.snapshot.expenses.single().emailSubject)
        assertEquals(received, store.snapshot.expenses.single().emailReceivedAtMillis)
        assertEquals(SyncMessageState.IMPORTED, store.snapshot.ledger.entryFor("user@example.com", "a")?.state)
    }

    @Test fun legacyMessageAbsentFromCurrentMailboxIsNotRetriedForThatMailbox() {
        val ledger = SyncLedger(entries = listOf(
            SyncLedgerEntry(LEGACY_UNSCOPED_ACCOUNT, "a", SyncMessageState.LEGACY_UNKNOWN),
        ))
        val gateway = FakeGateway(emptyMap(), mutableMapOf()).apply { notFoundFor = "a" }
        val store = FakeStore(SyncSnapshot(emptyList(), ledger))
        val engine = GmailSyncEngine(gateway, store)

        engine.run("token", start, end, GmailSyncRequestMode.RECHECK_SKIPPED)
        engine.run("token", start, end, GmailSyncRequestMode.RECHECK_SKIPPED)

        assertEquals(1, gateway.getCount)
        assertEquals(SyncMessageState.LEGACY_UNKNOWN, store.snapshot.ledger.entryFor("user@example.com", "a")?.state)
        assertEquals(SyncMessageState.LEGACY_UNKNOWN, store.snapshot.ledger.entryForLegacy("a")?.state)
    }

    @Test fun accountScopedAndLegacyEntriesAreDeduplicated() {
        val ledger = SyncLedger(entries = listOf(
            SyncLedgerEntry("other@example.com", "a", SyncMessageState.IMPORTED),
            SyncLedgerEntry(LEGACY_UNSCOPED_ACCOUNT, "b", SyncMessageState.LEGACY_UNKNOWN),
            SyncLedgerEntry("user@example.com", "c", SyncMessageState.DISMISSED),
        ))
        val gateway = FakeGateway(
            mapOf(null to GmailMessagePage(listOf("a", "b", "c"), null)),
            mutableMapOf("a" to email("a")),
        )
        val store = FakeStore(SyncSnapshot(emptyList(), ledger))
        val report = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() }).run("token", start, end)

        assertEquals(1, report.checked)
        assertEquals(2, report.alreadySeen)
        assertEquals(1, gateway.getCount)
        assertEquals("a", store.snapshot.expenses.single().gmailMessageId)
    }

    @Test fun stopsAtFiftyGetsAndCanContinueNextPage() {
        val firstIds = (0 until 50).map { "a$it" }
        val secondIds = listOf("b")
        val gateway = FakeGateway(
            mapOf(null to GmailMessagePage(firstIds, "page2"), "page2" to GmailMessagePage(secondIds, null)),
            (firstIds + secondIds).associateWith(::email).toMutableMap(),
        )
        val store = FakeStore()
        val engine = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() })

        val first = engine.run("token", start, end)
        assertEquals(50, first.checked)
        assertEquals(50, gateway.getCount)
        assertTrue(first.hasMore)
        assertEquals("page2", store.snapshot.ledger.cursorFor("user@example.com", start.toString(), end.toString())?.pageToken)

        val second = engine.run("token", start, end, GmailSyncRequestMode.CONTINUE)
        assertEquals(1, second.checked)
        assertFalse(second.hasMore)
        assertEquals(51, store.snapshot.expenses.size)
    }

    @Test fun stopsBeforePageThatWouldExceedBudget() {
        val firstIds = (0 until 40).map { "a$it" }
        val secondIds = (0 until 20).map { "b$it" }
        val gateway = FakeGateway(
            mapOf(null to GmailMessagePage(firstIds, "page2"), "page2" to GmailMessagePage(secondIds, null)),
            (firstIds + secondIds).associateWith(::email).toMutableMap(),
        )
        val store = FakeStore()
        val engine = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() })

        val first = engine.run("token", start, end)
        assertEquals(40, first.checked)
        assertEquals("page2", store.snapshot.ledger.cursorFor("user@example.com", start.toString(), end.toString())?.pageToken)
        assertEquals(40, gateway.getCount)

        val continued = engine.run("token", start, end, GmailSyncRequestMode.CONTINUE)
        assertEquals(20, continued.checked)
        assertEquals(60, store.snapshot.expenses.size)
        assertFalse(continued.hasMore)
    }

    @Test fun invalidContinuationTokenRestartsNewestOnceAndDeduplicates() {
        val ledger = SyncLedger(
            entries = listOf(SyncLedgerEntry("user@example.com", "a", SyncMessageState.IMPORTED)),
            cursors = listOf(SyncCursor("user@example.com", start.toString(), end.toString(), "page2", 1L)),
        )
        val gateway = FakeGateway(
            mapOf(null to GmailMessagePage(listOf("a"), "page2"), "page2" to GmailMessagePage(listOf("b"), null)),
            mutableMapOf("b" to email("b")),
        ).apply { invalidPageTokenOnce = "page2" }
        val store = FakeStore(SyncSnapshot(emptyList(), ledger))

        val report = GmailSyncEngine(gateway, store, parser = { _, _ -> expense() })
            .run("token", start, end, GmailSyncRequestMode.CONTINUE)

        assertEquals(1, report.checked)
        assertEquals(1, report.alreadySeen)
        assertEquals(3, gateway.listCount)
        assertFalse(report.hasMore)
        assertEquals("b", store.snapshot.expenses.single().gmailMessageId)
    }

    @Test fun outOfRangeEmailIsSkippedBeforeParsing() {
        val outside = LocalDate.of(2026, 9, 30).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val gateway = FakeGateway(mapOf(null to GmailMessagePage(listOf("a"), null)), mutableMapOf("a" to email("a", outside)))
        val store = FakeStore()
        var parseCalls = 0
        val report = GmailSyncEngine(gateway, store, parser = { _, _ -> parseCalls++; expense() }).run("token", start, end)

        assertEquals(1, report.checked)
        assertEquals(1, report.skipped)
        assertEquals(0, parseCalls)
        assertEquals(0, store.snapshot.expenses.size)
    }
}
