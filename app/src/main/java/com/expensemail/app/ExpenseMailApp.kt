package com.expensemail.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.House
import androidx.compose.material3.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
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

@Composable
private fun PocketlogBottomBar(tab: String, onSelect: (String) -> Unit) {
    val navSurface = Color(0xFF10253A)
    val navBorder = Color(0xFF29435D)
    val bottomInsetPx = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toFloat()
    }
    val selectedDestination = when (tab) {
        "Review", "Skipped", "Coverage" -> "Coverage"
        "Activity" -> "Activity"
        "Categories", "Recurring", "Insights" -> "Spending"
        "Target" -> "Plan"
        "Settings" -> null
        else -> "Home"
    }
    val destinations = listOf("Coverage", "Activity", "Home", "Spending", "Plan")

    // The extra 22dp above the panel is the space occupied by the raised Home control.
    // Navigation-bar insets remain outside the 94dp content area and keep the gesture area clear.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (bottomInsetPx > 0f) {
                    drawRect(
                        color = navSurface,
                        topLeft = Offset(0f, size.height - bottomInsetPx),
                        size = Size(size.width, bottomInsetPx),
                    )
                }
            }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(96.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = navSurface,
            contentColor = White,
            border = BorderStroke(1.dp, navBorder),
            tonalElevation = 0.dp,
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                destinations.forEach { destination ->
                    if (destination == "Home") {
                        Spacer(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        val icon = when (destination) {
                            "Coverage" -> Icons.Outlined.Inbox
                            "Activity" -> Icons.Outlined.Description
                            "Spending" -> Icons.Outlined.BarChart
                            else -> Icons.Outlined.RadioButtonChecked
                        }
                        val selected = selectedDestination == destination
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(
                                    selected = selected,
                                    role = Role.Tab,
                                    onClick = { onSelect(destinationRoute(destination)) },
                                )
                                .padding(top = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Icon(icon, contentDescription = null, tint = if (selected) Mint else Muted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(destination, color = if (selected) Mint else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.2f)
                .height(94.dp)
                .selectable(
                    selected = selectedDestination == "Home",
                    role = Role.Tab,
                    onClick = { onSelect("Home") },
                ),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).size(62.dp),
                shape = CircleShape,
                color = Mint,
                contentColor = navSurface,
                border = BorderStroke(3.dp, Color(0xFF071A2B)),
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.House, contentDescription = null, tint = navSurface, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                text = "Home",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp),
                color = Mint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private fun destinationRoute(destination: String): String = when (destination) {
    "Coverage" -> "Coverage"
    "Activity" -> "Activity"
    "Spending" -> "Categories"
    "Plan" -> "Target"
    else -> "Home"
}

@Composable
fun ExpenseMailApp(context: Context, incomingEmail: String?, viewModel: ExpenseViewModel) {
    val data by viewModel.data.collectAsState()
    val backupState by viewModel.backup.collectAsState()
    val spendingTarget by viewModel.spendingTarget.collectAsState()
    val expenses = data.snapshot.expenses
    val syncLedger = data.snapshot.ledger
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(if (!incomingEmail.isNullOrBlank()) "Review" else "Home") }
    var importText by remember { mutableStateOf(incomingEmail.orEmpty()) }
    var senderText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var manualImportExpanded by remember { mutableStateOf(!incomingEmail.isNullOrBlank()) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var gmailConnected by remember { mutableStateOf(viewModel.repository.isGmailConnected()) }
    var gmailStatus by remember { mutableStateOf("") }
    var gmailAuthorizing by remember { mutableStateOf(false) }
    val gmailSyncState by GmailSyncRuntime.state.collectAsState()
    val gmailSyncing = gmailAuthorizing || gmailSyncState.isRunning
    var requestedTargetMessageId by remember { mutableStateOf<String?>(null) }
    var selectedSkipped by remember { mutableStateOf<SyncLedgerEntry?>(null) }
    var skippedAddMode by remember { mutableStateOf(false) }
    var dismissSkippedEntry by remember { mutableStateOf<SyncLedgerEntry?>(null) }
    var skippedActionError by remember { mutableStateOf("") }
    var skippedPreview by remember { mutableStateOf<BankEmail?>(null) }
    var skippedPreviewLoading by remember { mutableStateOf(false) }
    var skippedPreviewError by remember { mutableStateOf("") }
    var activityDateMode by remember { mutableStateOf("All") }
    var activityStartDate by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var activityEndDate by remember { mutableStateOf(LocalDate.now()) }
    var activityCategoryFilter by remember { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("expensemail", Context.MODE_PRIVATE) }
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
    LaunchedEffect(data.warning) {
        data.warning?.let { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(viewModel.exportBackupJson())
                    } ?: error("Could not open the selected file.")
                }
            }
            viewModel.setBackupMessage(result.fold({ "Backup saved. Keep this file somewhere safe." }, { "Could not save backup: ${it.message ?: "unknown error"}" }))
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
            viewModel.setBackupMessage(result.fold({ "CSV exported. CSV files cannot be restored as app data." }, { "Could not export CSV: ${it.message ?: "unknown error"}" }))
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
            result.onSuccess { imported -> viewModel.stageImportedBackup(imported) }
                .onFailure { viewModel.setBackupMessage("Could not read backup: ${it.message ?: "invalid file"}") }
        }
    }
    val gmailScope = remember { listOf(Scope(GmailInboxReader.READ_ONLY_SCOPE)) }
    val cloudBackupScopes = remember {
        listOf(
            Scope("https://www.googleapis.com/auth/userinfo.email"),
            Scope("https://www.googleapis.com/auth/userinfo.profile"),
        )
    }
    val cloudAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!) }
                .onSuccess { authorization ->
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) viewModel.failCloudSignIn("Google did not provide account access. Please try again.")
                    else viewModel.signInToCloudBackup(token)
                }
                .onFailure {
                    viewModel.failCloudSignIn(it.message ?: "Google account authorization failed.")
                }
        } else {
            viewModel.failCloudSignIn("Google account authorization was not completed.")
        }
    }
    fun requestCloudSignIn() {
        if (activity == null) { viewModel.setBackupMessage("Google sign-in is unavailable in this screen."); return }
        viewModel.beginCloudSignIn()
        val request = AuthorizationRequest.builder().setRequestedScopes(cloudBackupScopes).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent == null) {
                        viewModel.failCloudSignIn("Google did not return an account prompt.")
                    } else cloudAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) {
                        viewModel.failCloudSignIn("Google did not provide account access. Please try again.")
                    } else viewModel.signInToCloudBackup(token)
                }
            }
            .addOnFailureListener {
                viewModel.failCloudSignIn(it.message ?: "Google account authorization failed.")
            }
    }
    var requestedSyncMode by remember { mutableStateOf(GmailSyncRequestMode.NORMAL) }
    LaunchedEffect(gmailSyncState.revision) {
        if (gmailSyncState.revision > 0) {
            withContext(Dispatchers.IO) { viewModel.refreshFromDisk() }
            currentAccountKey = prefs.getString("gmail_account_key", null)
            gmailConnected = viewModel.repository.isGmailConnected()
        }
    }
    LaunchedEffect(gmailSyncState.message) {
        if (gmailSyncState.message.isNotBlank()) gmailStatus = gmailSyncState.message
    }
    val scanProgressText = if (gmailSyncing) {
        if (gmailSyncState.batches == 0) "Starting Gmail scan in the background…"
        else if (gmailSyncState.targetTotal > 0) "${gmailSyncState.processed} of ${gmailSyncState.targetTotal} skipped emails rechecked · ${gmailSyncState.added} added to Review"
        else "${gmailSyncState.fetched} emails checked across ${gmailSyncState.batches} ${if (gmailSyncState.batches == 1) "batch" else "batches"} · ${gmailSyncState.added} added to Review"
    } else null
    val scanProgressFraction = if (gmailSyncing && gmailSyncState.targetTotal > 0) gmailSyncState.processed.toFloat() / gmailSyncState.targetTotal else null
    fun persist(updated: List<Expense>) {
        if (gmailSyncing) { gmailStatus = "Wait for Gmail sync to finish before changing transactions."; return }
        runCatching { viewModel.saveExpenses(updated) }
            .onFailure { coroutineScope.launch { snackbarHostState.showSnackbar(it.message ?: "Could not save transactions. Try again.") } }
    }
    fun deleteExpense(id: String) {
        if (gmailSyncing) { gmailStatus = "Wait for Gmail sync to finish before changing transactions."; return }
        val updated = viewModel.snapshot().withDismissedExpense(id, System.currentTimeMillis())
        runCatching { viewModel.commitSnapshot(updated) }
            .onFailure { coroutineScope.launch { snackbarHostState.showSnackbar(it.message ?: "Could not delete this transaction. Try again.") } }
    }

    fun syncWithToken(accessToken: String, mode: GmailSyncRequestMode, targetMessageId: String? = null) {
        gmailConnected = true
        viewModel.repository.setGmailConnected(true)
        gmailAuthorizing = false
        GmailSyncRuntime.publish(GmailSyncUiState(isRunning = true, message = "Starting Gmail scan in the background…"))
        runCatching { GmailSyncForegroundService.start(context, accessToken, gmailStartDate, gmailEndDate, mode, targetMessageId) }
            .onFailure {
                GmailSyncRuntime.publish(GmailSyncUiState(message = "Could not start background Gmail sync. Try again."))
                gmailStatus = "Could not start background Gmail sync. Try again."
            }
    }

    val gmailAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!) }
                .onSuccess { authorization ->
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { gmailAuthorizing = false; gmailStatus = "Google did not provide Gmail access. Please try again." }
                    else syncWithToken(token, requestedSyncMode, requestedTargetMessageId)
                }
                .onFailure { gmailAuthorizing = false; gmailStatus = it.message ?: "Gmail authorization failed." }
        } else {
            gmailAuthorizing = false
            gmailStatus = "Gmail access was not granted."
        }
    }

    fun beginGmailAuthorization() {
        if (activity == null) { gmailAuthorizing = false; gmailStatus = "Gmail authorization is unavailable in this screen."; return }
        gmailAuthorizing = true
        gmailStatus = "Requesting Gmail access…"
        val request = AuthorizationRequest.builder().setRequestedScopes(gmailScope).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent == null) { gmailAuthorizing = false; gmailStatus = "Google did not return an authorization prompt." }
                    else gmailAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { gmailAuthorizing = false; gmailStatus = "Google did not provide Gmail access. Please try again." }
                    else syncWithToken(token, requestedSyncMode, requestedTargetMessageId)
                }
            }
            .addOnFailureListener { gmailAuthorizing = false; gmailStatus = it.message ?: "Gmail authorization failed." }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginGmailAuthorization()
        else {
            // Android still allows a foreground service without drawer-notification permission.
            // Let the user sync; the system may show its foreground notice only in Task Manager.
            gmailStatus = "Notifications are off. Android may hide background sync progress."
            beginGmailAuthorization()
        }
    }

    fun requestGmailSync(mode: GmailSyncRequestMode = GmailSyncRequestMode.NORMAL, targetMessageId: String? = null) {
        if (gmailSyncing) return
        if (activity == null) { gmailStatus = "Gmail authorization is unavailable in this screen."; return }
        requestedSyncMode = mode
        requestedTargetMessageId = targetMessageId
        gmailAuthorizing = true
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginGmailAuthorization()
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
            viewModel.repository.setGmailConnected(false)
            gmailStatus = "Gmail disconnected. Previously imported transactions remain on this device."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Canvas, surface = Surface, onSurface = White)) {
        Scaffold(
            containerColor = Canvas,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                PocketlogBottomBar(tab = tab, onSelect = { tab = it })
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Canvas)) {
                when (tab) {
                    "Home" -> HomeScreen(
                        expenses = expenses,
                        targetAmount = spendingTarget.amount,
                        targetPeriod = spendingTarget.period,
                        syncReport = syncLedger.lastReport,
                        syncing = gmailSyncing,
                        gmailConnected = gmailConnected,
                        skippedCount = currentAccountKey?.let { recheckableCount(syncLedger, it) } ?: 0,
                        onReview = { tab = "Review" },
                        onManualImport = { error = ""; manualImportExpanded = true; tab = "Review" },
                        onOpenActivity = { tab = "Activity" },
                        onOpenMonthlyActivity = { activityDateMode = "Monthly"; tab = "Activity" },
                        onOpenSettings = { tab = "Settings" },
                        onOpenCategories = { tab = "Categories" },
                        onOpenTarget = { tab = "Target" },
                        onOpenRecurring = { tab = "Recurring" },
                        onOpenInsights = { tab = "Insights" },
                        onOpenCoverage = { tab = "Coverage" },
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
                        onApprove = { id ->
                            val target = expenses.firstOrNull { it.id == id }
                            if (target?.hasMalformedGmailMerchant() == true) {
                                coroutineScope.launch { snackbarHostState.showSnackbar("Edit the merchant before confirming this imported row.") }
                            } else persist(expenses.map { if (it.id == id) it.copy(approved = true) else it })
                        },
                        onApproveMany = { ids ->
                            val safeIds = ids - expenses.filter(Expense::hasMalformedGmailMerchant).map { it.id }.toSet()
                            if (safeIds.isNotEmpty()) persist(expenses.map { if (it.id in safeIds && !it.approved) it.copy(approved = true) else it })
                            if (safeIds.size < ids.size) coroutineScope.launch {
                                snackbarHostState.showSnackbar("Rows with unreadable merchant text stayed in Review. Edit them before confirming.")
                            }
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
                        categoryFilter = activityCategoryFilter,
                        onCategoryFilterChange = { activityCategoryFilter = it },
                        onReview = { tab = "Review" },
                    )
                    "Categories" -> CategorySpendingScreen(
                        expenses = expenses,
                        onBack = { tab = "Home" },
                        onOpenCategory = { category, start, end ->
                            activityDateMode = "Custom"
                            activityStartDate = start
                            activityEndDate = end
                            activityCategoryFilter = category
                            tab = "Activity"
                        },
                    )
                    "Target" -> MonthlyTargetScreen(
                        expenses = expenses,
                        savedAmount = spendingTarget.amount,
                        savedPeriod = spendingTarget.period,
                        onSave = viewModel::saveSpendingTarget,
                        onBack = { tab = "Home" },
                    )
                    "Recurring" -> RecurringChargesScreen(expenses = expenses, onBack = { tab = "Home" })
                    "Insights" -> SpendingInsightsScreen(expenses = expenses, onBack = { tab = "Home" }, onOpenCategories = { tab = "Categories" })
                    "Coverage" -> CoverageScreen(
                        report = syncLedger.lastReport,
                        gmailConnected = gmailConnected,
                        syncing = gmailSyncing,
                        progressText = scanProgressText,
                        progress = scanProgressFraction,
                        skippedCount = currentAccountKey?.let { recheckableCount(syncLedger, it) } ?: 0,
                        pendingCount = expenses.count { !it.approved },
                        onBack = { tab = "Home" },
                        onOpenSettings = { tab = "Settings" },
                        onOpenReview = { tab = "Review" },
                        onViewSkipped = { tab = "Skipped" },
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
                        onExportBackup = { viewModel.setBackupMessage(""); backupExportLauncher.launch("pocketlog-backup.json") },
                        onExportCsv = { viewModel.setBackupMessage(""); csvExportLauncher.launch("pocketlog-transactions.csv") },
                        onImportBackup = { viewModel.setBackupMessage(""); backupImportLauncher.launch(arrayOf("application/json", "text/*")) },
                        cloudAccountEmail = backupState.accountEmail,
                        cloudBackupBusy = backupState.busy,
                        onConnectCloudBackup = { viewModel.setBackupMessage(""); requestCloudSignIn() },
                        onUploadCloudBackup = { viewModel.uploadCloudBackup(gmailSyncing) },
                        onRestoreCloudBackup = { viewModel.restoreLatestCloudBackup(gmailSyncing) },
                        onDisconnectCloudBackup = { viewModel.disconnectCloudBackup() },
                        backupMessage = backupState.message,
                        backupActionsEnabled = !gmailSyncing,
                    )
                }
            }
        }
        editing?.let { expense -> EditExpenseDialog(expense, onDismiss = { editing = null }, onSave = { updated ->
            if (!gmailSyncing) { persist(expenses.map { if (it.id == updated.id) updated else it }); editing = null }
            else gmailStatus = "Wait for Gmail sync to finish before changing transactions."
        }) }
        val preview = backupState.preview
        val importedBackup = backupState.pendingBackup
        if (preview != null && importedBackup != null) {
            BackupMergeDialog(
                preview = preview,
                onDismiss = { viewModel.cancelRestore() },
                onApply = { viewModel.applyRestore(gmailSyncing) },
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
                        val original = viewModel.snapshot()
                        val updated = original.withManuallyAddedSkippedEmail(entry, proposed, System.currentTimeMillis())
                        if (updated == original) skippedActionError = "This email has already been handled."
                        else runCatching { viewModel.commitSnapshot(updated) }
                            .onSuccess {
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
                            val original = viewModel.snapshot()
                            val updated = original.withDismissedSkippedEmail(entry, System.currentTimeMillis())
                            if (updated == original) skippedActionError = "This email has already been handled."
                            else runCatching { viewModel.commitSnapshot(updated) }
                                .onSuccess { dismissSkippedEntry = null }
                                .onFailure { skippedActionError = it.message ?: "Could not dismiss this email." }
                        }
                    }) { Text("Dismiss email", color = Orange) }
                },
                dismissButton = { TextButton(onClick = { dismissSkippedEntry = null }) { Text("Cancel") } },
            )
        }
    }
}
