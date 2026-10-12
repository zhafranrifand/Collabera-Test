package com.expensemail.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal interface GmailSyncGateway {
    fun ensureNotCancelled(cancellation: GmailSyncCancellation?) { cancellation?.ensureActive() }
    fun getAccountEmail(accessToken: String): String
    fun getAccountEmail(accessToken: String, cancellation: GmailSyncCancellation?) = getAccountEmail(accessToken)
    fun listBankAlertPage(
        accessToken: String,
        startDate: LocalDate,
        endDate: LocalDate,
        pageToken: String?
    ): GmailMessagePage
    fun listBankAlertPage(
        accessToken: String,
        startDate: LocalDate,
        endDate: LocalDate,
        pageToken: String?,
        cancellation: GmailSyncCancellation?,
    ) = listBankAlertPage(accessToken, startDate, endDate, pageToken)
    fun getBankEmail(accessToken: String, id: String): BankEmail
    fun getBankEmail(accessToken: String, id: String, cancellation: GmailSyncCancellation?) = getBankEmail(accessToken, id)
}

internal object GmailReaderGateway : GmailSyncGateway {
    override fun getAccountEmail(accessToken: String) = GmailInboxReader.getAccountEmail(accessToken)
    override fun getAccountEmail(accessToken: String, cancellation: GmailSyncCancellation?) = GmailInboxReader.getAccountEmail(accessToken, cancellation)
    override fun listBankAlertPage(accessToken: String, startDate: LocalDate, endDate: LocalDate, pageToken: String?) =
        GmailInboxReader.listBankAlertPage(accessToken, startDate, endDate, pageToken)
    override fun listBankAlertPage(accessToken: String, startDate: LocalDate, endDate: LocalDate, pageToken: String?, cancellation: GmailSyncCancellation?) =
        GmailInboxReader.listBankAlertPage(accessToken, startDate, endDate, pageToken, cancellation)
    override fun getBankEmail(accessToken: String, id: String) = GmailInboxReader.getBankEmail(accessToken, id)
    override fun getBankEmail(accessToken: String, id: String, cancellation: GmailSyncCancellation?) = GmailInboxReader.getBankEmail(accessToken, id, cancellation)
}

internal data class SyncSnapshot(val expenses: List<Expense>, val ledger: SyncLedger)

/** Implement commit as one durable write of expenses and ledger, or throw on failure. */
internal interface GmailSyncStore {
    fun load(): SyncSnapshot
    fun commit(snapshot: SyncSnapshot)
}

internal enum class GmailSyncRequestMode { NORMAL, CONTINUE, RECHECK_SKIPPED }

private class GmailSyncStoreException(cause: Exception) : IllegalStateException("Unable to save Gmail sync progress.", cause)

