package com.expensemail.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Keeps an explicitly user-started Gmail scan alive while Pocketlog is not visible. */
class GmailSyncForegroundService : Service() {
    private val scanLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var scanRunning = false
    private var scanGeneration = 0
    private var latestStartId = 0
    private var revision = 0
    private var timeoutMessage: String? = null
    @Volatile private var activeCancellation: GmailSyncCancellation? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(scanLock) {
            latestStartId = startId
            when (intent?.action) {
                ACTION_CANCEL -> {
                    syncJob?.cancel(CancellationException("Gmail scan cancelled by the user."))
                    // Interrupt the current blocking request so cancellation does not wait for its
                    // connect/read timeout to expire.
                    activeCancellation?.cancel()
                    if (syncJob?.isActive != true) stopSelf(startId)
                    return START_NOT_STICKY
                }
                ACTION_START -> startScan(intent, startId)
                else -> stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startScan(intent: Intent, startId: Int) {
        synchronized(scanLock) {
            // Job.cancel() marks a coroutine inactive before its blocking engine call has unwound.
            // Keep the previous run active until its finally block completes.
            if (scanRunning) return@synchronized
            val token = intent.getStringExtra(EXTRA_ACCESS_TOKEN).orEmpty()
            // Do not retain the short-lived OAuth token in the service's command Intent.
            intent.removeExtra(EXTRA_ACCESS_TOKEN)
            val startDate = intent.getStringExtra(EXTRA_START_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val endDate = intent.getStringExtra(EXTRA_END_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { GmailSyncRequestMode.valueOf(it) }.getOrNull() }
            val targetMessageId = intent.getStringExtra(EXTRA_TARGET_MESSAGE_ID)
            if (token.isBlank() || startDate == null || endDate == null || mode == null) {
                GmailSyncRuntime.publish(GmailSyncUiState(message = "Could not start Gmail sync. Please try again."))
                stopSelf(startId)
                return@synchronized
            }

            publish(isRunning = true, message = statusFor(mode), targetTotal = if (targetMessageId != null) 1 else 0)
            scanGeneration++
            val generation = scanGeneration
            timeoutMessage = null
            scanRunning = true
            val cancellation = GmailSyncCancellation()
            activeCancellation = cancellation
            startForeground(NOTIFICATION_ID, notification("Starting Gmail scan…", running = true))
            syncJob = serviceScope.launch {
                runScan(token, startDate, endDate, mode, targetMessageId, generation, cancellation)
            }
        }
    }

    private suspend fun runScan(
        accessToken: String,
        startDate: LocalDate,
        endDate: LocalDate,
        mode: GmailSyncRequestMode,
        targetMessageId: String?,
        generation: Int,
        cancellation: GmailSyncCancellation,
    ) {
        var fetched = 0
        var added = 0
        var batches = 0
        var targetTotal = if (targetMessageId != null) 1 else 0
        var processed = 0
        val prefs = getSharedPreferences("expensemail", Context.MODE_PRIVATE)
        try {
            val store = AndroidGmailSyncStore(this)
            val engine = GmailSyncEngine(GmailReaderGateway, store)
            val bulkRecheckIds = if (mode == GmailSyncRequestMode.RECHECK_SKIPPED && targetMessageId == null) {
                val account = withContext(Dispatchers.IO) { normalizedAccountKey(GmailReaderGateway.getAccountEmail(accessToken, cancellation)) }
                val ledger = withContext(Dispatchers.IO) { store.load().ledger }
                val scoped = ledger.skippedForRecheck(account, Int.MAX_VALUE)
                val legacy = ledger.entries.filter {
                    it.accountKey == LEGACY_UNSCOPED_ACCOUNT && it.state == SyncMessageState.LEGACY_UNKNOWN &&
                        ledger.entryFor(account, it.messageId) == null
                }
                (scoped + legacy).distinctBy { it.messageId }.map { it.messageId }
            } else null

            if (bulkRecheckIds != null && bulkRecheckIds.isEmpty()) {
                finish("No skipped emails need rechecking.", fetched, added, batches, targetTotal, processed)
                return
            }
            if (bulkRecheckIds != null) {
                targetTotal = bulkRecheckIds.size
                publish(isRunning = true, message = "Rechecking skipped bank alerts…", fetched, added, batches, targetTotal, processed)
            }

            var bulkOffset = 0
            var nextMode = mode
            while (true) {
                val selectedIds = when {
                    targetMessageId != null -> setOf(targetMessageId)
                    bulkRecheckIds != null -> bulkRecheckIds.drop(bulkOffset).take(GmailSyncEngine.MAX_MESSAGE_GETS).toSet()
                    else -> null
                }
                val report = withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    engine.run(accessToken, startDate, endDate, nextMode, selectedIds, cancellation)
                }
                currentCoroutineContext().ensureActive()
                prefs.edit().putString("gmail_account_key", report.accountKey).putBoolean("gmail_connected", true).apply()
                fetched += report.checked
                added += report.added
                batches++
                if (report.error == null && bulkRecheckIds != null) bulkOffset += selectedIds.orEmpty().size
                if (bulkRecheckIds != null) processed = bulkOffset
                else if (targetMessageId != null && report.error == null) processed = 1
                val moreBulk = bulkRecheckIds != null && bulkOffset < bulkRecheckIds.size
                if (report.error?.contains("access expired", ignoreCase = true) == true) {
                    prefs.edit().remove("gmail_connected").apply()
                }
                val phase = when {
                    report.error != null -> "Scan paused. ${report.error}"
                    nextMode == GmailSyncRequestMode.RECHECK_SKIPPED && moreBulk -> "Rechecking more skipped emails automatically…"
                    nextMode == GmailSyncRequestMode.RECHECK_SKIPPED -> "Recheck complete. $added added to Review."
                    report.hasMore -> "Scanning older emails automatically…"
                    else -> "Scan complete. $fetched emails checked; $added added to Review."
                }
                val message = if (report.error == null && report.hasMore && nextMode != GmailSyncRequestMode.RECHECK_SKIPPED) {
                    "$fetched emails checked · $added added to Review. $phase"
                } else phase
                publish(isRunning = true, message, fetched, added, batches, targetTotal, processed)
                updateNotification(message, running = true)
                // Expense and ledger commits happen inside the engine before each page cursor advances.
                // Reloading is intentionally unnecessary here; the visible app observes revision.
                if (report.error != null || (nextMode == GmailSyncRequestMode.RECHECK_SKIPPED && !moreBulk) ||
                    (nextMode != GmailSyncRequestMode.RECHECK_SKIPPED && !report.hasMore)) {
                    finish(message, fetched, added, batches, targetTotal, processed)
                    return
                }
                if (nextMode != GmailSyncRequestMode.RECHECK_SKIPPED) nextMode = GmailSyncRequestMode.CONTINUE
                delay(SCAN_BATCH_DELAY_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            finish(timeoutMessage ?: "Gmail scan cancelled. Your saved progress is ready to resume.", fetched, added, batches, targetTotal, processed)
            throw cancelled
        } catch (error: Exception) {
            val status = when {
                error is GmailApiException && error.isQuotaLimit -> "Scan paused because Gmail is limiting requests. Your progress is saved."
                error is GmailApiException && error.statusCode == 401 -> {
                    prefs.edit().remove("gmail_connected").apply()
                    "Gmail access expired. Reconnect Gmail and try again."
                }
                error is GmailApiException -> "Gmail could not complete sync. Try again."
                else -> error.message ?: "Gmail sync failed. Try again."
            }
            finish(status, fetched, added, batches, targetTotal, processed)
        } finally {
            withContext(NonCancellable) {
                synchronized(scanLock) {
                    if (generation == scanGeneration) {
                        syncJob = null
                        scanRunning = false
                        activeCancellation = null
                        stopForeground(if (timeoutMessage == null) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                        stopSelf(latestStartId)
                    }
                }
            }
        }
    }

    private fun statusFor(mode: GmailSyncRequestMode): String = when (mode) {
        GmailSyncRequestMode.RECHECK_SKIPPED -> "Rechecking skipped bank alerts…"
        GmailSyncRequestMode.CONTINUE -> "Continuing Gmail scan…"
        GmailSyncRequestMode.NORMAL -> "Checking bank alerts…"
    }

    private fun publish(
        isRunning: Boolean,
        message: String,
        fetched: Int = 0,
        added: Int = 0,
        batches: Int = 0,
        targetTotal: Int = 0,
        processed: Int = 0,
    ) {
        revision++
        GmailSyncRuntime.publish(GmailSyncUiState(isRunning, message, fetched, added, batches, targetTotal, processed, revision))
    }

    private fun finish(message: String, fetched: Int, added: Int, batches: Int, targetTotal: Int, processed: Int) {
        publish(false, message, fetched, added, batches, targetTotal, processed)
        updateNotification(message, running = false)
    }

    private fun notification(message: String, running: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (running) "Pocketlog is syncing Gmail" else "Pocketlog Gmail sync")
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        builder.setProgress(0, 0, running)
        if (running) {
            val cancelIntent = PendingIntent.getService(
                this, 1, Intent(this, GmailSyncForegroundService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "Cancel", cancelIntent).build())
        }
        return builder.build()
    }

    private fun updateNotification(message: String, running: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, running))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gmail sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while Pocketlog checks bank alert emails."
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutMessage = "Gmail scan paused by Android's background time limit. Your progress is saved."
        syncJob?.cancel(CancellationException(timeoutMessage!!))
        activeCancellation?.cancel()
        val state = GmailSyncRuntime.state.value
        finish(timeoutMessage!!, state.fetched, state.added, state.batches, state.targetTotal, state.processed)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        syncJob?.cancel(CancellationException("Gmail sync service stopped."))
        activeCancellation?.cancel()
        serviceScope.cancel()
        if (GmailSyncRuntime.state.value.isRunning) {
            GmailSyncRuntime.publish(GmailSyncRuntime.state.value.copy(
                isRunning = false,
                message = "Gmail scan paused. Your saved progress is ready to resume.",
            ))
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gmail_sync"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.expensemail.app.action.START_GMAIL_SYNC"
        private const val ACTION_CANCEL = "com.expensemail.app.action.CANCEL_GMAIL_SYNC"
        private const val EXTRA_ACCESS_TOKEN = "access_token"
        private const val EXTRA_START_DATE = "start_date"
        private const val EXTRA_END_DATE = "end_date"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TARGET_MESSAGE_ID = "target_message_id"
        private const val SCAN_BATCH_DELAY_MILLIS = 15_000L

        internal fun start(context: Context, token: String, startDate: LocalDate, endDate: LocalDate, mode: GmailSyncRequestMode, targetMessageId: String?) {
            val intent = Intent(context, GmailSyncForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ACCESS_TOKEN, token)
                .putExtra(EXTRA_START_DATE, startDate.toString())
                .putExtra(EXTRA_END_DATE, endDate.toString())
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_TARGET_MESSAGE_ID, targetMessageId)
            context.startForegroundService(intent)
        }
    }
}
