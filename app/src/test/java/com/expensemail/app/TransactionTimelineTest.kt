package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TransactionTimelineTest {
    @Test
    fun sortsByTransactionDateThenEmailTime() {
        val old = Expense(id = "old", merchant = "Old", amount = 1, date = "9 Jan 2026", category = "Other")
        val morning = Expense(id = "morning", merchant = "Morning", amount = 1, date = "7 Oct 2026", category = "Other", emailReceivedAtMillis = 1L)
        val afternoon = Expense(id = "afternoon", merchant = "Afternoon", amount = 1, date = "7 Oct 2026", category = "Other", emailReceivedAtMillis = 2L)
        assertEquals(listOf("afternoon", "morning", "old"), transactionsNewestFirst(listOf(old, morning, afternoon)).map { it.id })
    }

    @Test
    fun filtersDatesInclusively() {
        val items = listOf(
            Expense(id = "start", merchant = "Start", amount = 1, date = "1 Oct 2026", category = "Other"),
            Expense(id = "end", merchant = "End", amount = 1, date = "7 Oct 2026", category = "Other"),
            Expense(id = "after", merchant = "After", amount = 1, date = "8 Oct 2026", category = "Other"),
        )
        assertEquals(listOf("end", "start"), transactionsInRange(items, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)).map { it.id })
    }

    @Test
    fun acceptsAndroidSeptemberAbbreviation() {
        val item = Expense(id = "september", merchant = "September", amount = 100, date = "18 Sept 2026", category = "Other")
        assertEquals(LocalDate.of(2026, 9, 18), expenseLocalDate(item))
        assertEquals(listOf("september"), transactionsInRange(listOf(item), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).map { it.id })
    }
}
