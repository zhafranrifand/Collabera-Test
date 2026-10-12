package com.expensemail.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val Ink = Color(0xFF0C1728)
private val Surface = Color(0xFF142238)
private val Surface2 = Color(0xFF1B2C45)
private val Mint = Color(0xFF69E6B1)
private val Muted = Color(0xFF9DAFC4)
private val Canvas = Color(0xFF0C1728)
private val White = Color(0xFFF7FAFC)
private val Orange = Color(0xFFFFB86B)
private const val SCAN_BATCH_DELAY_MILLIS = 15_000L
private const val LIST_PAGE_SIZE = 25

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent { ExpenseMailApp(this, incoming) }
    }
}

data class Expense(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String,
    val amount: Long,
    val date: String,
    val category: String,
    val source: String = "Email alert",
    val approved: Boolean = false,
    val bank: String = "Unknown bank",
    val recordType: String = "Expense",
    val gmailMessageId: String? = null,
    val gmailAccountEmail: String? = null,
    val emailSender: String? = null,
    val emailSubject: String? = null,
    val emailReceivedAtMillis: Long? = null
)

private fun todayLabel() = SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(Date())
private fun isCurrentMonth(date: String): Boolean = date.endsWith(SimpleDateFormat("MMM yyyy", Locale("en", "ID")).format(Date()))
private fun rupiah(value: Long): String = "Rp" + NumberFormat.getNumberInstance(Locale("id", "ID")).format(value)
private fun recheckableCount(ledger: SyncLedger, accountKey: String): Int =
    ledger.recheckableCount(accountKey) + ledger.entries.count {
        it.accountKey == LEGACY_UNSCOPED_ACCOUNT && it.state == SyncMessageState.LEGACY_UNKNOWN &&
            ledger.entryFor(accountKey, it.messageId) == null
    }

private fun parseAlert(raw: String, suppliedSender: String = ""): Expense? = BankAlertParser.parse(raw, suppliedSender)

private fun saveExpenses(context: Context, expenses: List<Expense>) {
    context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).edit()
        .putString("expenses", encodeExpenses(expenses)).apply()
}

internal fun Expense.isLegacyDemoRecord(): Boolean = when {
    merchant == "Kopi Kenangan" && amount == 32000L && category == "Food & drink" && source == "BCA debit alert" -> true
    merchant == "TransJakarta" && amount == 3500L && category == "Transport" && source == "BRI debit alert" -> true
    merchant == "Tokopedia" && amount == 189900L && category == "Shopping" && source == "Mandiri debit alert" -> true
    merchant == "Alfamart" && amount == 54700L && category == "Groceries" && source == "BCA debit alert" -> true
    merchant == "Gojek" && amount == 24500L && category == "Transport" && source == "Gopay notification" -> true
    merchant == "Netflix" && amount == 186000L && category == "Subscriptions" && source == "Card transaction alert" -> true
    else -> false
}

private fun loadExpenses(context: Context): List<Expense> {
    val prefs = context.getSharedPreferences("expensemail", Context.MODE_PRIVATE)
    val raw = prefs.getString("expenses", null) ?: return emptyList()
    return try {
        val saved = decodeExpenses(raw, todayLabel())
        val cleaned = saved.filterNot(Expense::isLegacyDemoRecord)
        if (cleaned.size != saved.size) saveExpenses(context, cleaned)
        cleaned
    } catch (_: Exception) { emptyList() }
}

