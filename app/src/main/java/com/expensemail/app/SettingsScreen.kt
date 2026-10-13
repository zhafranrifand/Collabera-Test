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
import androidx.compose.runtime.collectAsState
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
internal fun SettingsScreen(
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
    cloudAccountEmail: String?,
    cloudBackupBusy: Boolean,
    onConnectCloudBackup: () -> Unit,
    onUploadCloudBackup: () -> Unit,
    onRestoreCloudBackup: () -> Unit,
    onDisconnectCloudBackup: () -> Unit,
    backupMessage: String,
    backupActionsEnabled: Boolean,
    merchantRulesCount: Int,
    onManageMerchantRules: () -> Unit,
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
            Text("Import supported BCA, Livin’ by Mandiri, Bank Jago, UOB, wondr by BNI, and OCBC transaction alerts. New items stay in Review until you confirm them.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
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
            Text("Merchant categories", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Remember a category when editing a transaction. Future expenses from that exact merchant use your choice and still need review.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = onManageMerchantRules, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                Text("Manage remembered merchants ($merchantRulesCount)", fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your data", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Backups include transactions, Gmail scan history, and remembered merchant categories, but never message bodies or Google access tokens. Restore merges records and keeps this device’s existing data.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Text("Cloud backup", color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                if (cloudAccountEmail.isNullOrBlank()) "Connect a Google account for backups. Gmail access is separate and is only used for email scanning and review."
                else "Connected as $cloudAccountEmail. Gmail access is managed separately. Backups are encrypted on this device; the service can restore one when you ask.",
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp,
            )
            if (cloudAccountEmail.isNullOrBlank()) {
                OutlinedButton(onClick = onConnectCloudBackup, enabled = backupActionsEnabled && !cloudBackupBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                    Text(if (cloudBackupBusy) "Connecting…" else "Connect cloud backup", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(onClick = onUploadCloudBackup, enabled = backupActionsEnabled && !cloudBackupBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                    Text(if (cloudBackupBusy) "Working…" else "Back up now", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onRestoreCloudBackup, enabled = backupActionsEnabled && !cloudBackupBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                    Text("Restore latest cloud backup", fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDisconnectCloudBackup, enabled = !cloudBackupBusy, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Disconnect cloud backup", color = Orange)
                }
            }
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
internal fun BackupMergeDialog(
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
                Text("${preview.merchantRulesAdded} merchant rules will be added", color = White)
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
internal fun DateChoice(label: String, date: LocalDate, onDateSelected: (LocalDate) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
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
internal fun EditExpenseDialog(expense: Expense, onDismiss: () -> Unit, onSave: (Expense, Boolean) -> Unit) {
    var merchant by remember(expense.id) { mutableStateOf(expense.merchant) }
    var amount by remember(expense.id) { mutableStateOf(expense.amount.toString()) }
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var recordType by remember(expense.id) { mutableStateOf(expense.recordType) }
    var categoryMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var rememberCategory by remember(expense.id) { mutableStateOf(false) }
    val categories = EXPENSE_CATEGORIES.filter { it != "Transfer" }
    val canRemember = recordType == "Expense" && MerchantCategoryRules.canRemember(merchant)
    val validCategory = category.takeIf { it in categories } ?: "Other"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Edit transaction", color = White) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(merchant, { merchant = it; rememberCategory = false }, label = { Text("Merchant") }, singleLine = true)
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Amount (IDR)") }, singleLine = true)
                Text("Transaction type", color = Muted, fontSize = 12.sp)
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(recordType, modifier = Modifier.weight(1f)); Text("Change") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        listOf("Expense", "Transfer", "Income").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { recordType = value; if (value != "Expense") rememberCategory = false; typeMenu = false }) }
                    }
                }
                if (recordType == "Expense") {
                    Text("Category", color = Muted, fontSize = 12.sp)
                    Box {
                        OutlinedButton(onClick = { categoryMenu = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(validCategory, modifier = Modifier.weight(1f)); Text("Change") }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            categories.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { category = value; categoryMenu = false }) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberCategory && canRemember, onCheckedChange = { rememberCategory = it }, enabled = canRemember, colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted))
                        Column(Modifier.weight(1f)) {
                            Text("Remember category for this merchant", color = White, fontSize = 13.sp)
                            Text(if (canRemember) "Future imports only. Review still required." else "Enter a recognizable merchant name first.", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = merchant.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0L, onClick = {
            amount.toLongOrNull()?.let { onSave(expense.copy(merchant = merchant.trim(), amount = it, category = if (recordType == "Expense") validCategory else recordType, recordType = recordType), rememberCategory && canRemember) }
        }) { Text("Save", color = Mint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
