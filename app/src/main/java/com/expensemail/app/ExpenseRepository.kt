package com.expensemail.app

import android.content.Context

/** Local persistence and backup I/O boundary for expense and Gmail history data. */
internal class ExpenseRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val syncStore = AndroidGmailSyncStore(appContext)
    var loadWarning: String? = null
        private set

    fun loadSnapshot(): SyncSnapshot {
        loadWarning = null
        val raw = preferences.getString("expenses", null)
        val saved = raw?.let {
            runCatching { decodeExpenses(it, todayLabel()) }
                .onFailure { loadWarning = "Saved transaction data could not be read. The original data was preserved on this device." }
                .getOrNull()
        }
        // Do not delete historic Gmail rows whose merchant is clearly an HTML/footer artifact.
        // Move them back to Review so they stop affecting totals and the user can correct them.
        val expenses = normalizeSavedExpenses(saved.orEmpty())
        if (saved != null && expenses != saved) {
            // Persist demo cleanup and the reversible malformed-merchant review migration together.
            check(preferences.edit().putString("expenses", encodeExpenses(expenses)).commit()) {
                "Could not save transaction review changes on this device."
            }
        }
        return SyncSnapshot(expenses, AndroidGmailSyncStore.readLedger(appContext))
    }

    fun commitSnapshot(snapshot: SyncSnapshot) = syncStore.commit(snapshot)

    fun saveExpenses(expenses: List<Expense>) {
        // Match the former UI save behavior: update SharedPreferences immediately in memory,
        // then write on its background disk executor so a large ledger cannot stall Compose.
        preferences.edit().putString("expenses", encodeExpenses(expenses)).apply()
    }

    fun isGmailConnected(): Boolean = preferences.getBoolean("gmail_connected", false)

    fun loadSpendingTarget(): SpendingTarget = SpendingTarget(
        amount = preferences.getLong("spending_target_amount", 0L).coerceAtLeast(0L),
        period = preferences.getString("spending_target_period", "Monthly")?.takeIf { it == "Weekly" || it == "Monthly" } ?: "Monthly",
    )

    fun saveSpendingTarget(target: SpendingTarget) {
        preferences.edit()
            .putLong("spending_target_amount", target.amount.coerceAtLeast(0L))
            .putString("spending_target_period", if (target.period == "Weekly") "Weekly" else "Monthly")
            .apply()
    }

    fun setGmailConnected(connected: Boolean) {
        val edit = preferences.edit()
        if (connected) edit.putBoolean("gmail_connected", true) else edit.remove("gmail_connected")
        check(edit.commit()) { "Could not save Gmail connection state." }
    }

    fun gmailAccountKey(): String? = preferences.getString("gmail_account_key", null)

    fun setGmailDateRange(start: String, end: String) {
        check(preferences.edit().putString("gmail_start_date", start).putString("gmail_end_date", end).commit()) {
            "Could not save Gmail date range."
        }
    }

    fun loadGmailStartDate(): String? = preferences.getString("gmail_start_date", null)
    fun loadGmailEndDate(): String? = preferences.getString("gmail_end_date", null)

    fun encodeBackup(snapshot: SyncSnapshot): String = ExpenseBackupCodec.encode(snapshot.expenses, snapshot.ledger)

    fun decodeBackup(raw: String): ExpenseBackup = ExpenseBackupCodec.decode(raw)

    companion object {
        private const val PREFERENCES_NAME = "expensemail"
        private fun todayLabel() = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("en", "ID")).format(java.util.Date())
    }
}