/** Runs a bounded, resumable scan. Every inspected message is committed before a page cursor advances. */
internal class GmailSyncEngine(
    private val gateway: GmailSyncGateway,
    private val store: GmailSyncStore,
    private val parser: (String, String) -> Expense? = BankAlertParser::parse,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_MESSAGE_GETS = 50
        const val MAX_LIST_PAGES = 5
        const val PARSER_VERSION = 5
    }

    fun run(
        accessToken: String,
        startDate: LocalDate,
        endDate: LocalDate,
        mode: GmailSyncRequestMode = GmailSyncRequestMode.NORMAL,
        targetMessageIds: Set<String>? = null,
        cancellation: GmailSyncCancellation? = null,
    ): SyncReport {
        gateway.ensureNotCancelled(cancellation)
        require(!endDate.isBefore(startDate)) { "End date must be on or after start date." }
        require(targetMessageIds == null || mode == GmailSyncRequestMode.RECHECK_SKIPPED) {
            "Messages can only be selected for a skipped-alert recheck."
        }
        val account = normalizedAccountKey(gateway.getAccountEmail(accessToken))
        val start = startDate.toString()
        val end = endDate.toString()
        val started = nowMillis()
        var snapshot = store.load()
        var checked = 0
        var added = 0
        var skipped = 0
        var rechecked = 0
        var alreadySeen = 0
        var oldestFetchedAtMillis: Long? = null
        var checkStamp = maxOf(started, snapshot.ledger.entries.maxOfOrNull { it.checkedAtMillis } ?: 0L)

        fun friendlyError(error: Exception): String = when {
            error is GmailApiException && error.isQuotaLimit ->
                "Gmail is temporarily limiting sync. Wait a minute, then try again."
            error is GmailApiException && error.statusCode == 401 ->
                "Gmail access expired. Reconnect Gmail and try again."
            else -> "Gmail sync stopped. Try again."
        }

        fun nextCheckStamp(): Long {
            checkStamp = maxOf(nowMillis(), checkStamp + 1)
            return checkStamp
        }

        fun commit(next: SyncSnapshot) {
            try { store.commit(next) }
            catch (error: Exception) { throw GmailSyncStoreException(error) }
            snapshot = next
        }

        fun report(error: String?, hasMore: Boolean, resumeToken: String?): SyncReport {
            val result = SyncReport(
                accountKey = account,
                mode = if (mode == GmailSyncRequestMode.RECHECK_SKIPPED) SyncMode.RECHECK_SKIPPED else SyncMode.NORMAL,
                startDate = start,
                endDate = end,
                startedAtMillis = started,
                finishedAtMillis = nowMillis(),
                checked = checked,
                added = added,
                skipped = skipped,
                rechecked = rechecked,
                alreadySeen = alreadySeen,
                partial = error != null || hasMore,
                error = error,
                hasMore = hasMore,
                oldestFetchedAtMillis = oldestFetchedAtMillis,
            )
            val nextLedger = if (mode == GmailSyncRequestMode.RECHECK_SKIPPED) snapshot.ledger else if (hasMore) {
                snapshot.ledger.withCursor(SyncCursor(account, start, end, resumeToken, nowMillis()))
            } else {
                snapshot.ledger.withoutCursor(account, start, end)
            }
            commit(snapshot.copy(ledger = nextLedger.copy(lastReport = result)))
            return result
        }

        fun inspect(email: BankEmail, wasSkipped: Boolean, legacyRecovery: Boolean = false) {
            val checkedAt = nextCheckStamp()
            val dateValid = targetMessageIds != null || email.receivedAtMillis?.let { millis ->
                val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                !date.isBefore(startDate) && !date.isAfter(endDate)
            } == true
            val parsed = if (dateValid) {
                parser(email.body, email.sender) ?:
                    BankAlertParser.parseJagoAlertWithReceivedDate(email.body, email.sender, email.receivedAtMillis)
            } else null
            val existingById = snapshot.expenses.firstOrNull {
                it.gmailMessageId == email.id && it.gmailAccountEmail?.let(::normalizedAccountKey) == account
            }
            // Previous app versions saved Gmail expenses without message IDs. Recover their
            // provenance when revisiting ambiguous legacy scanned IDs rather than adding a copy.
            val legacyMatch = if (legacyRecovery && parsed != null && existingById == null) {
                snapshot.expenses.firstOrNull { old ->
                    old.gmailMessageId == null && old.source.startsWith("Gmail") &&
                        old.merchant == parsed.merchant && old.amount == parsed.amount &&
                        old.date == parsed.date && old.bank == parsed.bank &&
                        old.recordType == parsed.recordType &&
                        (email.subject.isBlank() || old.source == "Gmail · ${email.subject}" ||
                            old.emailSubject == email.subject || old.source == "Gmail")
                }
            } else null
            val existingExpense = existingById ?: legacyMatch
            val provenance = existingExpense?.copy(
                gmailMessageId = email.id,
                gmailAccountEmail = account,
                emailSender = email.sender,
                emailSubject = email.subject,
                emailReceivedAtMillis = email.receivedAtMillis,
            )
            val expense = if (existingExpense == null) parsed?.copy(
                source = "Gmail",
                approved = false,
                gmailMessageId = email.id,
                gmailAccountEmail = account,
                emailSender = email.sender,
                emailSubject = email.subject,
                emailReceivedAtMillis = email.receivedAtMillis,
            ) else null
            val state = if (expense != null || existingExpense != null) SyncMessageState.IMPORTED else SyncMessageState.SKIPPED
            val entry = SyncLedgerEntry(
                accountKey = account,
                messageId = email.id,
                state = state,
                expenseId = expense?.id ?: existingExpense?.id,
                sender = email.sender,
                subject = email.subject,
                receivedAtMillis = email.receivedAtMillis,
                checkedAtMillis = checkedAt,
                parserVersion = PARSER_VERSION,
                skipReason = if (state == SyncMessageState.SKIPPED) {
                    if (!dateValid) "Outside selected date range or missing received date"
                    else BankAlertParser.skipReason(email.body, email.sender)
                } else null,
            )
            commit(SyncSnapshot(
                expenses = when {
                    expense != null -> listOf(expense) + snapshot.expenses
                    provenance != null -> snapshot.expenses.map { if (it.id == provenance.id) provenance else it }
                    else -> snapshot.expenses
                },
                ledger = snapshot.ledger.withEntry(entry),
            ))
            checked++
            email.receivedAtMillis?.let { received ->
                oldestFetchedAtMillis = oldestFetchedAtMillis?.let { minOf(it, received) } ?: received
            }
            if (expense != null) added++
            else if (state == SyncMessageState.SKIPPED) skipped++
            if (wasSkipped) rechecked++
        }

        if (mode == GmailSyncRequestMode.RECHECK_SKIPPED) {
            val eligible = (snapshot.ledger.skippedForRecheck(account, Int.MAX_VALUE) +
                snapshot.ledger.entries.filter { entry ->
                    entry.accountKey == LEGACY_UNSCOPED_ACCOUNT &&
                        entry.state == SyncMessageState.LEGACY_UNKNOWN &&
                        snapshot.ledger.entryFor(account, entry.messageId) == null
                })
                .filter { entry ->
                    targetMessageIds != null || entry.receivedAtMillis == null || Instant.ofEpochMilli(entry.receivedAtMillis)
                        .atZone(ZoneId.systemDefault()).toLocalDate().let { !it.isBefore(startDate) && !it.isAfter(endDate) }
                }
                .filter { targetMessageIds == null || it.messageId in targetMessageIds }
                .sortedWith(compareBy<SyncLedgerEntry> { it.checkedAtMillis }.thenBy { it.messageId })
            val selected = eligible.take(MAX_MESSAGE_GETS)
            for (entry in selected) {
                gateway.ensureNotCancelled(cancellation)
                val email = try { gateway.getBankEmail(accessToken, entry.messageId, cancellation) }
                catch (e: Exception) {
                    gateway.ensureNotCancelled(cancellation)
                    if (entry.accountKey == LEGACY_UNSCOPED_ACCOUNT && e is GmailApiException && e.statusCode == 404) {
                        // Legacy IDs had no mailbox identity. A 404 only rules out this mailbox.
                        commit(snapshot.copy(ledger = snapshot.ledger.withEntry(SyncLedgerEntry(
                            accountKey = account,
                            messageId = entry.messageId,
                            state = SyncMessageState.LEGACY_UNKNOWN,
                            checkedAtMillis = nextCheckStamp(),
                            skipReason = "Message unavailable in this Gmail account",
                        ))))
                        continue
                    }
                    return report(friendlyError(e), true, null)
                }
                try { inspect(email, wasSkipped = true, legacyRecovery = entry.accountKey == LEGACY_UNSCOPED_ACCOUNT) }
                catch (e: Exception) {
                    gateway.ensureNotCancelled(cancellation)
                    if (e is GmailSyncStoreException) throw e
                    return report(friendlyError(e), true, null)
                }
            }
            return report(null, eligible.size > selected.size, null)
        }

        var pageToken = if (mode == GmailSyncRequestMode.CONTINUE) {
            snapshot.ledger.cursorFor(account, start, end)?.pageToken
        } else null
        // A new scan supersedes an older continuation for this exact account and range.
        if (mode == GmailSyncRequestMode.NORMAL) {
            commit(snapshot.copy(ledger = snapshot.ledger.withCursor(SyncCursor(account, start, end, null, nowMillis()))))
        }
        var pages = 0
        var restartedInvalidCursor = false
        while (pages < MAX_LIST_PAGES && checked < MAX_MESSAGE_GETS) {
            gateway.ensureNotCancelled(cancellation)
            val page = try { gateway.listBankAlertPage(accessToken, startDate, endDate, pageToken, cancellation) }
            catch (e: Exception) {
                gateway.ensureNotCancelled(cancellation)
                if (!restartedInvalidCursor && pageToken != null && e is GmailApiException && e.statusCode == 400) {
                    // Gmail page tokens can expire as the mailbox changes. Restart at newest
                    // once; account-scoped ledger entries prevent duplicate imports.
                    pageToken = null
                    restartedInvalidCursor = true
                    commit(snapshot.copy(ledger = snapshot.ledger.withCursor(
                        SyncCursor(account, start, end, null, nowMillis())
                    )))
                    continue
                }
                return report(friendlyError(e), true, pageToken)
            }
            val ids = page.ids.distinct()
            val pending = ids.filter { id ->
                snapshot.ledger.entryFor(account, id) == null && snapshot.ledger.entryForLegacy(id) == null &&
                    snapshot.expenses.none {
                        it.gmailMessageId == id && it.gmailAccountEmail?.let(::normalizedAccountKey) == account
                    }
            }
            // Process whole pages only. This keeps the saved cursor at a page boundary.
            if (pending.size > MAX_MESSAGE_GETS - checked) return report(null, true, pageToken)
            for (id in ids) {
                gateway.ensureNotCancelled(cancellation)
                val known = snapshot.ledger.entryFor(account, id) != null ||
                    snapshot.ledger.entryForLegacy(id) != null || snapshot.expenses.any {
                        it.gmailMessageId == id && it.gmailAccountEmail?.let(::normalizedAccountKey) == account
                    }
                if (known) { alreadySeen++; continue }
                val email = try { gateway.getBankEmail(accessToken, id, cancellation) }
                catch (e: Exception) {
                    gateway.ensureNotCancelled(cancellation)
                    return report(friendlyError(e), true, pageToken)
                }
                try { inspect(email, wasSkipped = false) }
                catch (e: Exception) {
                    gateway.ensureNotCancelled(cancellation)
                    if (e is GmailSyncStoreException) throw e
                    return report(friendlyError(e), true, pageToken)
                }
            }
            pages++
            pageToken = page.nextPageToken
            // The page is complete; this write can safely move the continuation forward.
            val nextLedger = if (pageToken == null) snapshot.ledger.withoutCursor(account, start, end)
                else snapshot.ledger.withCursor(SyncCursor(account, start, end, pageToken, nowMillis()))
            commit(snapshot.copy(ledger = nextLedger))
            if (pageToken == null) break
        }
        return report(null, pageToken != null, pageToken)
    }
}