@Composable
fun ExpenseMailApp(context: Context, incomingEmail: String?) {
    var expenses by remember { mutableStateOf(loadExpenses(context)) }
    var tab by remember { mutableStateOf(if (!incomingEmail.isNullOrBlank()) "Review" else "Home") }
    var importText by remember { mutableStateOf(incomingEmail.orEmpty()) }
    var senderText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var manualImportExpanded by remember { mutableStateOf(!incomingEmail.isNullOrBlank()) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var gmailConnected by remember { mutableStateOf(context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).getBoolean("gmail_connected", false)) }
    var gmailStatus by remember { mutableStateOf("") }
    var gmailSyncing by remember { mutableStateOf(false) }
    var scanFetched by remember { mutableIntStateOf(0) }
    var scanAdded by remember { mutableIntStateOf(0) }
    var scanBatches by remember { mutableIntStateOf(0) }
    var scanTargetTotal by remember { mutableIntStateOf(0) }
    var scanProcessed by remember { mutableIntStateOf(0) }
    var requestedTargetMessageId by remember { mutableStateOf<String?>(null) }
    var selectedSkipped by remember { mutableStateOf<SyncLedgerEntry?>(null) }
    var skippedAddMode by remember { mutableStateOf(false) }
    var dismissSkippedEntry by remember { mutableStateOf<SyncLedgerEntry?>(null) }
    var skippedActionError by remember { mutableStateOf("") }
    var skippedPreview by remember { mutableStateOf<BankEmail?>(null) }
    var skippedPreviewLoading by remember { mutableStateOf(false) }
    var skippedPreviewError by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    var pendingBackup by remember { mutableStateOf<ExpenseBackup?>(null) }
    var backupPreview by remember { mutableStateOf<BackupMergePreview?>(null) }
    var activityDateMode by remember { mutableStateOf("All") }
    var activityStartDate by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var activityEndDate by remember { mutableStateOf(LocalDate.now()) }
    val prefs = remember { context.getSharedPreferences("expensemail", Context.MODE_PRIVATE) }
    val syncStore = remember { AndroidGmailSyncStore(context) }
    var syncLedger by remember { mutableStateOf(AndroidGmailSyncStore.readLedger(context)) }
    var currentAccountKey by remember { mutableStateOf(prefs.getString("gmail_account_key", null)) }
    val defaultEndDate = remember { LocalDate.now() }
    var gmailStartDate by remember {
        mutableStateOf(runCatching { prefs.getString("gmail_start_date", null)?.let(LocalDate::parse) ?: defaultEndDate.minusDays(90) }.getOrDefault(defaultEndDate.minusDays(90)))
    }
    var gmailEndDate by remember {
        mutableStateOf(runCatching { prefs.getString("gmail_end_date", null)?.let(LocalDate::parse) ?: defaultEndDate }.getOrDefault(defaultEndDate))
    }
    val activity = context as? ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(ExpenseBackupCodec.encode(expenses, syncStore.load().ledger))
                    } ?: error("Could not open the selected file.")
                }
            }
            backupMessage = result.fold({ "Backup saved. Keep this file somewhere safe." }, { "Could not save backup: ${it.message ?: "unknown error"}" })
        }
    }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ExpenseCsvExporter.export(expenses)) }
                        ?: error("Could not open the selected file.")
                }
            }
            backupMessage = result.fold({ "CSV exported. CSV files cannot be restored as app data." }, { "Could not export CSV: ${it.message ?: "unknown error"}" })
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= ExpenseBackupCodec.MAX_INPUT_BYTES) { "Backup file is too large to import." }
                            output.write(buffer, 0, count)
                        }
                        String(output.toByteArray(), Charsets.UTF_8)
                    } ?: error("Could not read the selected file.")
                    ExpenseBackupCodec.decode(raw)
                }
            }
            result.onSuccess { imported ->
                pendingBackup = imported
                backupPreview = ExpenseBackupCodec.merge(ExpenseBackup(expenses, syncStore.load().ledger), imported).preview
                backupMessage = ""
            }.onFailure { backupMessage = "Could not read backup: ${it.message ?: "invalid file"}" }
        }
    }
    val gmailScope = remember { listOf(Scope(GmailInboxReader.READ_ONLY_SCOPE)) }
    var requestedSyncMode by remember { mutableStateOf(GmailSyncRequestMode.NORMAL) }
    val scanProgressText = if (gmailSyncing) {
        if (scanBatches == 0) "Connecting and checking the first batch…"
        else if (scanTargetTotal > 0) "$scanProcessed of $scanTargetTotal skipped emails rechecked · $scanAdded added to Review"
        else "$scanFetched emails checked across $scanBatches ${if (scanBatches == 1) "batch" else "batches"} · $scanAdded added to Review"
    } else null
    val scanProgressFraction = if (gmailSyncing && scanTargetTotal > 0) scanProcessed.toFloat() / scanTargetTotal else null
    fun persist(updated: List<Expense>) {
        if (gmailSyncing) { gmailStatus = "Wait for Gmail sync to finish before changing transactions."; return }
        expenses = updated
        saveExpenses(context, updated)
    }
    fun deleteExpense(id: String) {
        if (gmailSyncing) { gmailStatus = "Wait for Gmail sync to finish before changing transactions."; return }
        val updated = SyncSnapshot(expenses, syncLedger).withDismissedExpense(id, System.currentTimeMillis())
        runCatching { syncStore.commit(updated) }
            .onSuccess { expenses = updated.expenses; syncLedger = updated.ledger }
            .onFailure { gmailStatus = it.message ?: "Could not delete this transaction. Try again." }
    }

    fun syncWithToken(accessToken: String, mode: GmailSyncRequestMode, targetMessageId: String? = null) {
        gmailConnected = true
        prefs.edit().putBoolean("gmail_connected", true).apply()
        val selectedStart = gmailStartDate
        val selectedEnd = gmailEndDate
        scanFetched = 0
        scanAdded = 0
        scanBatches = 0
        scanTargetTotal = if (targetMessageId != null) 1 else 0
        scanProcessed = 0
        coroutineScope.launch {
            gmailSyncing = true
            gmailStatus = when (mode) {
                GmailSyncRequestMode.RECHECK_SKIPPED -> "Rechecking skipped bank alerts…"
                GmailSyncRequestMode.CONTINUE -> "Continuing Gmail scan…"
                else -> "Checking bank alerts…"
            }
            try {
                val engine = GmailSyncEngine(GmailReaderGateway, syncStore)
                val bulkRecheckIds = if (mode == GmailSyncRequestMode.RECHECK_SKIPPED && targetMessageId == null) {
                    withContext(Dispatchers.IO) {
                        val account = normalizedAccountKey(GmailReaderGateway.getAccountEmail(accessToken))
                        val ledger = syncStore.load().ledger
                        val scoped = ledger.skippedForRecheck(account, Int.MAX_VALUE)
                        val legacy = ledger.entries.filter {
                            it.accountKey == LEGACY_UNSCOPED_ACCOUNT && it.state == SyncMessageState.LEGACY_UNKNOWN &&
                                ledger.entryFor(account, it.messageId) == null
                        }
                        (scoped + legacy).distinctBy { it.messageId }.map { it.messageId }
                    }
                } else null
                if (bulkRecheckIds != null && bulkRecheckIds.isEmpty()) {
                    gmailStatus = "No skipped emails need rechecking."
                    return@launch
                }
                if (bulkRecheckIds != null) scanTargetTotal = bulkRecheckIds.size
                var bulkOffset = 0
                var nextMode = mode
                while (true) {
                    val selectedIds = when {
                        targetMessageId != null -> setOf(targetMessageId)
                        bulkRecheckIds != null -> bulkRecheckIds.drop(bulkOffset).take(GmailSyncEngine.MAX_MESSAGE_GETS).toSet()
                        else -> null
                    }
                    val report = withContext(Dispatchers.IO) {
                        engine.run(accessToken, selectedStart, selectedEnd, nextMode, selectedIds)
                    }
                    val snapshot = withContext(Dispatchers.IO) { syncStore.load() }
                    expenses = snapshot.expenses
                    syncLedger = snapshot.ledger
                    currentAccountKey = report.accountKey
                    prefs.edit().putString("gmail_account_key", report.accountKey).apply()
                    scanFetched += report.checked
                    scanAdded += report.added
                    scanBatches++
                    if (report.error == null && bulkRecheckIds != null) bulkOffset += selectedIds.orEmpty().size
                    if (bulkRecheckIds != null) scanProcessed = bulkOffset
                    else if (targetMessageId != null && report.error == null) scanProcessed = 1
                    val moreBulkRechecks = bulkRecheckIds != null && bulkOffset < bulkRecheckIds.size
                    if (report.error?.contains("access expired", ignoreCase = true) == true) {
                        gmailConnected = false
                        prefs.edit().remove("gmail_connected").apply()
                    }
                    gmailStatus = when {
                        report.error != null -> "Scan paused. ${report.error}"
                        nextMode == GmailSyncRequestMode.RECHECK_SKIPPED && moreBulkRechecks -> "Rechecking more skipped emails automatically…"
                        nextMode == GmailSyncRequestMode.RECHECK_SKIPPED -> "Recheck complete. $scanAdded added to Review."
                        report.hasMore -> "Scanning older emails automatically…"
                        else -> "Scan complete. $scanFetched emails checked; $scanAdded added to Review."
                    }
                    if (report.error != null || (nextMode == GmailSyncRequestMode.RECHECK_SKIPPED && !moreBulkRechecks) ||
                        (nextMode != GmailSyncRequestMode.RECHECK_SKIPPED && !report.hasMore)) break
                    if (nextMode == GmailSyncRequestMode.RECHECK_SKIPPED) {
                        delay(SCAN_BATCH_DELAY_MILLIS)
                        continue
                    }
                    nextMode = GmailSyncRequestMode.CONTINUE
                    delay(SCAN_BATCH_DELAY_MILLIS)
                }
            } catch (e: Exception) {
                val saved = runCatching { withContext(Dispatchers.IO) { syncStore.load() } }.getOrNull()
                if (saved != null) { expenses = saved.expenses; syncLedger = saved.ledger }
                gmailStatus = when {
                    e is GmailApiException && e.isQuotaLimit -> "Scan paused because Gmail is limiting requests. Your progress is saved."
                    e is GmailApiException && e.statusCode == 401 -> {
                        gmailConnected = false
                        prefs.edit().remove("gmail_connected").apply()
                        "Gmail access expired. Reconnect Gmail and try again."
                    }
                    e is GmailApiException -> "Gmail could not complete sync. Try again."
                    else -> e.message ?: "Gmail sync failed. Try again."
                }
            } finally {
                gmailSyncing = false
            }
        }
    }

    val gmailAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!) }
                .onSuccess { authorization ->
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { gmailSyncing = false; gmailStatus = "Google did not provide Gmail access. Please try again." }
                    else syncWithToken(token, requestedSyncMode, requestedTargetMessageId)
                }
                .onFailure { gmailSyncing = false; gmailStatus = it.message ?: "Gmail authorization failed." }
        } else {
            gmailSyncing = false
            gmailStatus = "Gmail access was not granted."
        }
    }

    fun requestGmailSync(mode: GmailSyncRequestMode = GmailSyncRequestMode.NORMAL, targetMessageId: String? = null) {
        if (gmailSyncing) return
        if (activity == null) { gmailStatus = "Gmail authorization is unavailable in this screen."; return }
        requestedSyncMode = mode
        requestedTargetMessageId = targetMessageId
        scanFetched = 0
        scanAdded = 0
        scanBatches = 0
        scanTargetTotal = if (targetMessageId != null) 1 else 0
        scanProcessed = 0
        gmailSyncing = true
        gmailStatus = "Requesting Gmail access…"
        val request = AuthorizationRequest.builder().setRequestedScopes(gmailScope).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent == null) { gmailSyncing = false; gmailStatus = "Google did not return an authorization prompt." }
                    else gmailAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { gmailSyncing = false; gmailStatus = "Google did not provide Gmail access. Please try again." }
                    else syncWithToken(token, mode, targetMessageId)
                }
            }
            .addOnFailureListener { gmailSyncing = false; gmailStatus = it.message ?: "Gmail authorization failed." }
    }

    fun loadSkippedPreview(accessToken: String) {
        val entry = selectedSkipped ?: return
        coroutineScope.launch {
            try {
                val email = withContext(Dispatchers.IO) { GmailInboxReader.getBankEmail(accessToken, entry.messageId) }
                if (selectedSkipped?.messageId == entry.messageId) skippedPreview = email
            } catch (e: Exception) {
                skippedPreviewError = if (e is GmailApiException && e.statusCode == 401)
                    "Gmail access expired. Reconnect Gmail and try again."
                else "Could not open this email. Try again."
            } finally {
                skippedPreviewLoading = false
            }
        }
    }

    val skippedPreviewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!) }
                .onSuccess { authorization ->
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { skippedPreviewLoading = false; skippedPreviewError = "Gmail access was unavailable." }
                    else loadSkippedPreview(token)
                }
                .onFailure { skippedPreviewLoading = false; skippedPreviewError = "Gmail authorization failed." }
        } else {
            skippedPreviewLoading = false
            skippedPreviewError = "Gmail access was not granted."
        }
    }

    fun openSkippedEmail(entry: SyncLedgerEntry, addMode: Boolean = false) {
        if (skippedPreviewLoading) return
        selectedSkipped = entry
        skippedAddMode = addMode
        skippedActionError = ""
        skippedPreview = null
        skippedPreviewError = ""
        skippedPreviewLoading = true
        if (activity == null) { skippedPreviewLoading = false; skippedPreviewError = "Gmail is unavailable in this screen."; return }
        val request = AuthorizationRequest.builder().setRequestedScopes(gmailScope).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent == null) { skippedPreviewLoading = false; skippedPreviewError = "Google did not return an authorization prompt." }
                    else skippedPreviewLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { skippedPreviewLoading = false; skippedPreviewError = "Gmail access was unavailable." }
                    else loadSkippedPreview(token)
                }
            }
            .addOnFailureListener { skippedPreviewLoading = false; skippedPreviewError = "Gmail authorization failed." }
    }

    fun disconnectGmail() {
        Identity.getAuthorizationClient(context).revokeAccess(
            RevokeAccessRequest.builder().setScopes(gmailScope).build()
        ).addOnCompleteListener {
            gmailConnected = false
            context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).edit()
                .remove("gmail_connected").apply()
            gmailStatus = "Gmail disconnected. Previously imported transactions remain on this device."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Canvas, surface = Surface, onSurface = White)) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Ink,
                    contentColor = Muted,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars,
                ) {
                    listOf("Home", "Review", "Activity", "Settings").forEach { item ->
                        val icon = when (item) { "Home" -> Icons.Default.Home; "Review" -> Icons.Default.Inbox; "Activity" -> Icons.Default.AccountBalanceWallet; else -> Icons.Default.Settings }
                        NavigationBarItem(selected = tab == item || (tab == "Skipped" && item == "Review"), onClick = { tab = item }, icon = { Icon(icon, contentDescription = item) }, label = { Text(item, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Mint, selectedTextColor = Mint, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Surface2))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Canvas)) {
                when (tab) {
                    "Home" -> HomeScreen(
                        expenses = expenses,
                        onReview = { tab = "Review" },
                        onManualImport = { error = ""; manualImportExpanded = true; tab = "Review" },
                        onOpenActivity = { tab = "Activity" },
                        onOpenMonthlyActivity = { activityDateMode = "Monthly"; tab = "Activity" },
                        onOpenSettings = { tab = "Settings" }
                    )
                    "Review" -> ReviewScreen(
                        expenses = expenses,
                        skippedToRecheck = currentAccountKey?.let { recheckableCount(syncLedger, it) } ?: 0,
                        onViewSkipped = { tab = "Skipped" },
                        actionsEnabled = !gmailSyncing,
                        importText = importText,
                        senderText = senderText,
                        manualImportExpanded = manualImportExpanded,
                        onToggleManualImport = { manualImportExpanded = !manualImportExpanded; error = "" },
                        onSenderChange = { senderText = it },
                        onTextChange = { importText = it; error = "" },
                        error = error,
                        onImport = {
                            val parsed = parseAlert(importText, senderText)
                            if (parsed == null) error = "This doesn't look like a supported bank transaction alert. Check the sender and transaction details."
                            else { persist(listOf(parsed.copy(approved = false)) + expenses); importText = ""; senderText = ""; error = "Added to review"; manualImportExpanded = false }
                        },
                        onApprove = { id -> persist(expenses.map { if (it.id == id) it.copy(approved = true) else it }) },
                        onApproveMany = { ids ->
                            persist(expenses.map { if (it.id in ids && !it.approved) it.copy(approved = true) else it })
                        },
                        onDelete = { id -> deleteExpense(id) },
                        onEdit = { editing = it }
                    )
                    "Activity" -> ActivityScreen(
                        expenses = expenses.filter { it.approved },
                        dateMode = activityDateMode,
                        startDate = activityStartDate,
                        endDate = activityEndDate,
                        onDateModeChange = { activityDateMode = it },
                        onStartDateChange = { activityStartDate = it },
                        onEndDateChange = { activityEndDate = it },
                        onReview = { tab = "Review" },
                    )
                    "Skipped" -> SkippedAlertsScreen(
                        entries = currentAccountKey?.let { account -> syncLedger.entries.filter {
                            it.accountKey == account && it.state == SyncMessageState.SKIPPED
                        } }.orEmpty(),
                        lastReport = syncLedger.lastReport,
                        onBack = { tab = "Review" },
                        onOpenEmail = { openSkippedEmail(it) },
                        onAddToReview = { openSkippedEmail(it, addMode = true) },
                        onDismissEmail = { dismissSkippedEntry = it; skippedActionError = "" },
                        onRecheckOne = { requestGmailSync(GmailSyncRequestMode.RECHECK_SKIPPED, it.messageId) },
                        actionsEnabled = !gmailSyncing,
                    )
                    else -> SettingsScreen(
                        onManualImport = { error = ""; manualImportExpanded = true; tab = "Review" },
                        onSyncGmail = {
                            val saved = currentAccountKey?.let { syncLedger.cursorFor(it, gmailStartDate.toString(), gmailEndDate.toString()) }
                            requestGmailSync(if (saved != null) GmailSyncRequestMode.CONTINUE else GmailSyncRequestMode.NORMAL)
                        },
                        onDisconnectGmail = { disconnectGmail() },
                        gmailConnected = gmailConnected,
                        gmailSyncing = gmailSyncing,
                        gmailStatus = gmailStatus,
                        scanProgressText = scanProgressText,
                        scanProgressFraction = scanProgressFraction,
                        syncReport = syncLedger.lastReport,
                        canContinue = currentAccountKey?.let { syncLedger.cursorFor(it, gmailStartDate.toString(), gmailEndDate.toString()) } != null,
                        skippedToRecheck = currentAccountKey?.let { recheckableCount(syncLedger, it) } ?: 0,
                        onContinue = { requestGmailSync(GmailSyncRequestMode.CONTINUE) },
                        onRecheckSkipped = { requestGmailSync(GmailSyncRequestMode.RECHECK_SKIPPED) },
                        startDate = gmailStartDate,
                        endDate = gmailEndDate,
                        onStartDateChange = { gmailStartDate = it; prefs.edit().putString("gmail_start_date", it.toString()).apply() },
                        onEndDateChange = { gmailEndDate = it; prefs.edit().putString("gmail_end_date", it.toString()).apply() },
                        onExportBackup = { backupMessage = ""; backupExportLauncher.launch("pocketlog-backup.json") },
                        onExportCsv = { backupMessage = ""; csvExportLauncher.launch("pocketlog-transactions.csv") },
                        onImportBackup = { backupMessage = ""; backupImportLauncher.launch(arrayOf("application/json", "text/*")) },
                        backupMessage = backupMessage,
                        backupActionsEnabled = !gmailSyncing,
                    )
                }
            }
        }
        editing?.let { expense -> EditExpenseDialog(expense, onDismiss = { editing = null }, onSave = { updated ->
            if (!gmailSyncing) { persist(expenses.map { if (it.id == updated.id) updated else it }); editing = null }
            else gmailStatus = "Wait for Gmail sync to finish before changing transactions."
        }) }
        val preview = backupPreview
        val importedBackup = pendingBackup
        if (preview != null && importedBackup != null) {
            BackupMergeDialog(
                preview = preview,
                onDismiss = { backupPreview = null; pendingBackup = null },
                onApply = {
                    if (gmailSyncing) backupMessage = "Wait for Gmail sync to finish before restoring a backup."
                    else runCatching {
                        val merged = ExpenseBackupCodec.merge(ExpenseBackup(expenses, syncStore.load().ledger), importedBackup).backup
                        syncStore.commit(SyncSnapshot(merged.transactions, merged.syncLedger))
                        expenses = merged.transactions
                        syncLedger = merged.syncLedger
                        backupMessage = "Restore complete. Existing transactions and dismissals were preserved."
                        backupPreview = null
                        pendingBackup = null
                    }.onFailure { backupMessage = "Could not restore backup: ${it.message ?: "unknown error"}" }
                }
            )
        }
        selectedSkipped?.let { entry ->
            SkippedEmailDialog(
                entry = entry,
                email = skippedPreview,
                loading = skippedPreviewLoading,
                error = skippedPreviewError,
                addMode = skippedAddMode,
                actionError = skippedActionError,
                actionsEnabled = !gmailSyncing,
                onDismiss = { selectedSkipped = null; skippedPreview = null; skippedAddMode = false },
                onRetry = { openSkippedEmail(entry, skippedAddMode) },
                onAddMode = { skippedAddMode = true; skippedActionError = "" },
                onSave = { proposed ->
                    if (gmailSyncing) skippedActionError = "Wait for Gmail sync to finish."
                    else {
                        val original = SyncSnapshot(expenses, syncLedger)
                        val updated = original.withManuallyAddedSkippedEmail(entry, proposed, System.currentTimeMillis())
                        if (updated == original) skippedActionError = "This email has already been handled."
                        else runCatching { syncStore.commit(updated) }
                            .onSuccess {
                                expenses = updated.expenses
                                syncLedger = updated.ledger
                                selectedSkipped = null
                                skippedPreview = null
                                skippedAddMode = false
                                tab = "Review"
                            }
                            .onFailure { skippedActionError = it.message ?: "Could not save this transaction." }
                    }
                },
            )
        }
        dismissSkippedEntry?.let { entry ->
            AlertDialog(
                onDismissRequest = { dismissSkippedEntry = null },
                title = { Text("Dismiss this email?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("It will leave the skipped list and will not be imported during future scans. The email stays in Gmail.")
                        if (skippedActionError.isNotBlank()) Text(skippedActionError, color = Orange, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (gmailSyncing) skippedActionError = "Wait for Gmail sync to finish."
                        else {
                            val original = SyncSnapshot(expenses, syncLedger)
                            val updated = original.withDismissedSkippedEmail(entry, System.currentTimeMillis())
                            if (updated == original) skippedActionError = "This email has already been handled."
                            else runCatching { syncStore.commit(updated) }
                                .onSuccess { syncLedger = updated.ledger; dismissSkippedEntry = null }
                                .onFailure { skippedActionError = it.message ?: "Could not dismiss this email." }
                        }
                    }) { Text("Dismiss email", color = Orange) }
                },
                dismissButton = { TextButton(onClick = { dismissSkippedEntry = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    expenses: List<Expense>,
    onReview: () -> Unit,
    onManualImport: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenMonthlyActivity: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val today = LocalDate.now()
    val approved = expenses.filter { it.approved }
    val confirmedExpenses = approved.filter { it.recordType == "Expense" }
    val currentMonth = YearMonth.from(today)
    val currentMonthExpenses = confirmedExpenses.filter { expenseLocalDate(it)?.let { date -> YearMonth.from(date) == currentMonth && !date.isAfter(today) } == true }
    val monthTotal = currentMonthExpenses.sumOf { it.amount }
    val pending = expenses.count { !it.approved }
    val recent = transactionsNewestFirst(approved).take(3)
    val previousMonth = currentMonth.minusMonths(1)
    val previousPeriodEnd = previousMonth.atDay(today.dayOfMonth.coerceAtMost(previousMonth.lengthOfMonth()))
    val previousPeriodTotal = confirmedExpenses.filter { expenseLocalDate(it)?.let { date ->
        YearMonth.from(date) == previousMonth && !date.isAfter(previousPeriodEnd)
    } == true }.sumOf { it.amount }
    val trend = (5 downTo 0).map { monthsAgo ->
        val month = currentMonth.minusMonths(monthsAgo.toLong())
        month to confirmedExpenses.filter { expenseLocalDate(it)?.let { date ->
            YearMonth.from(date) == month && !date.isAfter(today)
        } == true }.sumOf { it.amount }
    }
    val topMerchants = currentMonthExpenses
        .filter { it.merchant.isNotBlank() }
        .groupBy { it.merchant.trim().uppercase(Locale.ROOT) }
        .values.map { entries -> Triple(entries.first().merchant.trim(), entries.sumOf { it.amount }, entries.size) }
        .sortedByDescending { it.second }
        .take(3)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Canvas, Color(0xFF0D1B30), Canvas))),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Pocketlog", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Your spending, pulled from your inbox.", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, contentDescription = "Open Settings", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Overview", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(today.format(DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)), color = Muted, fontSize = 12.sp)
            }
        }
        if (pending > 0) item {
            DashboardMetric(pending.toString(), "awaiting review", Mint, Icons.Default.Mail, onReview, Modifier.fillMaxWidth())
        }
        item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Spent in ${today.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))}", color = Muted, fontSize = 13.sp)
                Text(rupiah(monthTotal), color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("${currentMonthExpenses.size} confirmed ${if (currentMonthExpenses.size == 1) "expense" else "expenses"} · 1–${today.dayOfMonth} ${today.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))}", color = Muted, fontSize = 12.sp)
                if (previousPeriodTotal > 0L) {
                    val change = ((monthTotal - previousPeriodTotal).toDouble() / previousPeriodTotal * 100).roundToInt()
                    val comparison = when {
                        change > 0 -> "↑ $change% more"
                        change < 0 -> "↓ ${-change}% less"
                        else -> "About the same"
                    }
                    Text("$comparison vs 1–${previousPeriodEnd.dayOfMonth} ${previousPeriodEnd.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))}", color = if (change > 0) Orange else Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else if (currentMonthExpenses.isNotEmpty()) {
                    Text("No spending recorded for the same period last month", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).clickable(onClick = onOpenMonthlyActivity).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Spending trend", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Last 6 months · values in Rp millions", color = Muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "View monthly activity", tint = Mint, modifier = Modifier.size(20.dp))
                }
                if (trend.any { it.second > 0L }) {
                    DashboardSpendingTrend(trend)
                    Text("${today.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))} is month to date", color = Muted, fontSize = 11.sp)
                } else {
                    Text("No confirmed expenses in this period", color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (topMerchants.isNotEmpty()) item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Top merchants this month", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                topMerchants.forEachIndexed { index, (merchant, amount, count) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${index + 1}", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(merchant, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$count ${if (count == 1) "expense" else "expenses"}", color = Muted, fontSize = 10.sp)
                        }
                        Text(rupiah(amount), color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent transactions", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.clickable(onClick = onOpenActivity).height(48.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("See all", color = Mint, fontSize = 12.sp)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Mint, modifier = Modifier.size(17.dp))
                }
            }
        }
        if (recent.isEmpty()) {
            item { EmptyState("No confirmed transactions yet", "Confirmed alerts will appear here. Review an alert to get started.", "Open Review", onReview) }
        } else {
            item {
                Column {
                    recent.forEach { expense -> DashboardRecentRow(expense, onOpenActivity) }
                }
            }
        }
        if (expenses.isEmpty()) item { OutlinedButton(onClick = onManualImport, modifier = Modifier.fillMaxWidth()) { Text("Paste an alert manually") } }
    }
}

