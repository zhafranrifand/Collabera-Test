package com.expensemail.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDemoExpenseTest {
    @Test
    fun recognizesSeededDemoRowsForOneTimeCleanup() {
        val demo = Expense(
            merchant = "Kopi Kenangan",
            amount = 32000,
            date = "9 Oct 2026",
            category = "Food & drink",
            approved = true,
            source = "BCA debit alert"
        )

        assertTrue(demo.isLegacyDemoRecord())
    }

    @Test
    fun keepsRealEmailRecordsEvenWhenMerchantMatchesDemo() {
        val imported = Expense(
            merchant = "Kopi Kenangan",
            amount = 32000,
            date = "9 Oct 2026",
            category = "Food & drink",
            approved = false,
            source = "Gmail · myBCA Payment Alert"
        )

        assertFalse(imported.isLegacyDemoRecord())
    }
}
