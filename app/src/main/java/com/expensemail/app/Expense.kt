package com.expensemail.app

import java.util.UUID

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String,
    val amount: Long,
    val date: String,
    val category: String,
    val source: String = "Email alert",
    val approved: Boolean = false,
    val bank: String = "Unknown bank",
    val recordType: String = "Expense",
    val gmailMessageId: String? = null,
    val gmailAccountEmail: String? = null,
    val emailSender: String? = null,
    val emailSubject: String? = null,
    val emailReceivedAtMillis: Long? = null,
    val categoryRuleMerchant: String? = null,
)

internal fun Expense.isLegacyDemoRecord(): Boolean = when {
    merchant == "Kopi Kenangan" && amount == 32000L && category == "Food & drink" && source == "BCA debit alert" -> true
    merchant == "TransJakarta" && amount == 3500L && category == "Transport" && source == "BRI debit alert" -> true
    merchant == "Tokopedia" && amount == 189900L && category == "Shopping" && source == "Mandiri debit alert" -> true
    merchant == "Alfamart" && amount == 54700L && category == "Groceries" && source == "BCA debit alert" -> true
    merchant == "Gojek" && amount == 24500L && category == "Transport" && source == "Gopay notification" -> true
    merchant == "Netflix" && amount == 186000L && category == "Subscriptions" && source == "Card transaction alert" -> true
    else -> false
}

/** True only for Gmail expense rows whose merchant clearly came from email markup/footer text. */
internal fun Expense.hasMalformedGmailMerchant(): Boolean {
    val manuallyAdded = source.contains("manually added", ignoreCase = true)
    val fromGmail = !manuallyAdded &&
        (source.startsWith("Gmail", ignoreCase = true) || gmailMessageId != null || emailSender != null)
    return fromGmail && recordType == "Expense" && !BankAlertParser.isCredibleMerchant(merchant)
}

/** Apply the same safe, reversible cleanup wherever local transactions are loaded. */
internal fun normalizeSavedExpenses(expenses: List<Expense>): List<Expense> = expenses
    .filterNot(Expense::isLegacyDemoRecord)
    .map { expense ->
        if (expense.approved && expense.hasMalformedGmailMerchant()) expense.copy(approved = false)
        else expense
    }
