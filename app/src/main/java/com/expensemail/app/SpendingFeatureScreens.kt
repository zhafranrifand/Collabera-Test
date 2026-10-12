package com.expensemail.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class CategoryTotal(val name: String, val amount: Long, val count: Int)

private fun confirmedExpenses(expenses: List<Expense>): List<Expense> =
    expenses.filter { it.approved && it.recordType == "Expense" && it.amount > 0 }

private fun categoryTotals(expenses: List<Expense>): List<CategoryTotal> = expenses
    .filter { it.category != "Transfer" }
    .groupBy { it.category.ifBlank { "Other" } }
    .map { (name, entries) -> CategoryTotal(name, entries.sumOf { it.amount }, entries.size) }
    .sortedByDescending { it.amount }

@Composable
internal fun FeatureHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Mint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun CategorySpendingScreen(
    expenses: List<Expense>,
    onBack: () -> Unit,
    onOpenCategory: (String, LocalDate, LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val monthEnd = if (month == YearMonth.from(today)) today else month.atEndOfMonth()
    val rows = categoryTotals(confirmedExpenses(expenses).filter {
        expenseLocalDate(it)?.let { date -> YearMonth.from(date) == month && !date.isAfter(today) } == true
    })
    val total = rows.sumOf { it.amount }
    val max = rows.maxOfOrNull { it.amount }?.coerceAtLeast(1) ?: 1
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item { FeatureHeader("Category spending", "See where confirmed expenses went.", onBack) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous month", tint = Mint) }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), color = White, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { if (month < YearMonth.from(today)) month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next month", tint = if (month < YearMonth.from(today)) Mint else Muted)
                }
            }
        }
        item {
            FeatureCard {
                Text("Confirmed expenses", color = Muted, fontSize = 12.sp)
                Text(rupiah(total), color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${rows.sumOf { it.count }} transactions · transfers excluded", color = Muted, fontSize = 12.sp)
            }
        }
        rows.firstOrNull { it.name == "Other" }?.takeIf { total > 0 && it.amount.toDouble() / total >= 0.35 }?.let { other ->
            item {
                FeatureCard(modifier = Modifier.clickable { onOpenCategory("Other", month.atDay(1), monthEnd) }) {
                    Text("Many records need a category check", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("${other.count} expenses (${(other.amount.toDouble() / total * 100).roundToInt()}%) are grouped under Other. Open them from Activity to correct any labels.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        if (rows.isEmpty()) item {
            EmptyState("No confirmed expenses for this month", "Confirm a transaction in Review and it will appear here. Unconfirmed or skipped emails are not included.")
        } else {
            item { Text("By category", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            items(rows, key = { it.name }) { row ->
                val share = if (total > 0) row.amount.toFloat() / total else 0f
                val expanded = expandedCategory == row.name
                val merchants = confirmedExpenses(expenses).filter { expense ->
                    expense.category == row.name && expenseLocalDate(expense)?.let { YearMonth.from(it) == month } == true && isReadableMerchant(expense.merchant)
                }.groupBy { it.merchant.trim() }.values
                    .map { entries -> entries.first().merchant.trim() to entries.sumOf { it.amount } }
                    .sortedByDescending { it.second }.take(3)
                FeatureCard {
                    Row(Modifier.fillMaxWidth().clickable { expandedCategory = if (expanded) null else row.name }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).background(categoryColor(row.name), CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${row.count} ${if (row.count == 1) "expense" else "expenses"} · ${(share * 100).roundToInt()}%", color = Muted, fontSize = 11.sp)
                        }
                        Text(rupiah(row.amount), color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = categoryColor(row.name), trackColor = Surface2)
                    if (expanded) {
                        Spacer(Modifier.height(5.dp))
                        Text("Matching merchants", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        if (merchants.isEmpty()) Text("Merchant names need review for these records.", color = Muted, fontSize = 11.sp)
                        else merchants.forEach { (merchant, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(merchant, color = White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(rupiah(amount), color = Muted, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = { onOpenCategory(row.name, month.atDay(1), monthEnd) }, contentPadding = PaddingValues(0.dp)) {
                            Text("View transactions  ›", color = Mint, fontSize = 12.sp)
                        }
                    }
                }
            }
            item { Text("Tap a category to view its transactions.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 3.dp)) }
        }
        item {
            FeatureNote("Category labels are estimates from the merchant and email details. Review or edit a transaction if a label looks wrong.")
        }
    }
}

private fun isReadableMerchant(value: String): Boolean {
    val text = value.trim()
    return text.length in 2..36 && text.any(Char::isLetter) &&
        !text.contains("max-width", ignoreCase = true) &&
        !text.contains("font-size", ignoreCase = true) &&
        !text.contains("source of fund", ignoreCase = true) &&
        !text.contains("transaction date", ignoreCase = true)
}

@Composable
internal fun MonthlyTargetScreen(
    expenses: List<Expense>,
    savedAmount: Long,
    savedPeriod: String,
    onSave: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    var amountText by remember(savedAmount) { mutableStateOf(if (savedAmount > 0) savedAmount.toString() else "") }
    var period by remember(savedPeriod) { mutableStateOf(savedPeriod) }
    var error by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val approved = confirmedExpenses(expenses)
    val start = if (period == "Weekly") today.with(DayOfWeek.MONDAY) else today.withDayOfMonth(1)
    val spent = approved.filter { expenseLocalDate(it)?.let { date -> !date.isBefore(start) && !date.isAfter(today) } == true }.sumOf { it.amount }
    val amount = amountText.filter(Char::isDigit).toLongOrNull() ?: 0L
    val progress = if (amount > 0) (spent.toFloat() / amount).coerceIn(0f, 1f) else 0f
    LazyColumn(Modifier.fillMaxSize().background(Canvas), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { FeatureHeader("Spending target", "A personal guide for your spending—not a bank limit.", onBack) }
        item {
            FeatureCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Savings, null, tint = Mint)
                    Column {
                        Text("Your spending limit", color = White, fontWeight = FontWeight.SemiBold)
                        Text("Saved on this device", color = Muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("TARGET AMOUNT", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input -> amountText = input.filter(Char::isDigit).take(12); error = "" },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    prefix = { Text("Rp ", color = Muted, fontWeight = FontWeight.SemiBold) },
                    placeholder = { Text("e.g. 6000000", color = Muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Surface2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint),
                )
                Spacer(Modifier.height(12.dp))
                Text("RESET PERIOD", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
                    listOf("Weekly", "Monthly").forEach { option ->
                        FilterChip(selected = period == option, onClick = { period = option }, label = { Text(option) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White))
                    }
                }
                if (error.isNotBlank()) Text(error, color = if (error == "Target saved.") Mint else Orange, fontSize = 12.sp)
                Button(onClick = {
                    if (amount <= 0) error = "Enter a target greater than Rp0."
                    else { onSave(amount, period); error = "Target saved." }
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) { Text("Save target", fontWeight = FontWeight.Bold) }
                if (savedAmount > 0) TextButton(onClick = { amountText = ""; onSave(0, period) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Turn target off", color = Muted) }
            }
        }
        if (savedAmount > 0) item {
            FeatureCard {
                Text("${if (period == "Weekly") "This week" else "This month"}", color = Muted, fontSize = 12.sp)
                Text("${rupiah(spent)} of ${rupiah(savedAmount)}", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(7.dp), color = if (spent > savedAmount) Orange else Mint, trackColor = Surface2)
                val remaining = (savedAmount - spent).coerceAtLeast(0)
                Text(if (spent > savedAmount) "${rupiah(spent - savedAmount)} over target" else "${rupiah(remaining)} remaining", color = if (spent > savedAmount) Orange else Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val periodDays = if (period == "Weekly") 7 else YearMonth.from(today).lengthOfMonth()
                val daysElapsed = (ChronoUnit.DAYS.between(start, today) + 1).coerceAtLeast(1)
                val projected = (spent.toDouble() / daysElapsed * periodDays).toLong()
                Text("At this pace: about ${rupiah(projected)}", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                Text("Estimate uses confirmed records so far; your ${period.lowercase()} may vary.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        item { FeatureNote("Estimates use confirmed expenses only. Pending review and skipped email alerts are excluded, so actual spending may be higher.") }
    }
}

@Composable
internal fun RecurringChargesScreen(expenses: List<Expense>, onBack: () -> Unit) {
    val approved = confirmedExpenses(expenses)
    val candidates = remember(approved) { findRecurringCandidates(approved) }
    LazyColumn(Modifier.fillMaxSize().background(Canvas), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FeatureHeader("Recurring charges", "Possible repeated payments found in confirmed transactions.", onBack) }
        item { FeatureNote("These are suggestions based on merchant, timing, and amount patterns. Pocketlog does not know whether a payment is a subscription.") }
        if (candidates.isEmpty()) item {
            EmptyState("No recurring patterns found yet", "A pattern needs at least three similar confirmed payments at regular intervals. More history may reveal one.")
        } else items(candidates, key = { it.merchant }) { candidate ->
            FeatureCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(38.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Refresh, null, tint = Mint) }
                    Column(Modifier.weight(1f)) {
                        Text(candidate.merchant, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Possible ${candidate.frequency.lowercase()} · ${candidate.count} payments", color = Mint, fontSize = 11.sp)
                    }
                    Text("~${rupiah(candidate.averageAmount)}", color = White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Text("Last seen ${candidate.lastDate}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                Text("Average interval about ${candidate.averageDays} days. Verify this is a recurring charge.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

internal data class RecurringCandidate(val merchant: String, val count: Int, val averageAmount: Long, val averageDays: Long, val lastDate: String, val frequency: String)

internal fun findRecurringCandidates(expenses: List<Expense>): List<RecurringCandidate> = expenses
    .filter { it.merchant.isNotBlank() }
    .groupBy { it.merchant.trim().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() }
    .values.mapNotNull { rows ->
        val dated = rows.mapNotNull { row -> expenseLocalDate(row)?.let { it to row } }.sortedBy { it.first }
        if (dated.size < 3) return@mapNotNull null
        val lastThree = dated.takeLast(4)
        val gaps = lastThree.zipWithNext().map { (a, b) -> ChronoUnit.DAYS.between(a.first, b.first) }
        val avgGap = gaps.average().roundToInt().toLong()
        val frequency = when {
            avgGap in 6..9 -> "Weekly"
            avgGap in 25..35 -> "Monthly"
            else -> return@mapNotNull null
        }
        if (gaps.any { if (frequency == "Weekly") it !in 4..12 else it !in 18..42 }) return@mapNotNull null
        val amounts = lastThree.map { it.second.amount }
        val average = amounts.average()
        if (average <= 0 || amounts.any { kotlin.math.abs(it - average) / average > 0.25 }) return@mapNotNull null
        val latest = lastThree.last()
        RecurringCandidate(latest.second.merchant, lastThree.size, average.roundToInt().toLong(), avgGap, latest.first.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)), frequency)
    }
    .distinctBy { it.merchant.lowercase(Locale.ROOT) }
    .sortedByDescending { it.averageAmount }

@Composable
internal fun SpendingInsightsScreen(expenses: List<Expense>, onBack: () -> Unit, onOpenCategories: () -> Unit) {
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val priorMonth = currentMonth.minusMonths(1)
    val currentExpenses = confirmedExpenses(expenses)
    val monthToDate = currentExpenses.filter { expenseLocalDate(it)?.let { d -> YearMonth.from(d) == currentMonth && !d.isAfter(today) } == true }
    val previousComparableEnd = priorMonth.atDay(today.dayOfMonth.coerceAtMost(priorMonth.lengthOfMonth()))
    val previousComparable = currentExpenses.filter { expenseLocalDate(it)?.let { d -> YearMonth.from(d) == priorMonth && !d.isAfter(previousComparableEnd) } == true }
    val currentTotal = monthToDate.sumOf { it.amount }
    val priorTotal = previousComparable.sumOf { it.amount }
    val deltaPercent = if (priorTotal > 0) ((currentTotal - priorTotal).toDouble() / priorTotal * 100).roundToInt() else null
    val categories = categoryTotals(monthToDate)
    val top = categories.firstOrNull()
    val weekStart = today.minusDays(6)
    val priorWeekStart = today.minusDays(13)
    val thisWeek = currentExpenses.filter { expenseLocalDate(it)?.let { d -> !d.isBefore(weekStart) && !d.isAfter(today) } == true }.sumOf { it.amount }
    val lastWeek = currentExpenses.filter { expenseLocalDate(it)?.let { d -> !d.isBefore(priorWeekStart) && d.isBefore(weekStart) } == true }.sumOf { it.amount }
    LazyColumn(Modifier.fillMaxSize().background(Canvas), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 26.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { FeatureHeader("Spending insights", "Simple comparisons from confirmed transactions.", onBack) }
        item { FeatureNote("Insights describe recorded expenses only. Missing or unreviewed bank alerts can make totals incomplete.") }
        if (monthToDate.isEmpty()) item { EmptyState("Not enough spending data yet", "Confirm transactions to see spending changes and category patterns.") }
        else {
            item {
                FeatureCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = Mint)
                        Text("Month to date", color = Muted, fontSize = 12.sp)
                    }
                    Text(rupiah(currentTotal), color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text(if (deltaPercent == null) "No previous-month comparison is available yet." else {
                        val direction = if (deltaPercent > 0) "more" else if (deltaPercent < 0) "less" else "about the same as"
                        "${kotlin.math.abs(deltaPercent)}% $direction than the same dates last month (${rupiah(priorTotal)})."
                    }, color = if (deltaPercent != null && deltaPercent > 0) Orange else Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            item {
                FeatureCard {
                    Text("Last 7 days", color = Muted, fontSize = 12.sp)
                    Text(rupiah(thisWeek), color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (lastWeek > 0) {
                        val change = ((thisWeek - lastWeek).toDouble() / lastWeek * 100).roundToInt()
                        Text("${kotlin.math.abs(change)}% ${if (change > 0) "more" else if (change < 0) "less" else "change"} than the 7 days before (${rupiah(lastWeek)}).", color = if (change > 0) Orange else Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    } else Text("No earlier-week spending to compare.", color = Muted, fontSize = 12.sp)
                }
            }
            if (top != null) item {
                FeatureCard(modifier = Modifier.clickable(onClick = onOpenCategories)) {
                    Text("Largest category this month", color = Muted, fontSize = 12.sp)
                    Text(top.name, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${rupiah(top.amount)} · ${top.count} ${if (top.count == 1) "expense" else "expenses"}", color = Mint, fontSize = 13.sp)
                    Text("View category breakdown  ›", color = Mint, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
internal fun CoverageScreen(
    report: SyncReport?,
    gmailConnected: Boolean,
    syncing: Boolean,
    progressText: String?,
    progress: Float?,
    skippedCount: Int,
    pendingCount: Int,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReview: () -> Unit,
    onViewSkipped: () -> Unit,
) {
    var needsAttention by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().background(Canvas), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 26.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { FeatureHeader("Tracking coverage", "See what Gmail checked and what needs attention.", onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !needsAttention, onClick = { needsAttention = false }, label = { Text("Overview") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White))
                FilterChip(selected = needsAttention, onClick = { needsAttention = true }, label = { Text("Needs attention${if (pendingCount + skippedCount > 0) " (${pendingCount + skippedCount})" else ""}") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink, labelColor = White))
            }
        }
        if (!needsAttention) {
        if (syncing) item {
            FeatureCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Mail, null, tint = Mint)
                    Text("Gmail scan in progress", color = White, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), color = Mint, trackColor = Surface2)
                Text(progressText ?: "Checking bank alerts…", color = Muted, fontSize = 12.sp)
            }
        }
        if (report == null) item {
            EmptyState(
                title = if (syncing) "Gmail scan is running" else if (gmailConnected) "Gmail connected, no scan yet" else "Connect Gmail to start tracking",
                detail = if (syncing) "Your scan progress appears above. You can leave this screen while it runs." else if (gmailConnected) "Your account is connected. Start a scan in Settings to find supported bank alerts." else "Scan your inbox to find supported bank alerts and see whether any need review.",
                action = if (syncing) null else if (gmailConnected) "Open Gmail settings" else "Connect Gmail",
                onAction = if (syncing) null else onOpenSettings,
            )
        } else {
            item {
                FeatureCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Mail, null, tint = Mint)
                        Text("Last scan", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${report.accountKey} · ${java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(java.util.Date(report.finishedAtMillis))}", color = Muted, fontSize = 11.sp)
                    Text("${report.startDate} – ${report.endDate}", color = Muted, fontSize = 12.sp)
                    HorizontalDivider(color = Surface2, modifier = Modifier.padding(vertical = 3.dp))
                    CoverageMetric("Fetched", report.checked.toString())
                    CoverageMetric("Added to review", report.added.toString())
                    CoverageMetric("Skipped", report.skipped.toString(), if (report.skipped > 0) Orange else Mint)
                    if (report.oldestFetchedAtMillis != null) Text("Oldest email opened: ${java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale("en", "ID")).format(java.util.Date(report.oldestFetchedAtMillis))}", color = Muted, fontSize = 11.sp)
                    report.error?.takeIf(String::isNotBlank)?.let { Text(it, color = Orange, fontSize = 12.sp) }
                }
            }
            item { OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) { Text("Gmail scan settings") } }
        }
        } else {
        item { Text("Needs attention", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (pendingCount > 0) item {
            FeatureCard(modifier = Modifier.clickable(onClick = onOpenReview)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = Mint)
                    Column(Modifier.weight(1f)) {
                        Text("$pendingCount transactions to review", color = White, fontWeight = FontWeight.SemiBold)
                        Text("Confirm, edit, or remove alerts before they count as spending.", color = Muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Mint)
                }
            }
        }
        if (skippedCount > 0) item {
            FeatureCard(modifier = Modifier.clickable(onClick = onViewSkipped)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.Info, null, tint = Orange)
                        Column(Modifier.weight(1f)) {
                            Text("$skippedCount skipped emails", color = White, fontWeight = FontWeight.SemiBold)
                            Text("Inspect, add, recheck, or dismiss each email.", color = Muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Mint)
                    }
            }
        }
        if (pendingCount == 0 && skippedCount == 0) item {
            EmptyState("Nothing needs attention", "New transactions and skipped emails will appear here after a Gmail scan.", "Open Review", onOpenReview)
        }
        }
        item { FeatureNote("Email matching can miss alerts. These counts describe scan activity and do not guarantee that every bank transaction is represented.") }
    }
}

@Composable
private fun CoverageMetric(label: String, value: String, color: Color = White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun FeatureShortcut(title: String, detail: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(34.dp).background(Surface2, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(detail, color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun FeatureCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Surface, RoundedCornerShape(17.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
}

@Composable
private fun FeatureNote(text: String) {
    Row(Modifier.fillMaxWidth().background(Surface.copy(alpha = .7f), RoundedCornerShape(13.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, null, tint = Muted, modifier = Modifier.size(16.dp))
        Text(text, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

private fun categoryColor(category: String): Color = when (category) {
    "Food & drink" -> Mint
    "Groceries" -> Color(0xFF8DC9FF)
    "Transport" -> Color(0xFF8CAEFF)
    "Shopping" -> Color(0xFFC09AFF)
    "Bills & utilities" -> Color(0xFFFFC37D)
    "Health & personal care" -> Color(0xFFFF8E9A)
    "Entertainment" -> Color(0xFF87D5C1)
    "Subscriptions" -> Color(0xFFD0A6FF)
    else -> Muted
}