@Composable
private fun DashboardSpendingTrend(months: List<Pair<YearMonth, Long>>) {
    val maxAmount = months.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        months.forEachIndexed { index, (month, amount) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(String.format(Locale("id", "ID"), "%.1f", amount / 1_000_000.0), color = if (index == months.lastIndex) Mint else Muted, fontSize = 10.sp, maxLines = 1)
                Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth(0.7f).height((76f * amount.toFloat() / maxAmount.toFloat()).coerceAtLeast(if (amount > 0L) 3f else 0f).dp).background(if (index == months.lastIndex) Mint else Color(0xFF4D6687), RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)))
                }
                Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)), color = if (index == months.lastIndex) White else Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun GmailScanCard(
    connected: Boolean,
    syncing: Boolean,
    status: String,
    progressText: String?,
    progressFraction: Float?,
    report: SyncReport?,
    canContinue: Boolean,
    validDateRange: Boolean,
    onScan: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Mail, contentDescription = null, tint = Mint, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text("Gmail scan", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (connected) "Connected" else "Not connected", color = if (connected) Mint else Muted, fontSize = 12.sp)
            }
            Button(onClick = onScan, enabled = !syncing && validDateRange, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (syncing) "Scanning…" else if (connected) "Scan Gmail" else "Connect", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        if (report != null) {
            HorizontalDivider(color = Surface2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Column(Modifier.weight(1.4f)) {
                    Text("Last scan", color = Muted, fontSize = 11.sp)
                    Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(Date(report.finishedAtMillis)), color = White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.width(1.dp).height(27.dp).background(Surface2))
                DashboardScanStat(report.checked, "fetched", Modifier.weight(0.75f))
                Box(Modifier.width(1.dp).height(27.dp).background(Surface2))
                DashboardScanStat(report.added, "added", Modifier.weight(0.65f))
                Box(Modifier.width(1.dp).height(27.dp).background(Surface2))
                DashboardScanStat(report.skipped, "skipped", Modifier.weight(0.75f))
            }
            if (canContinue && !syncing) TextButton(onClick = onContinue, contentPadding = PaddingValues(0.dp)) { Text("Resume saved scan", color = Mint) }
        } else {
            Text("Scan Gmail for supported bank alerts.", color = Muted, fontSize = 12.sp)
        }
        if (status.isNotBlank()) Text(status, color = if (status.contains("error", true)) Orange else Muted, fontSize = 12.sp, lineHeight = 17.sp)
        if (progressText != null) GmailScanProgress(progressText, progressFraction)
    }
}

