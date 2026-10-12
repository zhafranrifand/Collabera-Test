package com.expensemail.app

import android.content.Context
import android.content.SharedPreferences

/** Keeps the expense list and Gmail scan ledger in the same durable preference edit. */
internal class AndroidGmailSyncStore(context: Context) : GmailSyncStore {
    private val prefs = context.getSharedPreferences("expensemail", Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): SyncSnapshot {
        val expenses = prefs.getString("expenses", null)?.let { decodeExpenses(it) }
            ?.let(::normalizeSavedExpenses).orEmpty()
        val ledger = migratedLedger(prefs)
        return SyncSnapshot(expenses, ledger)
    }

    @Synchronized
    override fun commit(snapshot: SyncSnapshot) {
        check(prefs.edit()
            .putString("expenses", encodeExpenses(snapshot.expenses))
            .putString(LEDGER_KEY, SyncLedgerCodec.encode(snapshot.ledger))
            .commit()) { "Could not save Gmail sync progress on this device." }
    }

    companion object {
        private const val LEDGER_KEY = "gmail_sync_ledger"

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
