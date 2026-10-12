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
internal fun ExpenseRow(expense: Expense) {
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
internal fun EmptyState(
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
internal fun GmailScanProgress(progressText: String, progressFraction: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (progressFraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Mint, trackColor = Surface2)
        else LinearProgressIndicator(progress = { progressFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Mint, trackColor = Surface2)
        Text(progressText, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
internal fun SyncReportCard(
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
