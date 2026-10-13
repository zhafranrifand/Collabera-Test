package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseJsonCodecTest {
    @Test
    fun loadsLegacyExpenseWithoutInventingEmailEvidence() {
        val legacy = """[{"id":"old-1","merchant":"Shop","amount":42000,"date":"8 Oct 2026","category":"Shopping","source":"Gmail · Payment alert","approved":false,"bank":"BCA","recordType":"Expense"}]"""

        val expense = decodeExpenses(legacy, "10 Oct 2026").single()

        assertEquals("old-1", expense.id)
        assertEquals("Gmail · Payment alert", expense.source)
        assertNull(expense.gmailMessageId)
        assertNull(expense.gmailAccountEmail)
        assertNull(expense.emailSender)
        assertNull(expense.emailSubject)
        assertNull(expense.emailReceivedAtMillis)
        assertNull(expense.categoryRuleMerchant)
    }

    @Test
    fun roundTripsGmailEvidenceAndPreservesItAfterEditing() {
        val imported = Expense(
            id = "expense-1",
            merchant = "Shop",
            amount = 42000,
            date = "8 Oct 2026",
            category = "Shopping",
            source = "Gmail",
            gmailMessageId = "message-123",
            gmailAccountEmail = "owner@example.com",
            emailSender = "bca@bca.co.id",
            emailSubject = "BCA payment receipt",
            emailReceivedAtMillis = 1_760_000_000_000L,
            categoryRuleMerchant = "Shop",
        )

        val edited = imported.copy(merchant = "Corrected Shop")
        val decoded = decodeExpenses(encodeExpenses(listOf(edited)), "10 Oct 2026").single()

        assertEquals(edited, decoded)
        assertEquals("message-123", decoded.gmailMessageId)
        assertEquals("owner@example.com", decoded.gmailAccountEmail)
        assertEquals("bca@bca.co.id", decoded.emailSender)
    }

    @Test
    fun manualExpenseOmitsGmailKeys() {
        val manual = Expense(merchant = "Shop", amount = 42000, date = "8 Oct 2026", category = "Shopping")

        val encoded = encodeExpenses(listOf(manual))
        val decoded = decodeExpenses(encoded, "10 Oct 2026").single()

        assertFalse(encoded.contains("gmailMessageId"))
        assertFalse(encoded.contains("emailSender"))
        assertEquals(manual, decoded)
    }
}
