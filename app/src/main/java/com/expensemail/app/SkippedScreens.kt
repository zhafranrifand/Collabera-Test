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
internal fun SkippedAlertsScreen(
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
internal fun SkippedEmailDialog(
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
    suggestCategory: (Expense) -> Expense,
) {
    var merchant by remember(entry.messageId) { mutableStateOf("") }
    var amount by remember(entry.messageId) { mutableStateOf("") }
    var bank by remember(entry.messageId) { mutableStateOf(bankNameForSender(entry.sender)) }
    var category by remember(entry.messageId) { mutableStateOf("Other") }
    var categoryEdited by remember(entry.messageId) { mutableStateOf(false) }
    var categoryRuleMerchant by remember(entry.messageId) { mutableStateOf<String?>(null) }
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
                val suggested = suggestCategory(parsed)
                category = suggested.category
                categoryRuleMerchant = suggested.categoryRuleMerchant
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
                    OutlinedTextField(merchant, {
                        merchant = it
                        if (!categoryEdited && recordType == "Expense") {
                            val suggested = suggestCategory(Expense(merchant = it, amount = 0, date = "", category = "Other"))
                            category = suggested.category
                            categoryRuleMerchant = suggested.categoryRuleMerchant
                        } else categoryRuleMerchant = null
                    }, modifier = Modifier.fillMaxWidth(), label = { Text("Merchant or description") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("Amount (IDR)") }, singleLine = true)
                    DateChoice("Transaction date", transactionDate, { transactionDate = it }, Modifier.fillMaxWidth())
                    OutlinedTextField(bank, { bank = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Bank") }, singleLine = true)
                    Text("Transaction type", color = Muted, fontSize = 12.sp)
                    Box {
                        OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(recordType, modifier = Modifier.weight(1f)); Text("Change") }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            listOf("Expense", "Transfer", "Income").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = {
                                recordType = value
                                typeMenu = false
                                categoryRuleMerchant = null
                                if (value == "Expense") {
                                    category = category.takeIf { it in EXPENSE_CATEGORIES } ?: "Other"
                                    if (!categoryEdited) {
                                        val suggested = suggestCategory(Expense(merchant = merchant, amount = 0, date = "", category = category))
                                        category = suggested.category
                                        categoryRuleMerchant = suggested.categoryRuleMerchant
                                    }
                                }
                            }) }
                        }
                    }
                    if (recordType == "Expense") {
                        Text("Category", color = Muted, fontSize = 12.sp)
                        Box {
                            OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(category, modifier = Modifier.weight(1f)); Text("Change") }
                            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                                EXPENSE_CATEGORIES.forEach { value ->
                                    DropdownMenuItem(text = { Text(value) }, onClick = { category = value; categoryEdited = true; categoryRuleMerchant = null; categoryMenu = false })
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
                            categoryRuleMerchant = categoryRuleMerchant.takeIf { recordType == "Expense" },
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

internal fun bankNameForSender(sender: String?): String = when {
    sender.orEmpty().contains("bca", ignoreCase = true) -> "BCA"
    sender.orEmpty().contains("jago", ignoreCase = true) -> "Jago"
    sender.orEmpty().contains("mandiri", ignoreCase = true) -> "Mandiri"
    sender.orEmpty().contains("bni", ignoreCase = true) -> "BNI"
    sender.orEmpty().contains("bri", ignoreCase = true) -> "BRI"
    else -> "Unknown bank"
}

internal fun skippedReasonLabel(entry: SyncLedgerEntry): String = when (entry.skipReason) {
    "No supported transaction found" -> "This email did not match a supported receipt format"
    else -> entry.skipReason ?: "No transaction was added"
}

@Composable
internal fun ListPager(total: Int, page: Int, onPageChange: (Int) -> Unit) {
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