@Composable
private fun DashboardMetric(value: String, label: String, accent: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(59.dp).background(Surface, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(29.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = Muted, fontSize = 10.sp, maxLines = 2, lineHeight = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DashboardScanStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun DashboardRecentRow(expense: Expense, onClick: () -> Unit) {
    val date = expenseLocalDate(expense)
    Row(Modifier.fillMaxWidth().height(50.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(Modifier.width(31.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date?.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))?.uppercase(Locale.ENGLISH) ?: "—", color = Muted, fontSize = 8.sp, lineHeight = 10.sp)
            Text(date?.dayOfMonth?.toString()?.padStart(2, '0') ?: "—", color = White, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.width(12.dp).height(50.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(7.dp).background(Mint, CircleShape))
            Box(Modifier.width(1.dp).height(18.dp).background(Surface2))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(expense.merchant, color = White, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${expense.bank}  ·  ${expense.recordType}", color = Muted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(rupiah(expense.amount), color = White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ReviewScreen(
    expenses: List<Expense>,
    skippedToRecheck: Int,
    onViewSkipped: () -> Unit,
    actionsEnabled: Boolean,
    importText: String,
    senderText: String,
    manualImportExpanded: Boolean,
    onToggleManualImport: () -> Unit,
    onSenderChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    error: String,
    onImport: () -> Unit,
    onApprove: (String) -> Unit,
    onApproveMany: (Set<String>) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (Expense) -> Unit
) {
    val pending = transactionsNewestFirst(expenses.filter { !it.approved })
    var page by remember { mutableIntStateOf(0) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmationIds by remember { mutableStateOf<Set<String>?>(null) }
    var confirmingAll by remember { mutableStateOf(false) }
    val activeSelection = selectedIds.intersect(pending.mapTo(mutableSetOf()) { it.id })
    val currentPage = page.coerceIn(0, ((pending.size - 1).coerceAtLeast(0)) / LIST_PAGE_SIZE)
    val pageItems = pending.drop(currentPage * LIST_PAGE_SIZE).take(LIST_PAGE_SIZE)
    val pageIds = pageItems.mapTo(mutableSetOf()) { it.id }
    val wholePageSelected = pageIds.isNotEmpty() && activeSelection.containsAll(pageIds)
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Review", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${pending.size} ${if (pending.size == 1) "transaction" else "transactions"} waiting for your review", color = Muted, fontSize = 14.sp)
            }
        }
        if (skippedToRecheck > 0) item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Mint, modifier = Modifier.size(22.dp))
                    Column {
                        Text("Skipped emails", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Inspect emails that were not imported.", color = Muted, fontSize = 11.sp)
                    }
                }
                Button(onClick = onViewSkipped, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.Inbox, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Inspect $skippedToRecheck skipped ${if (skippedToRecheck == 1) "email" else "emails"}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }
        if (pending.isNotEmpty()) {
            item { ListPager(total = pending.size, page = currentPage, onPageChange = { page = it }) }
            item {
                Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wholePageSelected, onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + pageIds else selectedIds - pageIds
                        }, enabled = actionsEnabled, colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted))
                        Text("Select page (${pageItems.size})", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedIds = pending.mapTo(mutableSetOf()) { it.id } }, enabled = actionsEnabled) {
                            Text("Select all ${pending.size}", color = Mint, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${activeSelection.size} selected", color = Muted, fontSize = 12.sp)
                        TextButton(onClick = { confirmingAll = true; confirmationIds = pending.mapTo(mutableSetOf()) { it.id } }, enabled = actionsEnabled) {
                            Text("Approve all ${pending.size}", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onToggleManualImport,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (manualImportExpanded) "Close manual import" else "Paste an alert manually", fontWeight = FontWeight.SemiBold)
            }
        }
        if (manualImportExpanded) {
            item {
                Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paste a bank alert", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("We’ll read supported transaction details and add the result here for review.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = senderText,
                        onValueChange = onSenderChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Sender email") },
                        placeholder = { Text("noreply@bank.co.id", color = Muted) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Surface2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint)
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        placeholder = { Text("Paste transaction alert text…", color = Muted) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Surface2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint)
                    )
                    if (error.isNotEmpty()) Text(error, color = if (error == "Added to review") Mint else Orange, fontSize = 12.sp, lineHeight = 17.sp)
                    Button(onClick = onImport, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(14.dp)) {
                        Text("Add to review", fontWeight = FontWeight.Bold)
                    }
                    Text("Gmail can also share an alert directly here.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        if (pending.isEmpty()) {
            item { EmptyState(title = "You're all caught up", detail = "New bank alerts will appear here before they count in your expenses.") }
        } else {
            item { Text("Check each transaction before confirming it.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp) }
            items(pageItems, key = { it.id }) { expense ->
                PendingCard(expense, actionsEnabled, expense.id in activeSelection,
                    onSelectedChange = { checked -> selectedIds = if (checked) selectedIds + expense.id else selectedIds - expense.id },
                    onApprove = onApprove, onDelete = onDelete, onEdit = onEdit)
            }
            item { ListPager(total = pending.size, page = currentPage, onPageChange = { page = it }) }
        }
    }
    if (activeSelection.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().background(Surface2).padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${activeSelection.size} selected", color = White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear", color = Muted) }
            Button(onClick = { confirmingAll = false; confirmationIds = activeSelection }, enabled = actionsEnabled, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                Text("Approve selected", fontWeight = FontWeight.Bold)
            }
        }
    }
    }
    confirmationIds?.let { ids ->
        val targets = pending.filter { it.id in ids }
        val expenseTargets = targets.filter { it.recordType == "Expense" }
        val transferCount = targets.size - expenseTargets.size
        AlertDialog(
            onDismissRequest = { confirmationIds = null },
            title = { Text(if (confirmingAll) "Approve all pending?" else "Approve selected transactions?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${targets.size} transactions will move to Activity.")
                    Text("${expenseTargets.size} expenses · $transferCount transfers")
                    Text("Expense total: ${rupiah(expenseTargets.sumOf { it.amount })}")
                    Text("Check the details before approving. This action updates your confirmed records.")
                }
            },
            confirmButton = { TextButton(onClick = {
                if (targets.isNotEmpty() && actionsEnabled) onApproveMany(targets.mapTo(mutableSetOf()) { it.id })
                selectedIds = selectedIds - ids
                confirmationIds = null
            }, enabled = actionsEnabled && targets.isNotEmpty()) { Text("Approve ${targets.size}", color = Mint) } },
            dismissButton = { TextButton(onClick = { confirmationIds = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PendingCard(
    expense: Expense,
    actionsEnabled: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (Expense) -> Unit,
) {
    var expanded by remember(expense.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = actionsEnabled, colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted))
            Column(Modifier.weight(1f)) {
                Text(expense.merchant, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${expense.date}  ·  ${expense.bank}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(7.dp))
            Text(rupiah(expense.amount), color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${expense.recordType}  ·  ${expense.category}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                Text(if (expanded) "Less" else "Details", color = Mint, fontSize = 12.sp)
            }
        }
        if (expanded) {
        Text("Source: ${expense.source}", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        if (expense.gmailMessageId != null) {
            Column(
                Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Source email", color = White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                expense.emailSender?.let { Text("From: $it", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                expense.emailSubject?.let { Text("Subject: $it", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                expense.emailReceivedAtMillis?.let {
                    val received = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(Date(it))
                    Text("Received: $received", color = Muted, fontSize = 12.sp)
                }
                expense.gmailAccountEmail?.let { Text("Account: $it", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (expense.emailSender == null && expense.emailSubject == null && expense.emailReceivedAtMillis == null) {
                    Text("Email details unavailable", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onEdit(expense) }, enabled = actionsEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = White)) { Text("Edit") }
            IconButton(onClick = { onDelete(expense.id) }, enabled = actionsEnabled, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Delete, contentDescription = "Delete transaction", tint = Orange) }
            Button(onClick = { onApprove(expense.id) }, enabled = actionsEnabled, modifier = Modifier.weight(1.2f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Confirm", fontWeight = FontWeight.Bold)
            }
        }
        }
    }
}

@Composable
private fun ActivityScreen(
    expenses: List<Expense>,
    dateMode: String,
    startDate: LocalDate,
    endDate: LocalDate,
    onDateModeChange: (String) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit,
    onReview: () -> Unit,
) {
    val today = LocalDate.now()
    var monthlyYear by remember { mutableIntStateOf(today.year) }
    val earliestYear = expenses.mapNotNull(::expenseLocalDate).minOfOrNull { it.year } ?: today.year
    val invalidRange = dateMode == "Custom" && endDate.isBefore(startDate)
    val visible = when (dateMode) {
        "This month" -> transactionsInRange(expenses, today.withDayOfMonth(1), today)
        "Custom" -> if (invalidRange) emptyList() else transactionsInRange(expenses, startDate, endDate)
        else -> transactionsNewestFirst(expenses)
    }
    val expenseTotal = visible.filter { it.recordType == "Expense" }.sumOf { it.amount }
    var page by remember(dateMode, startDate, endDate) { mutableIntStateOf(0) }
    val currentPage = page.coerceIn(0, ((visible.size - 1).coerceAtLeast(0)) / LIST_PAGE_SIZE)
    val pageItems = visible.drop(currentPage * LIST_PAGE_SIZE).take(LIST_PAGE_SIZE)
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage, dateMode, startDate, endDate) { listState.scrollToItem(0) }
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Activity", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Confirmed transactions, newest first", color = Muted, fontSize = 14.sp)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "This month", "Custom", "Monthly").forEach { mode ->
                    FilterChip(
                        selected = dateMode == mode,
                        onClick = { onDateModeChange(mode) },
                        label = { Text(mode) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White),
                    )
                }
            }
        }
        if (dateMode == "Custom") {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateChoice("From", startDate, onStartDateChange, Modifier.weight(1f))
                    DateChoice("To", endDate, onEndDateChange, Modifier.weight(1f))
                }
                if (invalidRange) Text("End date must be on or after the start date.", color = Orange, fontSize = 12.sp)
            }
        }
        if (dateMode == "Monthly") {
            val yearExpenses = expenses.filter { it.recordType == "Expense" && expenseLocalDate(it)?.year == monthlyYear }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { monthlyYear-- }, enabled = monthlyYear > earliestYear) { Text("‹ Earlier", color = if (monthlyYear > earliestYear) Mint else Muted) }
                    Text(monthlyYear.toString(), color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { monthlyYear++ }, enabled = monthlyYear < today.year) { Text("Later ›", color = if (monthlyYear < today.year) Mint else Muted) }
                }
                Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Spending in $monthlyYear", color = Muted, fontSize = 13.sp)
                    Text(rupiah(yearExpenses.sumOf { it.amount }), color = White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Text("${yearExpenses.size} confirmed expenses · transfers excluded", color = Muted, fontSize = 12.sp)
                }
            }
            val lastMonth = if (monthlyYear == today.year) today.monthValue else 12
            items((1..lastMonth).toList().asReversed(), key = { "month-$monthlyYear-$it" }) { month ->
                val monthStart = YearMonth.of(monthlyYear, month).atDay(1)
                val monthEnd = YearMonth.of(monthlyYear, month).atEndOfMonth().coerceAtMost(today)
                val monthly = yearExpenses.filter { expenseLocalDate(it)?.monthValue == month }
                Row(
                    Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).clickable {
                        onStartDateChange(monthStart)
                        onEndDateChange(monthEnd)
                        onDateModeChange("Custom")
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(monthStart.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (monthly.isEmpty()) "No expenses · View transactions" else "${monthly.size} ${if (monthly.size == 1) "expense" else "expenses"} · View transactions", color = Muted, fontSize = 12.sp)
                    }
                    Text(rupiah(monthly.sumOf { it.amount }), color = Mint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            return@LazyColumn
        }
        item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${visible.size} confirmed ${if (visible.size == 1) "transaction" else "transactions"}", color = Muted, fontSize = 13.sp)
                Text(rupiah(expenseTotal), color = White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text("Total expenses in selected dates · transfers excluded", color = Muted, fontSize = 12.sp)
            }
        }
        when {
            expenses.isEmpty() -> item { EmptyState("Nothing confirmed yet", "Review a bank alert to start your activity.", "Review alerts", onReview) }
            visible.isEmpty() -> item { EmptyState("No transactions in these dates", "Choose another date range to see your confirmed transactions.") }
            else -> pageItems.groupBy { it.date }.forEach { (date, dayExpenses) ->
                item(key = "date-$date") { Text(date, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(dayExpenses, key = { it.id }) { ExpenseRow(it) }
            }
        }
        if (visible.isNotEmpty()) item { ListPager(total = visible.size, page = currentPage, onPageChange = { page = it }) }
    }
}

