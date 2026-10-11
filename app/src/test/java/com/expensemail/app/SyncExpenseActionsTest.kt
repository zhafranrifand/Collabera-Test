package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncExpenseActionsTest {
    @Test fun deletingGmailReviewItemKeepsDismissedMessageId() {
        val expense = Expense(
            id = "expense-1", merchant = "Cafe", amount = 10_000, date = "9 Oct 2026", category = "Other",
            gmailMessageId = "message-1", gmailAccountEmail = "User@Example.com",
        )
        val initial = SyncSnapshot(
            listOf(expense),
            SyncLedger(entries = listOf(SyncLedgerEntry("user@example.com", "message-1", SyncMessageState.IMPORTED, expenseId = expense.id))),
        )

        val updated = initial.withDismissedExpense(expense.id, 123L)

        assertEquals(emptyList<Expense>(), updated.expenses)
        val entry = updated.ledger.entryFor("user@example.com", "message-1")!!
        assertEquals(SyncMessageState.DISMISSED, entry.state)
        assertNull(entry.expenseId)
        assertEquals(123L, entry.checkedAtMillis)
        assertEquals(1, updated.ledger.entries.size)
    }
}
