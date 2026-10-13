package com.expensemail.app

import android.content.Context
import android.content.SharedPreferences

/** Keeps the expense list and Gmail scan ledger in the same durable preference edit. */
internal class AndroidGmailSyncStore(context: Context) : GmailSyncStore {
    private val prefs = context.getSharedPreferences("expensemail", Context.MODE_PRIVATE)

    override fun load(): SyncSnapshot = synchronized(STORE_LOCK) { loadLocked() }

    private fun loadLocked(): SyncSnapshot {
        val expenses = prefs.getString("expenses", null)?.let { decodeExpenses(it) }
            ?.let(::normalizeSavedExpenses).orEmpty()
        val ledger = migratedLedger(prefs)
        return SyncSnapshot(expenses, ledger, readMerchantRules(prefs))
    }

    override fun commit(snapshot: SyncSnapshot) = synchronized(STORE_LOCK) {
        // A running scan may carry rules from before a UI edit. Durable local entries win,
        // including deletion tombstones, while imported rules can still be added.
        val currentRules = readMerchantRules(prefs)
        val rules = (snapshot.merchantRules.associateBy { it.key } + currentRules.associateBy { it.key })
            .values.sortedBy { it.key }
        writeLocked(snapshot.copy(merchantRules = rules))
    }

    fun update(transform: (SyncSnapshot) -> SyncSnapshot): SyncSnapshot = synchronized(STORE_LOCK) {
        transform(loadLocked()).also(::writeLocked)
    }

    private fun writeLocked(snapshot: SyncSnapshot) {
        check(prefs.edit()
            .putString("expenses", encodeExpenses(snapshot.expenses))
            .putString(LEDGER_KEY, SyncLedgerCodec.encode(snapshot.ledger))
            .putString(MERCHANT_RULES_KEY, MerchantCategoryRuleCodec.encode(snapshot.merchantRules))
            .commit()) { "Could not save Gmail sync progress on this device." }
    }

    companion object {
        private const val LEDGER_KEY = "gmail_sync_ledger"
        private const val MERCHANT_RULES_KEY = "merchant_category_rules"
        private val STORE_LOCK = Any()

        fun readMerchantRules(context: Context): List<MerchantCategoryRule> = synchronized(STORE_LOCK) {
            readMerchantRules(context.getSharedPreferences("expensemail", Context.MODE_PRIVATE))
        }

        private fun readMerchantRules(prefs: SharedPreferences): List<MerchantCategoryRule> {
            val raw = prefs.getString(MERCHANT_RULES_KEY, null) ?: return emptyList()
            return MerchantCategoryRuleCodec.decode(raw)
        }

        fun readLedger(context: Context): SyncLedger =
            migratedLedger(context.getSharedPreferences("expensemail", Context.MODE_PRIVATE))

        private fun migratedLedger(prefs: SharedPreferences): SyncLedger {
            val existing = SyncLedgerCodec.decode(prefs.getString(LEDGER_KEY, null))
            if (existing.legacyMigrated) return existing
            val migrated = SyncLedgerCodec.migrateLegacy(
                prefs.getStringSet("gmail_scanned_ids", emptySet()).orEmpty().toSet(),
                prefs.getStringSet("gmail_imported_ids", emptySet()).orEmpty().toSet(),
                existing,
            )
            check(prefs.edit()
                .putString(LEDGER_KEY, SyncLedgerCodec.encode(migrated))
                .remove("gmail_scanned_ids")
                .remove("gmail_imported_ids")
                .commit()) { "Could not migrate Gmail sync history on this device." }
            return migrated
        }
    }
}
