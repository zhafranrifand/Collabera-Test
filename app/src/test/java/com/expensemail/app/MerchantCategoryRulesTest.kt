package com.expensemail.app

import org.junit.Assert.*
import org.junit.Test

class MerchantCategoryRulesTest {
    private fun expense(merchant: String = "Café 24") = Expense(
        merchant = merchant, amount = 42_000, date = "9 Oct 2026", category = "Other",
    )

    @Test fun unicodeCaseAndWhitespaceMatchExactlyWithoutDroppingPunctuationOrNumbers() {
        val rules = MerchantCategoryRules.remember(emptyList(), "  CAFE\u0301\u00a024  ", "Food & drink")
        val result = MerchantCategoryRules.apply(expense(), rules)
        assertEquals("Food & drink", result.category)
        assertEquals("CAFÉ 24", result.categoryRuleMerchant)
        assertEquals("Other", MerchantCategoryRules.apply(expense("Café 25"), rules).category)
        assertEquals("Other", MerchantCategoryRules.apply(expense("Café-24"), rules).category)
        assertEquals("Other", MerchantCategoryRules.apply(expense("New Café 24"), rules).category)
    }

    @Test fun explicitChoiceOverridesParserSuggestionButPreservesTransactionFacts() {
        val original = expense().copy(category = "Shopping", gmailMessageId = "message-a")
        val rule = MerchantCategoryRule("Café 24", "Food & drink")
        assertEquals(original.copy(category = "Food & drink", categoryRuleMerchant = "Café 24"),
            MerchantCategoryRules.apply(original, listOf(rule)))
    }

    @Test fun approvedTransfersAndIncomeDoNotGetCategoryRules() {
        val rules = listOf(MerchantCategoryRule("Café 24", "Shopping"))
        listOf(expense().copy(approved = true), expense().copy(recordType = "Transfer", category = "Transfer"),
            expense().copy(recordType = "Income", category = "Other")).forEach {
            assertSame(it, MerchantCategoryRules.apply(it, rules))
        }
    }

    @Test fun malformedAndGenericNamesCannotBeRemembered() {
        listOf("1500888", "BCA AccountSource of Fund: 23", "Unknown merchant", "Payment", "Transfer to account",
            "Jago debit card", "Internet Transaction Journal", "Rp190.000", "Please save this email", "font-size:12px").forEach {
            assertFalse(it, MerchantCategoryRules.canRemember(it))
        }
        assertTrue(MerchantCategoryRules.canRemember("New Merchant 01"))
    }

    @Test fun updatingAndRemovingAChoiceKeepsExactlyOneIdentityAndDeletionTombstone() {
        val original = MerchantCategoryRules.remember(emptyList(), "Café 24", "Shopping")
        val updated = MerchantCategoryRules.remember(original, "CAFÉ 24", "Food & drink")
        assertEquals(1, updated.size)
        assertEquals("Food & drink", updated.single().category)
        val removed = MerchantCategoryRules.remove(updated, updated.single().key)
        assertTrue(removed.single().deleted)
        val originalExpense = expense()
        assertSame(originalExpense, MerchantCategoryRules.apply(originalExpense, removed))
    }

    @Test fun removedRuleStopsApplyingAndCanBeExplicitlyRememberedAgain() {
        val original = expense()
        val deleted = listOf(MerchantCategoryRule("Café 24", "Shopping", deleted = true))
        assertSame(original, MerchantCategoryRules.apply(original, deleted))
        val restored = MerchantCategoryRules.remember(deleted, "Café 24", "Food & drink")
        assertFalse(restored.single().deleted)
        assertEquals("Food & drink", MerchantCategoryRules.apply(original, restored).category)
    }

    @Test fun codecPreservesTombstonesAndRejectsInvalidRulesRatherThanCoercingFields() {
        val rules = listOf(MerchantCategoryRule("Café 24", "Other", deleted = true))
        assertEquals(rules, MerchantCategoryRuleCodec.decode(MerchantCategoryRuleCodec.encode(rules)))
        val invalid = listOf(
            "",
            """[{"merchant":"Shop","category":"Transfer"}]""",
            """[{"merchant":42,"category":"Shopping"}]""",
            """[{"merchant":"Shop","category":"Shopping","deleted":"false"}]""",
            """[{"merchant":"1500888","category":"Other"}]""",
            """[{"merchant":"Shop","category":"Shopping"},{"merchant":"SHOP","category":"Other"}]""",
        )
        invalid.forEach { raw -> assertThrows(Exception::class.java) { MerchantCategoryRuleCodec.decode(raw) } }
    }
}
