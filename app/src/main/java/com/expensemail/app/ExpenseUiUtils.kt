package com.expensemail.app

import java.text.NumberFormat
import java.util.Locale

internal val EXPENSE_CATEGORIES = listOf(
    "Food & drink",
    "Groceries",
    "Transport",
    "Shopping",
    "Bills & utilities",
    "Health & personal care",
    "Entertainment",
    "Subscriptions",
    "Other",
)

internal data class SpendingTarget(val amount: Long = 0L, val period: String = "Monthly")

internal fun rupiah(value: Long): String = "Rp" + NumberFormat.getNumberInstance(Locale("id", "ID")).format(value)

internal fun recheckableCount(ledger: SyncLedger, accountKey: String): Int =
    ledger.recheckableCount(accountKey) + ledger.entries.count {
        it.accountKey == LEGACY_UNSCOPED_ACCOUNT && it.state == SyncMessageState.LEGACY_UNKNOWN &&
            ledger.entryFor(accountKey, it.messageId) == null
    }

internal fun parseAlert(raw: String, suppliedSender: String = ""): Expense? = BankAlertParser.parse(raw, suppliedSender)
