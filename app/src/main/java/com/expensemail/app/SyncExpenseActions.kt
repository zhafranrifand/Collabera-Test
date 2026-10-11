package com.expensemail.app

/** Removes a Review item and retains a Gmail tombstone so the next scan does not import it again. */
internal fun SyncSnapshot.withDismissedExpense(id: String, nowMillis: Long): SyncSnapshot {
    val removed = expenses.firstOrNull { it.id == id } ?: return this
    val remaining = expenses.filterNot { it.id == id }
    val account = removed.gmailAccountEmail
    val messageId = removed.gmailMessageId
    if (account == null || messageId == null) return copy(expenses = remaining)
    val previous = ledger.entryFor(account, messageId)
    val dismissed = (previous ?: SyncLedgerEntry(account, messageId, SyncMessageState.DISMISSED))
        .copy(state = SyncMessageState.DISMISSED, expenseId = null, checkedAtMillis = nowMillis)
    return SyncSnapshot(remaining, ledger.withEntry(dismissed))
}

/** Keeps an explicit decision for a skipped message so future scans leave it alone. */
internal fun SyncSnapshot.withDismissedSkippedEmail(entry: SyncLedgerEntry, nowMillis: Long): SyncSnapshot {
    val current = ledger.entryFor(entry.accountKey, entry.messageId) ?: return this
    if (current.state != SyncMessageState.SKIPPED) return this
    return copy(ledger = ledger.withEntry(current.copy(
        state = SyncMessageState.DISMISSED,
        expenseId = null,
        checkedAtMillis = nowMillis,
        skipReason = null,
    )))
}

/** Moves one inspected email into Review, retaining its Gmail identity to prevent duplicates. */
internal fun SyncSnapshot.withManuallyAddedSkippedEmail(
    entry: SyncLedgerEntry,
    proposed: Expense,
    nowMillis: Long,
): SyncSnapshot {
    val current = ledger.entryFor(entry.accountKey, entry.messageId) ?: return this
    if (current.state != SyncMessageState.SKIPPED || expenses.any {
            it.gmailMessageId == current.messageId &&
                it.gmailAccountEmail?.let(::normalizedAccountKey) == normalizedAccountKey(current.accountKey)
        }) return this
    val added = proposed.copy(
        approved = false,
        gmailMessageId = current.messageId,
        gmailAccountEmail = normalizedAccountKey(current.accountKey),
        emailSender = current.sender,
        emailSubject = current.subject,
        emailReceivedAtMillis = current.receivedAtMillis,
    )
    return SyncSnapshot(
        expenses = listOf(added) + expenses,
        ledger = ledger.withEntry(current.copy(
            state = SyncMessageState.IMPORTED,
            expenseId = added.id,
            checkedAtMillis = nowMillis,
            skipReason = null,
        )),
    )
}
