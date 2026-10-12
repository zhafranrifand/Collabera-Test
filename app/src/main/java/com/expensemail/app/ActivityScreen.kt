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
internal fun ActivityScreen(
    expenses: List<Expense>,
    dateMode: String,
    startDate: LocalDate,
    endDate: LocalDate,
    onDateModeChange: (String) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit,
    categoryFilter: String?,
    onCategoryFilterChange: (String?) -> Unit,
    onReview: () -> Unit,
) {
    val today = LocalDate.now()
    var monthlyYear by remember { mutableIntStateOf(today.year) }
    val earliestYear = expenses.mapNotNull(::expenseLocalDate).minOfOrNull { it.year } ?: today.year
    val invalidRange = dateMode == "Custom" && endDate.isBefore(startDate)
    val dateVisible = when (dateMode) {
        "This month" -> transactionsInRange(expenses, today.withDayOfMonth(1), today)
        "Custom" -> if (invalidRange) emptyList() else transactionsInRange(expenses, startDate, endDate)
        else -> transactionsNewestFirst(expenses)
    }
    val visible = dateVisible.filter { categoryFilter == null || it.category == categoryFilter }
    val expenseTotal = visible.filter { it.recordType == "Expense" }.sumOf { it.amount }
    var page by remember(dateMode, startDate, endDate, categoryFilter) { mutableIntStateOf(0) }
    val currentPage = page.coerceIn(0, ((visible.size - 1).coerceAtLeast(0)) / LIST_PAGE_SIZE)
    val pageItems = visible.drop(currentPage * LIST_PAGE_SIZE).take(LIST_PAGE_SIZE)
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage, dateMode, startDate, endDate, categoryFilter) { listState.scrollToItem(0) }
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
        if (categoryFilter != null) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Category filter", color = Muted, fontSize = 12.sp)
                FilterChip(
                    selected = true,
                    onClick = { onCategoryFilterChange(null) },
                    label = { Text("$categoryFilter  ×") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ink),
                )
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