@Composable
private fun SkippedAlertsScreen(
    entries: List<SyncLedgerEntry>,
    lastReport: SyncReport?,
    onBack: () -> Unit,
    onOpenEmail: (SyncLedgerEntry) -> Unit,
    onAddToReview: (SyncLedgerEntry) -> Unit,
    onDismissEmail: (SyncLedgerEntry) -> Unit,
    onRecheckOne: (SyncLedgerEntry) -> Unit,
    actionsEnabled: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val lastScanEntries = entries.filter { lastReport != null && it.checkedAtMillis >= lastReport.startedAtMillis }
    val attentionEntries = entries.filter { it.skipReason == "Possible transaction needs review" }
    val selected = when (filter) { 1 -> attentionEntries; 2 -> lastScanEntries; else -> entries }
    val visible = selected.filter {
        val dateLabel = it.receivedAtMillis?.let { millis -> SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(Date(millis)) }
        query.isBlank() || listOf(it.subject, it.sender, it.skipReason, dateLabel).any { field -> field.orEmpty().contains(query, ignoreCase = true) }
    }.sortedWith(compareByDescending<SyncLedgerEntry> { it.receivedAtMillis ?: Long.MIN_VALUE }.thenByDescending { it.checkedAtMillis })
    var page by remember(filter, query) { mutableIntStateOf(0) }
    val currentPage = page.coerceIn(0, ((visible.size - 1).coerceAtLeast(0)) / LIST_PAGE_SIZE)
    val pageItems = visible.drop(currentPage * LIST_PAGE_SIZE).take(LIST_PAGE_SIZE)
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage, filter, query) { listState.scrollToItem(0) }
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Back to Review", color = Mint) }
            Text("Skipped emails", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Bank emails without an imported transaction.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All (${entries.size})") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White))
                FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Needs attention (${attentionEntries.size})") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White))
                FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Last scan (${lastScanEntries.size})") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White))
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search email or date") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Surface2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint),
            )
        }
        if (visible.isEmpty()) {
            item { EmptyState("No skipped emails here", if (query.isBlank()) "Choose All to see every skipped email." else "Try another search.") }
        } else {
            items(pageItems, key = { "${it.accountKey}:${it.messageId}" }) { entry ->
                Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.subject?.ifBlank { "Untitled email" } ?: "Untitled email", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.sender?.ifBlank { "Unknown sender" } ?: "Unknown sender", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    entry.receivedAtMillis?.let { Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(Date(it)), color = Muted, fontSize = 12.sp) }
                    Text(skippedReasonLabel(entry), color = Orange, fontSize = 12.sp, lineHeight = 17.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { onOpenEmail(entry) }, enabled = actionsEnabled, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Surface2, contentColor = Mint)) {
                            Icon(Icons.Default.Mail, contentDescription = "Read email")
                        }
                        FilledTonalIconButton(onClick = { onRecheckOne(entry) }, enabled = actionsEnabled, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Surface2, contentColor = Mint)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recheck email")
                        }
                        FilledIconButton(onClick = { onAddToReview(entry) }, enabled = actionsEnabled, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = Ink)) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add transaction to Review")
                        }
                        FilledTonalIconButton(onClick = { onDismissEmail(entry) }, enabled = actionsEnabled, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Surface2, contentColor = Orange)) {
                            Icon(Icons.Default.Delete, contentDescription = "Dismiss email from app")
                        }
                    }
                }
            }
            item { ListPager(total = visible.size, page = currentPage, onPageChange = { page = it }) }
        }
    }
}

