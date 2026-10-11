package com.expensemail.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val transactionDateFormat = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)

internal fun expenseLocalDate(expense: Expense): LocalDate? =
    runCatching {
        // Android's English date formatting can produce "Sept", while java.time expects "Sep".
        val normalized = expense.date.trim().replace(Regex("(?i)\\bsept\\b"), "Sep")
        LocalDate.parse(normalized, transactionDateFormat)
    }.getOrNull()

/** Transaction date is primary; email receipt time orders transactions on the same date. */
internal fun transactionsNewestFirst(expenses: List<Expense>): List<Expense> = expenses.sortedWith(
    compareByDescending<Expense> { expenseLocalDate(it) ?: LocalDate.MIN }
        .thenByDescending { it.emailReceivedAtMillis ?: Long.MIN_VALUE }
        .thenBy { it.id }
)

internal fun transactionsInRange(expenses: List<Expense>, start: LocalDate?, end: LocalDate?): List<Expense> =
    transactionsNewestFirst(expenses.filter { expense ->
        val date = expenseLocalDate(expense) ?: return@filter start == null && end == null
        (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
    })
