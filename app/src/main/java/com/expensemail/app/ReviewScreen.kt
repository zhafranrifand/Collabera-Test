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
internal fun ReviewScreen(
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
    val approvableIds = pending.filterNot(Expense::hasMalformedGmailMerchant).mapTo(mutableSetOf()) { it.id }
    var page by remember { mutableIntStateOf(0) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmationIds by remember { mutableStateOf<Set<String>?>(null) }
    var confirmingAll by remember { mutableStateOf(false) }
    val activeSelection = selectedIds.intersect(pending.mapTo(mutableSetOf()) { it.id })
    val currentPage = page.coerceIn(0, ((pending.size - 1).coerceAtLeast(0)) / LIST_PAGE_SIZE)
    val pageItems = pending.drop(currentPage * LIST_PAGE_SIZE).take(LIST_PAGE_SIZE)
    val pageIds = pageItems.filterNot(Expense::hasMalformedGmailMerchant).mapTo(mutableSetOf()) { it.id }
    val wholePageSelected = pageIds.isNotEmpty() && activeSelection.containsAll(pageIds)
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Review", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${pending.size} ${if (pending.size == 1) "transaction" else "transactions"} waiting for your review", color = Muted, fontSize = 14.sp)
        val merchantFixCount = pending.count(Expense::hasMalformedGmailMerchant)
        if (merchantFixCount > 0) Text(
            if (merchantFixCount == 1) "1 imported row needs a merchant correction before it can be confirmed."
            else "$merchantFixCount imported rows need a merchant correction before they can be confirmed.",
            color = Orange,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
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
                        }, enabled = actionsEnabled && pageIds.isNotEmpty(), colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted))
                        Text("Select page (${pageItems.size})", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (approvableIds.isNotEmpty()) TextButton(onClick = { selectedIds = approvableIds }, enabled = actionsEnabled) {
                            Text("Select all ${approvableIds.size}", color = Mint, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${activeSelection.size} selected", color = Muted, fontSize = 12.sp)
                        if (approvableIds.isNotEmpty()) TextButton(onClick = { confirmingAll = true; confirmationIds = approvableIds }, enabled = actionsEnabled) {
                            Text("Approve all ${approvableIds.size}", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
internal fun PendingCard(
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
                    Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = actionsEnabled && !expense.hasMalformedGmailMerchant(), colors = CheckboxDefaults.colors(checkedColor = Mint, uncheckedColor = Muted))
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
        if (expense.hasMalformedGmailMerchant()) {
            Text(
                "Merchant text looks like email formatting or footer content. Edit it before confirming, or delete the row if it is not a transaction.",
                color = Orange,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 48.dp, end = 4.dp),
            )
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
            Button(onClick = { onApprove(expense.id) }, enabled = actionsEnabled && !expense.hasMalformedGmailMerchant(), modifier = Modifier.weight(1.2f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Confirm", fontWeight = FontWeight.Bold)
            }
        }
        }
    }
}