@Composable
private fun SkippedEmailDialog(
    entry: SyncLedgerEntry,
    email: BankEmail?,
    loading: Boolean,
    error: String,
    addMode: Boolean,
    actionError: String,
    actionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAddMode: () -> Unit,
    onSave: (Expense) -> Unit,
) {
    var merchant by remember(entry.messageId) { mutableStateOf("") }
    var amount by remember(entry.messageId) { mutableStateOf("") }
    var bank by remember(entry.messageId) { mutableStateOf(bankNameForSender(entry.sender)) }
    var category by remember(entry.messageId) { mutableStateOf("Other") }
    var recordType by remember(entry.messageId) { mutableStateOf("Expense") }
    var transactionDate by remember(entry.messageId) {
        mutableStateOf(entry.receivedAtMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now())
    }
    var showEmail by remember(entry.messageId) { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    LaunchedEffect(email?.id) {
        if (email != null) {
            val parsed = parseAlert(email.body, email.sender)
                ?: BankAlertParser.parseJagoAlertWithReceivedDate(email.body, email.sender, email.receivedAtMillis)
            if (parsed != null) {
                merchant = parsed.merchant
                amount = parsed.amount.toString()
                bank = parsed.bank
                category = parsed.category
                recordType = parsed.recordType
                expenseLocalDate(parsed)?.let { transactionDate = it }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (addMode) "Add to Review" else entry.subject ?: "Skipped email") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(entry.subject ?: "Untitled email", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(entry.sender ?: "Unknown sender", color = Muted, fontSize = 12.sp)
                entry.receivedAtMillis?.let { Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(Date(it)), color = Muted, fontSize = 12.sp) }
                Text("Why skipped: ${skippedReasonLabel(entry)}", color = Orange, fontSize = 12.sp)
                when {
                    loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    error.isNotBlank() -> {
                        Text(error, color = Orange, fontSize = 13.sp)
                        TextButton(onClick = onRetry) { Text("Try again", color = Mint) }
                    }
                    email != null && !addMode -> Text(email.body.ifBlank { "This email has no readable text content." }.take(12_000), color = White, fontSize = 13.sp, lineHeight = 19.sp)
                }
                if (addMode && email != null) {
                    Text("Enter the transaction shown in this email. It will go to Review for approval.", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { showEmail = !showEmail }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showEmail) "Hide email details" else "Show email details", color = Mint)
                    }
                    if (showEmail) Text(email.body.ifBlank { "This email has no readable text content." }.take(12_000), color = White, fontSize = 12.sp, lineHeight = 17.sp)
                    OutlinedTextField(merchant, { merchant = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Merchant or description") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("Amount (IDR)") }, singleLine = true)
                    DateChoice("Transaction date", transactionDate, { transactionDate = it }, Modifier.fillMaxWidth())
                    OutlinedTextField(bank, { bank = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Bank") }, singleLine = true)
                    Text("Transaction type", color = Muted, fontSize = 12.sp)
                    Box {
                        OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(recordType, modifier = Modifier.weight(1f)); Text("Change") }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            listOf("Expense", "Transfer", "Income").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { recordType = value; typeMenu = false }) }
                        }
                    }
                    if (recordType == "Expense") {
                        Text("Category", color = Muted, fontSize = 12.sp)
                        Box {
                            OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(category, modifier = Modifier.weight(1f)); Text("Change") }
                            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                                listOf("Food & drink", "Transport", "Groceries", "Shopping", "Subscriptions", "Other").forEach { value ->
                                    DropdownMenuItem(text = { Text(value) }, onClick = { category = value; categoryMenu = false })
                                }
                            }
                        }
                    }
                    Text("Review the email before saving. This will not change the message in Gmail.", color = Muted, fontSize = 12.sp)
                }
                if (actionError.isNotBlank()) Text(actionError, color = Orange, fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (addMode) {
                TextButton(
                    enabled = actionsEnabled && email != null && merchant.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0L,
                    onClick = {
                        onSave(Expense(
                            merchant = merchant.trim(),
                            amount = amount.toLong(),
                            date = transactionDate.format(DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)),
                            category = if (recordType == "Expense") category else recordType,
                            source = "Gmail · manually added",
                            bank = bank.trim().ifBlank { "Unknown bank" },
                            recordType = recordType,
                        ))
                    },
                ) { Text("Add to Review", color = Mint) }
            } else TextButton(onClick = onDismiss) { Text("Close", color = Mint) }
        },
        dismissButton = {
            if (addMode) TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
            else if (email != null) TextButton(onClick = onAddMode, enabled = actionsEnabled) { Text("Add to Review", color = Mint) }
        },
    )
}

