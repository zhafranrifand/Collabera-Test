package com.expensemail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedExpenseNormalizationTest {
    @Test
    fun malformedGmailRowsReturnToReviewWithoutBeingDeleted() {
        val malformed = Expense(
            id = "bad-gmail-row",
            merchant = "BCA AccountSource of Fund: 2302xxxx07",
            amount = 200_000L,
            date = "9 Oct 2026",
            category = "Other",
            source = "Gmail · Internet Transaction Journal",
            approved = true,
            bank = "BCA",
        )

        val normalized = normalizeSavedExpenses(listOf(malformed))

        assertEquals(1, normalized.size)
        assertEquals(malformed.id, normalized.single().id)
        assertEquals(malformed.amount, normalized.single().amount)
        assertEquals(malformed.date, normalized.single().date)
        assertEquals(malformed.category, normalized.single().category)
        assertFalse(normalized.single().approved)
    }

    @Test
    fun preservesCredibleAndManuallyAddedRecords() {
        val credible = Expense(
            id = "credible-gmail-row",
            merchant = "Tokopedia",
            amount = 90_000L,
            date = "8 Oct 2026",
            category = "Shopping",
            source = "Gmail · Receipt",
            approved = true,
            bank = "BCA",
        )
        val manuallyAddedMalformedName = credible.copy(
            id = "manual-row",
            merchant = "Merchant pending correction",
            source = "Gmail · manually added",
        )

        val normalized = normalizeSavedExpenses(listOf(credible, manuallyAddedMalformedName))

        assertEquals(2, normalized.size)
        assertTrue(normalized.all(Expense::approved))
    }
}
