package com.expensemail.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** A deleted rule is retained so restoring an older backup cannot revive it. */
data class MerchantCategoryRule(
    val merchant: String,
    val category: String,
    val deleted: Boolean = false,
) {
    val key: String get() = MerchantCategoryRules.key(merchant)
}

internal object MerchantCategoryRules {
    // NFC joins equivalent Unicode spellings without merging punctuation or merchant numbers.
    fun key(merchant: String): String = displayName(merchant).lowercase(Locale.ROOT)

    private fun displayName(merchant: String): String = Normalizer.normalize(merchant, Normalizer.Form.NFC)
        .replace(Regex("[\\p{Z}\\s]+"), " ").trim()

    private val genericNames = setOf(
        "unknown", "unknown merchant", "merchant", "other", "transaction", "payment", "purchase",
        "email alert", "bank fee", "administration fee", "admin fee", "bca", "bank bca",
        "bca account", "jago", "bank jago", "uob", "uob digital bank", "bni", "ocbc",
        "transfer", "transfer to", "transfer to account", "rdn pocket withdrawal",
        "jago debit card", "jago insufficient-balance fee",
        "debit card", "credit card", "internet transaction journal", "transaksi qr", "transaction receipt",
    )

    fun canRemember(merchant: String): Boolean {
        val name = displayName(merchant)
        return BankAlertParser.isCredibleMerchant(name) && key(name) !in genericNames &&
            !key(name).startsWith("transfer to ") &&
            !Regex("(?i)^(?:rp|idr)\\s*[\\d.,]+$").matches(name) &&
            merchant.none { Character.isISOControl(it) && !it.isWhitespace() }
    }

    fun canRemember(merchant: String, category: String): Boolean =
        canRemember(merchant) && category in EXPENSE_CATEGORIES

    /** Rules change only category metadata on a new review expense, never transaction facts. */
    fun apply(expense: Expense, rules: List<MerchantCategoryRule>): Expense {
        if (expense.approved || expense.recordType != "Expense" || !canRemember(expense.merchant)) return expense
        val rule = rules.lastOrNull { it.key == key(expense.merchant) } ?: return expense
        if (rule.deleted || !canRemember(rule.merchant) || rule.category !in EXPENSE_CATEGORIES) return expense
        return expense.copy(category = rule.category, categoryRuleMerchant = rule.merchant)
    }

    fun remember(rules: List<MerchantCategoryRule>, merchant: String, category: String): List<MerchantCategoryRule> {
        require(canRemember(merchant)) { "This merchant name cannot be remembered." }
        require(category in EXPENSE_CATEGORIES) { "Choose a valid expense category." }
        val rule = MerchantCategoryRule(displayName(merchant), category)
        return rules.filterNot { it.key == rule.key } + rule
    }

    fun remove(rules: List<MerchantCategoryRule>, key: String): List<MerchantCategoryRule> = rules.map {
        if (it.key == key) it.copy(deleted = true) else it
    }
}

/** Reject a damaged rules document rather than silently changing which merchant it matches. */
internal object MerchantCategoryRuleCodec {
    private const val MAX_RULES = 10_000

    fun encode(rules: List<MerchantCategoryRule>): String {
        validate(rules)
        return JSONArray().apply {
            rules.forEach { rule -> put(JSONObject().apply {
                put("merchant", rule.merchant)
                put("category", rule.category)
                put("deleted", rule.deleted)
            }) }
        }.toString()
    }

    fun decode(raw: String): List<MerchantCategoryRule> {
        require(raw.isNotBlank()) { "Merchant category rules are empty or damaged." }
        val array = JSONArray(raw)
        require(array.length() <= MAX_RULES) { "Too many merchant category rules." }
        val rules = (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            require(json.get("merchant") is String && json.get("category") is String) { "Invalid merchant category rule." }
            require(!json.has("deleted") || json.get("deleted") is Boolean) { "Invalid deleted rule flag." }
            MerchantCategoryRule(json.getString("merchant"), json.getString("category"), json.optBoolean("deleted", false))
        }
        validate(rules)
        return rules
    }

    private fun validate(rules: List<MerchantCategoryRule>) {
        require(rules.size <= MAX_RULES) { "Too many merchant category rules." }
        require(rules.all { MerchantCategoryRules.canRemember(it.merchant) && it.category in EXPENSE_CATEGORIES }) {
            "Invalid merchant category rule."
        }
        require(rules.map { it.key }.distinct().size == rules.size) { "Duplicate merchant category rules." }
    }
}
