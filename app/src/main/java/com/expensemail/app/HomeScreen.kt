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
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Info
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
internal fun HomeScreen(
    expenses: List<Expense>,
    targetAmount: Long,
    targetPeriod: String,
    syncReport: SyncReport?,
    syncing: Boolean,
    gmailConnected: Boolean,
    skippedCount: Int,
    onReview: () -> Unit,
    onManualImport: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenMonthlyActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenCoverage: () -> Unit,
) {
    val today = LocalDate.now()
    val approved = expenses.filter { it.approved }
    val confirmedExpenses = approved.filter { it.recordType == "Expense" && it.amount > 0 }
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
    val categoryTotals = currentMonthExpenses.groupBy { it.category.ifBlank { "Other" } }
        .map { (category, rows) -> Triple(category, rows.sumOf { it.amount }, rows.size) }
        .sortedByDescending { it.second }.take(3)
    val categorySum = currentMonthExpenses.groupBy { it.category.ifBlank { "Other" } }.values.sumOf { rows -> rows.sumOf { it.amount } }.coerceAtLeast(1L)
    val currentWeekStart = today.minusDays(6)
    val previousWeekStart = today.minusDays(13)
    val thisWeekTotal = confirmedExpenses.filter { expenseLocalDate(it)?.let { date -> !date.isBefore(currentWeekStart) && !date.isAfter(today) } == true }.sumOf { it.amount }
    val lastWeekTotal = confirmedExpenses.filter { expenseLocalDate(it)?.let { date -> !date.isBefore(previousWeekStart) && date.isBefore(currentWeekStart) } == true }.sumOf { it.amount }
    val recurringCount = remember(confirmedExpenses) { findRecurringCandidates(confirmedExpenses).size }
    val targetStart = if (targetPeriod.equals("Weekly", ignoreCase = true)) today.minusDays((today.dayOfWeek.value - 1).toLong()) else today.withDayOfMonth(1)
    val targetSpent = confirmedExpenses.filter { expenseLocalDate(it)?.let { date -> !date.isBefore(targetStart) && !date.isAfter(today) } == true }.sumOf { it.amount }
    val scanDate = syncReport?.let { runCatching { LocalDate.parse(it.endDate) }.getOrNull() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("POCKETLOG", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("Your money, clearly.", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(today.format(DateTimeFormatter.ofPattern("MMMM 'so far'", Locale.ENGLISH)).uppercase(Locale.ENGLISH), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(today.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)), color = Muted, fontSize = 11.sp)
                }
                Text(rupiah(monthTotal), color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("confirmed expenses · ${currentMonthExpenses.size} ${if (currentMonthExpenses.size == 1) "record" else "records"}", color = Muted, fontSize = 12.sp)
                val comparison = if (previousPeriodTotal > 0) ((monthTotal - previousPeriodTotal).toDouble() / previousPeriodTotal * 100).roundToInt() else null
                if (comparison != null && kotlin.math.abs(comparison) <= 500) Text("${kotlin.math.abs(comparison)}% ${if (comparison > 0) "more" else if (comparison < 0) "less" else "about the same"} than the same dates last month", color = if (comparison > 0) Orange else Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                else if (comparison != null) Text("Much higher than the same dates last month · check recent transactions", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                else Text("Compared through ${today.dayOfMonth} ${today.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))}", color = Muted, fontSize = 11.sp)
                Text("Based on confirmed records", color = Muted, fontSize = 10.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(15.dp)).clickable(onClick = onOpenCoverage).padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).background(Surface2, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mail, null, tint = Mint, modifier = Modifier.size(18.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (syncing) "Gmail scan in progress" else if (scanDate != null) "Gmail checked through ${scanDate.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}" else if (gmailConnected) "Gmail connected · no scan yet" else "Check your Gmail coverage", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when {
                        syncing -> "View scan progress"
                        skippedCount > 0 -> "$skippedCount emails may need your attention"
                        syncReport != null -> "${syncReport.checked} emails checked · ${syncReport.added} added"
                        gmailConnected -> "Start a scan to find bank alerts"
                        else -> "Connect Gmail to find bank alerts"
                    }, color = if (skippedCount > 0) Orange else Muted, fontSize = 10.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        if (pending > 0) item {
            DashboardMetric(pending.toString(), "transactions waiting for review", Mint, Icons.Default.Inbox, onReview, Modifier.fillMaxWidth())
        }
        item {
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Where it went", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenCategories, contentPadding = PaddingValues(horizontal = 3.dp)) { Text("See all  ›", color = Mint, fontSize = 12.sp) }
            }
        }
        if (categoryTotals.isEmpty()) item {
            EmptyState("No spending to break down yet", "Confirmed expenses will be grouped by category here.", "Open Review", onReview)
        } else item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                categoryTotals.forEach { (category, amount, count) ->
                    val share = amount.toFloat() / categorySum
                    val color = when (category) {
                        "Food & drink" -> Mint
                        "Groceries" -> Color(0xFF8DC9FF)
                        "Transport" -> Color(0xFF8CAEFF)
                        "Shopping" -> Color(0xFFC09AFF)
                        "Bills & utilities" -> Color(0xFFFFC37D)
                        else -> Color(0xFF8BA0B8)
                    }
                    Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCategories).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(30.dp).background(color.copy(alpha = .14f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.BarChart, null, tint = color, modifier = Modifier.size(16.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(category, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(rupiah(amount), color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = color, trackColor = Surface2)
                        }
                        Text("${(share * 100).roundToInt()}%", color = Muted, fontSize = 10.sp, modifier = Modifier.widthIn(min = 25.dp))
                    }
                }
            }
        }
        item {
            if (targetAmount > 0L) {
                Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).clickable(onClick = onOpenTarget).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.Savings, null, tint = Mint, modifier = Modifier.size(19.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${targetPeriod.replaceFirstChar { it.uppercase() }} target", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("${rupiah(targetSpent)} of ${rupiah(targetAmount)}", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                    LinearProgressIndicator(progress = { (targetSpent.toFloat() / targetAmount).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = if (targetSpent > targetAmount) Orange else Mint, trackColor = Surface2)
                    Text(if (targetSpent > targetAmount) "${rupiah(targetSpent - targetAmount)} over your guide" else "${rupiah(targetAmount - targetSpent)} left this period", color = if (targetSpent > targetAmount) Orange else Muted, fontSize = 11.sp)
                }
            } else {
                FeatureShortcut("Set a monthly target", "Choose a personal spending guide", { Icon(Icons.Default.Savings, null, tint = Mint, modifier = Modifier.size(18.dp)) }, onOpenTarget)
            }
        }
        if (thisWeekTotal > 0 && lastWeekTotal > 0) item {
            val weeklyChange = ((thisWeekTotal - lastWeekTotal).toDouble() / lastWeekTotal * 100).roundToInt()
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).clickable(onClick = onOpenInsights).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Worth a look", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Insights  ›", color = Mint, fontSize = 11.sp)
                }
                Text(if (kotlin.math.abs(weeklyChange) > 500) "Spending is far ${if (weeklyChange > 0) "above" else "below"} the prior week" else "${kotlin.math.abs(weeklyChange)}% ${if (weeklyChange > 0) "more" else if (weeklyChange < 0) "less" else "about the same"} spent this week", color = if (weeklyChange > 0) Orange else Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${rupiah(thisWeekTotal)} this week vs ${rupiah(lastWeekTotal)} the week before${if (kotlin.math.abs(weeklyChange) > 500) " · check recent transactions" else ""}", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            FeatureShortcut(
                "Recurring charges",
                if (recurringCount > 0) "$recurringCount possible repeat ${if (recurringCount == 1) "pattern" else "patterns"} to check" else "Check for repeated payments in your history",
                { Icon(Icons.Default.Refresh, null, tint = Mint, modifier = Modifier.size(18.dp)) },
                onOpenRecurring,
            )
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
        item { Text("Only confirmed transactions are included. Missing or unreviewed email alerts can make these totals incomplete.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 6.dp)) }
    }
}

@Composable
internal fun DashboardSpendingTrend(months: List<Pair<YearMonth, Long>>) {
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
internal fun GmailScanCard(
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
internal fun DashboardMetric(value: String, label: String, accent: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
internal fun DashboardScanStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
internal fun DashboardRecentRow(expense: Expense, onClick: () -> Unit) {
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