private fun bankNameForSender(sender: String?): String = when {
    sender.orEmpty().contains("bca", ignoreCase = true) -> "BCA"
    sender.orEmpty().contains("jago", ignoreCase = true) -> "Jago"
    sender.orEmpty().contains("mandiri", ignoreCase = true) -> "Mandiri"
    sender.orEmpty().contains("bni", ignoreCase = true) -> "BNI"
    sender.orEmpty().contains("bri", ignoreCase = true) -> "BRI"
    else -> "Unknown bank"
}

private fun skippedReasonLabel(entry: SyncLedgerEntry): String = when (entry.skipReason) {
    "No supported transaction found" -> "This email did not match a supported receipt format"
    else -> entry.skipReason ?: "No transaction was added"
}

@Composable
private fun ListPager(total: Int, page: Int, onPageChange: (Int) -> Unit) {
    val pageCount = ((total + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE).coerceAtLeast(1)
    val first = page * LIST_PAGE_SIZE + 1
    val last = minOf(total, (page + 1) * LIST_PAGE_SIZE)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Showing $first–$last of $total", color = Muted, fontSize = 12.sp)
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) { Text("Previous") }
                Text("Page ${page + 1} of $pageCount", color = White, fontSize = 13.sp)
                OutlinedButton(onClick = { onPageChange(page + 1) }, enabled = page + 1 < pageCount) { Text("Next") }
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(40.dp).background(Surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Mint, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(expense.merchant, color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${expense.bank}  ·  ${expense.date}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${if (expense.recordType == "Expense") "−" else ""}${rupiah(expense.amount)}", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun EmptyState(
    title: String,
    detail: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true
) {
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, enabled = actionEnabled, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                Text(action, color = Mint, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GmailScanProgress(progressText: String, progressFraction: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (progressFraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Mint, trackColor = Surface2)
        else LinearProgressIndicator(progress = { progressFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Mint, trackColor = Surface2)
        Text(progressText, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun SyncReportCard(
    report: SyncReport,
    canContinue: Boolean,
    skippedToRecheck: Int,
    syncing: Boolean,
    onContinue: () -> Unit,
    onRecheckSkipped: () -> Unit,
) {
    val whenLabel = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(Date(report.finishedAtMillis))
    val fromLabel = runCatching { LocalDate.parse(report.startDate).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(report.startDate)
    val toLabel = runCatching { LocalDate.parse(report.endDate).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(report.endDate)
    val oldestFetchedLabel = report.oldestFetchedAtMillis?.let {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(Date(it))
    }
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Last Gmail scan", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("${report.accountKey}  ·  $whenLabel", color = Muted, fontSize = 12.sp)
        Text("$fromLabel to $toLabel", color = Muted, fontSize = 12.sp)
        Text("${report.checked} fetched  ·  ${report.added} added  ·  ${report.skipped} skipped", color = White, fontSize = 13.sp, lineHeight = 19.sp)
        if (oldestFetchedLabel != null) Text("Oldest email opened in this batch: $oldestFetchedLabel", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        val matchedExisting = report.checked - report.added - report.skipped
        if (matchedExisting > 0) Text("$matchedExisting matched existing transactions", color = Muted, fontSize = 12.sp)
        if (report.alreadySeen > 0) Text("${report.alreadySeen} previously checked emails were skipped without reopening them", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        if (report.mode == SyncMode.RECHECK_SKIPPED) {
            Text("${report.rechecked} skipped alerts rechecked", color = Muted, fontSize = 12.sp)
        }
        if (report.hasMore) Text(
            when {
                report.mode == SyncMode.RECHECK_SKIPPED -> "More skipped alerts can be rechecked."
                syncing -> "The next saved Gmail page will scan automatically."
                else -> "More emails remain. Your place is saved; resume the scan to finish."
            },
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp,
        )
        report.error?.takeIf(String::isNotBlank)?.let { Text(it, color = Orange, fontSize = 12.sp, lineHeight = 17.sp) }
        if (!syncing && (canContinue || skippedToRecheck > 0)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canContinue) {
                    OutlinedButton(onClick = onContinue, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                        Text("Resume saved scan", fontSize = 13.sp)
                    }
                }
                if (skippedToRecheck > 0) {
                    OutlinedButton(onClick = onRecheckSkipped, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                        Text("Recheck skipped alerts ($skippedToRecheck)", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onManualImport: () -> Unit,
    onSyncGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    gmailConnected: Boolean,
    gmailSyncing: Boolean,
    gmailStatus: String,
    scanProgressText: String?,
    scanProgressFraction: Float?,
    syncReport: SyncReport?,
    canContinue: Boolean,
    skippedToRecheck: Int,
    onContinue: () -> Unit,
    onRecheckSkipped: () -> Unit,
    startDate: LocalDate,
    endDate: LocalDate,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit,
    onExportBackup: () -> Unit,
    onExportCsv: () -> Unit,
    onImportBackup: () -> Unit,
    backupMessage: String,
    backupActionsEnabled: Boolean,
) {
    var showScanDetails by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Settings", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Manage Gmail import and your data", color = Muted, fontSize = 14.sp)
        }
        GmailScanCard(
            connected = gmailConnected,
            syncing = gmailSyncing,
            status = gmailStatus,
            progressText = scanProgressText,
            progressFraction = scanProgressFraction,
            report = syncReport,
            canContinue = canContinue,
            validDateRange = !endDate.isBefore(startDate),
            onScan = onSyncGmail,
            onContinue = onContinue,
        )
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Mail, contentDescription = null, tint = Mint)
                Column(Modifier.weight(1f)) {
                    Text("Gmail import options", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
            Text("Import supported BCA, Livin’ by Mandiri, Bank Jago, and UOB transaction alerts. New items stay in Review until you confirm them.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Text("Email date range", color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateChoice("From", startDate, onStartDateChange, Modifier.weight(1f), enabled = !gmailSyncing)
                DateChoice("To", endDate, onEndDateChange, Modifier.weight(1f), enabled = !gmailSyncing)
            }
            Text("${startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))} – ${endDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", color = Muted, fontSize = 12.sp)
            if (endDate.isBefore(startDate)) Text("End date must be on or after the start date.", color = Orange, fontSize = 12.sp)
            if (syncReport != null) {
                TextButton(onClick = { showScanDetails = !showScanDetails }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showScanDetails) "Hide scan details" else "Show scan details", color = Mint)
                }
                if (showScanDetails) SyncReportCard(syncReport, canContinue, skippedToRecheck, gmailSyncing, onContinue, onRecheckSkipped)
            }
            Text("Google grants read-only access. Matching email content is processed on this device.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            if (gmailConnected) TextButton(onClick = onDisconnectGmail, enabled = !gmailSyncing, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Disconnect Gmail", color = Orange) }
        }
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manual import", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Paste a supported bank alert if you don’t want to sync Gmail.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = onManualImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                Text("Paste an alert manually", fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your data", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Backups include transactions and Gmail scan history, but never message bodies or Google access tokens. Restore merges records and keeps this device’s existing data.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = onExportBackup, enabled = backupActionsEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                Text("Export restorable backup (JSON)", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onImportBackup, enabled = backupActionsEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                Text("Restore from backup", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onExportCsv, enabled = backupActionsEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                Text("Export transactions as CSV", fontWeight = FontWeight.SemiBold)
            }
            if (backupMessage.isNotBlank()) Text(backupMessage, color = if (backupMessage.startsWith("Could not")) Orange else Mint, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text("Pocketlog v${BuildConfig.VERSION_NAME}  ·  IDR  ·  Stored on this device", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun BackupMergeDialog(
    preview: BackupMergePreview,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Restore backup?", color = White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review the merge before changing this device.", color = Muted)
                Text("${preview.transactionsAdded} transactions will be added", color = White)
                Text("${preview.duplicateTransactionIds + preview.duplicateGmailMessages} duplicates will be skipped", color = White)
                Text("${preview.dismissedTransactionsSkipped} dismissed emails will stay dismissed", color = White)
                Text("${preview.ledgerEntriesAdded} scan-history records and ${preview.cursorsAdded} saved scan cursors will be added", color = White)
                Text("Existing device records and dismissals take priority. This cannot be undone.", color = Orange, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("Restore", color = Mint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DateChoice(label: String, date: LocalDate, onDateSelected: (LocalDate) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showPicker = true }, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = White)) {
        Column {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(date.format(DateTimeFormatter.ofPattern("d MMM yyyy")), color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis -> onDateSelected(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showPicker = false
                }) { Text("Choose") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun EditExpenseDialog(expense: Expense, onDismiss: () -> Unit, onSave: (Expense) -> Unit) {
    var merchant by remember(expense.id) { mutableStateOf(expense.merchant) }
    var amount by remember(expense.id) { mutableStateOf(expense.amount.toString()) }
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var recordType by remember(expense.id) { mutableStateOf(expense.recordType) }
    var categoryMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    val categories = listOf("Food & drink", "Transport", "Groceries", "Shopping", "Subscriptions", "Other")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Edit transaction", color = White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true)
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Amount (IDR)") }, singleLine = true)
                Text("Transaction type", color = Muted, fontSize = 12.sp)
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(recordType, modifier = Modifier.weight(1f)); Text("Change") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        listOf("Expense", "Transfer", "Income").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { recordType = value; typeMenu = false }) }
                    }
                }
                if (recordType == "Expense") {
                    Text("Category", color = Muted, fontSize = 12.sp)
                    Box {
                        OutlinedButton(onClick = { categoryMenu = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(category, modifier = Modifier.weight(1f)); Text("Change") }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            categories.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { category = value; categoryMenu = false }) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { amount.toLongOrNull()?.let { onSave(expense.copy(merchant = merchant.ifBlank { "Unknown merchant" }, amount = it, category = if (recordType == "Expense") category else recordType, recordType = recordType)) } }) { Text("Save", color = Mint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
