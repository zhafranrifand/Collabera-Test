package com.expensemail.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF0C1728)
private val Surface = Color(0xFF142238)
private val Surface2 = Color(0xFF1B2C45)
private val Mint = Color(0xFF69E6B1)
private val Muted = Color(0xFF9DAFC4)
private val Canvas = Color(0xFF0C1728)
private val White = Color(0xFFF7FAFC)
private val Orange = Color(0xFFFFB86B)

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
    val recordType: String = "Expense"
)

private fun sampleExpenses() = listOf(
    Expense(merchant = "Kopi Kenangan", amount = 32000, date = todayLabel(), category = "Food & drink", approved = true, source = "BCA debit alert"),
    Expense(merchant = "TransJakarta", amount = 3500, date = todayLabel(), category = "Transport", approved = true, source = "BRI debit alert"),
    Expense(merchant = "Tokopedia", amount = 189900, date = yesterdayLabel(), category = "Shopping", approved = true, source = "Mandiri debit alert"),
    Expense(merchant = "Alfamart", amount = 54700, date = yesterdayLabel(), category = "Groceries", approved = true, source = "BCA debit alert"),
    Expense(merchant = "Gojek", amount = 24500, date = todayLabel(), category = "Transport", approved = false, source = "Gopay notification"),
    Expense(merchant = "Netflix", amount = 186000, date = todayLabel(), category = "Subscriptions", approved = false, source = "Card transaction alert")
)

private fun todayLabel() = SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(Date())
private fun yesterdayLabel(): String {
    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(cal.time)
}
private fun isCurrentMonth(date: String): Boolean = date.endsWith(SimpleDateFormat("MMM yyyy", Locale("en", "ID")).format(Date()))
private fun rupiah(value: Long): String = "Rp" + NumberFormat.getNumberInstance(Locale("id", "ID")).format(value)

private fun parseIdrAmount(raw: String): Long? {
    val value = raw.trim().filter { it.isDigit() || it == '.' || it == ',' }
    if (value.isEmpty()) return null
    val dot = value.lastIndexOf('.')
    val comma = value.lastIndexOf(',')
    val lastSeparator = maxOf(dot, comma)
    val integerPart = when {
        dot >= 0 && comma >= 0 && value.length - lastSeparator - 1 in 1..2 -> value.substring(0, lastSeparator)
        dot >= 0 && comma >= 0 -> value
        lastSeparator >= 0 && value.length - lastSeparator - 1 == 3 -> value
        lastSeparator >= 0 && value.length - lastSeparator - 1 in 1..2 -> value.substring(0, lastSeparator)
        else -> value
    }
    return integerPart.filter(Char::isDigit).toLongOrNull()?.takeIf { it > 0 }
}

private fun bankFromSender(sender: String, body: String): String {
    val address = sender.lowercase(Locale.ROOT).trim()
    val domain = Regex("@([a-z0-9.-]+)").find(address)?.groupValues?.getOrNull(1)
        ?: address.removePrefix("mailto:").removePrefix("www.").takeIf { it.contains('.') }.orEmpty()
    return when {
        domain == "bca.co.id" || domain.endsWith(".bca.co.id") || domain == "klikbca.com" -> "BCA"
        domain == "bankmandiri.co.id" || domain.endsWith(".bankmandiri.co.id") || domain == "livin.co.id" || domain.endsWith(".livin.co.id") -> "Livin’ by Mandiri"
        domain == "jago.com" || domain.endsWith(".jago.com") -> "Bank Jago"
        body.contains("myBCA", ignoreCase = true) || body.contains("PT Bank Central Asia", ignoreCase = true) -> "BCA"
        body.contains("Livin’", ignoreCase = true) || body.contains("Livin'", ignoreCase = true) || body.contains("Mandiri", ignoreCase = true) -> "Livin’ by Mandiri"
        body.contains("Jago", ignoreCase = true) -> "Bank Jago"
        else -> "Unknown bank"
    }
}

private fun transactionDate(text: String): String {
    val match = Regex("(?im)^\\s*(?:Transaction Date|Tanggal(?:\\s+transaksi)?)\\s*:?\\s*(\\d{1,2}\\s+[A-Za-zÀ-ÿ]+\\s+\\d{4})").find(text)
        ?: return todayLabel()
    val rawDate = match.groupValues[1]
    val months = mapOf("jan" to "Jan", "feb" to "Feb", "mar" to "Mar", "apr" to "Apr", "mei" to "May", "may" to "May", "jun" to "Jun", "jul" to "Jul", "agu" to "Aug", "ags" to "Aug", "aug" to "Aug", "sep" to "Sep", "okt" to "Oct", "oct" to "Oct", "nov" to "Nov", "des" to "Dec", "dec" to "Dec")
    val parts = rawDate.trim().split(Regex("\\s+"))
    if (parts.size != 3) return todayLabel()
    val month = months[parts[1].lowercase(Locale.ROOT).take(3)] ?: return todayLabel()
    return try {
        val parsed = SimpleDateFormat("d MMM yyyy", Locale.US).parse("${parts[0]} $month ${parts[2]}") ?: return todayLabel()
        SimpleDateFormat("d MMM yyyy", Locale("en", "ID")).format(parsed)
    } catch (_: Exception) { todayLabel() }
}

private fun parseAlert(raw: String, suppliedSender: String = ""): Expense? {
    val text = raw.replace('\u00A0', ' ').trim()
    if (text.length < 8) return null
    val senderInBody = Regex("(?im)^\\s*From:\\s*(?:.*?<)?([\\w.+-]+@[\\w.-]+)").find(text)?.groupValues?.getOrNull(1).orEmpty()
    val sender = suppliedSender.ifBlank { senderInBody }
    val labeledAmounts = listOf(
        Regex("(?im)^\\s*Total Payment\\s*:?\\s*IDR\\s*([0-9][0-9.,]*)"),
        Regex("(?im)^\\s*Total Transaksi\\s*:?\\s*IDR\\s*([0-9][0-9.,]*)"),
        Regex("(?im)^\\s*Jumlah\\s*:?\\s*(?:IDR|RP\\.?|RUPIAH)?\\s*([0-9][0-9.,]*)")
    )
    val amountText = labeledAmounts.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
        ?: Regex("(?i)(?:IDR|RP\\.?|RUPIAH)\\s*([0-9][0-9.,]*)|([0-9][0-9.,]*)\\s*(?:IDR|RP\\.?|RUPIAH)").find(text)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        ?: return null
    val amount = parseIdrAmount(amountText) ?: return null

    val merchantRegex = Regex("(?im)^\\s*(?:Payment to|Penerima|Merchant(?: Name)?|Ke)\\s*:?\\s*([^\\r\\n]+)")
    var merchant = merchantRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '!', '?', ';')
    if (merchant.isNullOrBlank()) {
        val hints = listOf(
            Regex("(?i)(?:merchant|recipient|beneficiary|penerima)\\s*[:=-]\\s*([^\\n,.]{2,48})"),
            Regex("(?i)(?:at|to|di|pada)\\s+([^\\n,.]{2,48})")
        )
        merchant = hints.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.trim() }
    }
    if (merchant.isNullOrBlank()) {
        merchant = text.lineSequence().map { it.trim() }
            .firstOrNull { it.length in 3..42 && !it.contains("@") && !it.contains(Regex("(?i)transaction|balance|debit|credit|account|card|alert|successful|success|nominal|total|tanggal|payment|amount|jumlah|biaya|reference|sumber dana|acquirer|terminal|merchant location|pan|rrn")) }
            ?: "Unknown merchant"
    }
    merchant = merchant.replace(Regex("(?i)\\s+(on|using|from)\\s+.*$"), "").replace(Regex("\\s{2,}"), " ").trim().take(42)
    val lower = text.lowercase(Locale.ROOT)
    val category = when {
        listOf("gojek", "grab", "transjakarta", "mrt", "taxi", "transport").any { lower.contains(it) } -> "Transport"
        listOf("alfamart", "indomaret", "supermarket", "grocery").any { lower.contains(it) } -> "Groceries"
        listOf("kopi", "cafe", "restaurant", "food", "makan").any { lower.contains(it) } -> "Food & drink"
        listOf("tokopedia", "shopee", "shopping", "marketplace").any { lower.contains(it) } -> "Shopping"
        listOf("netflix", "spotify", "subscription").any { lower.contains(it) } -> "Subscriptions"
        else -> "Other"
    }
    val bank = bankFromSender(sender, text)
    val recordType = if (lower.contains("transfer uang") || lower.contains("transfer dana") || lower.contains("money transfer") || lower.contains("melakukan transfer")) "Transfer" else "Expense"
    return Expense(merchant = merchant, amount = amount, date = transactionDate(text), category = if (recordType == "Transfer") "Transfer" else category, bank = bank, recordType = recordType)
}

private fun saveExpenses(context: Context, expenses: List<Expense>) {
    val array = JSONArray()
    expenses.forEach { e ->
        array.put(JSONObject().apply {
            put("id", e.id); put("merchant", e.merchant); put("amount", e.amount); put("date", e.date)
            put("category", e.category); put("source", e.source); put("approved", e.approved)
            put("bank", e.bank); put("recordType", e.recordType)
        })
    }
    context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).edit().putString("expenses", array.toString()).apply()
}

private fun loadExpenses(context: Context): List<Expense> {
    val raw = context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).getString("expenses", null) ?: return sampleExpenses()
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Expense(o.optString("id", UUID.randomUUID().toString()), o.optString("merchant", "Unknown"), o.optLong("amount"), o.optString("date", todayLabel()), o.optString("category", "Other"), o.optString("source", "Email alert"), o.optBoolean("approved"), o.optString("bank", "Unknown bank"), o.optString("recordType", "Expense"))
        }
    } catch (_: Exception) { sampleExpenses() }
}

@Composable
fun ExpenseMailApp(context: Context, incomingEmail: String?) {
    var expenses by remember { mutableStateOf(loadExpenses(context)) }
    var tab by remember { mutableStateOf(if (!incomingEmail.isNullOrBlank()) "Review" else "Home") }
    var importText by remember { mutableStateOf(incomingEmail.orEmpty()) }
    var senderText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var gmailConnected by remember { mutableStateOf(context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).getBoolean("gmail_connected", false)) }
    var gmailStatus by remember { mutableStateOf("") }
    var gmailSyncing by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("expensemail", Context.MODE_PRIVATE) }
    val defaultEndDate = remember { LocalDate.now() }
    var gmailStartDate by remember {
        mutableStateOf(runCatching { prefs.getString("gmail_start_date", null)?.let(LocalDate::parse) ?: defaultEndDate.minusDays(90) }.getOrDefault(defaultEndDate.minusDays(90)))
    }
    var gmailEndDate by remember {
        mutableStateOf(runCatching { prefs.getString("gmail_end_date", null)?.let(LocalDate::parse) ?: defaultEndDate }.getOrDefault(defaultEndDate))
    }
    val activity = context as? ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    val gmailScope = remember { listOf(Scope(GmailInboxReader.READ_ONLY_SCOPE)) }
    fun persist(updated: List<Expense>) { expenses = updated; saveExpenses(context, updated) }

    fun syncWithToken(accessToken: String) {
        coroutineScope.launch {
            gmailSyncing = true
            gmailStatus = "Checking recent bank alerts…"
            try {
                val (newExpenses, importedIds) = withContext(Dispatchers.IO) {
                    val prefs = context.getSharedPreferences("expensemail", Context.MODE_PRIVATE)
                    val knownIds = prefs.getStringSet("gmail_imported_ids", emptySet()).orEmpty().toSet()
                    val emails = GmailInboxReader.recentBankAlerts(accessToken, knownIds, gmailStartDate, gmailEndDate)
                    val parsed = emails.mapNotNull { email ->
                        parseAlert(email.body, email.sender)?.copy(source = "Gmail · ${email.subject}", approved = false)
                            ?.let { email.id to it }
                    }
                    parsed.map { it.second } to parsed.map { it.first }.toSet()
                }
                if (newExpenses.isNotEmpty()) persist(newExpenses + expenses)
                if (importedIds.isNotEmpty()) {
                    val prefs = context.getSharedPreferences("expensemail", Context.MODE_PRIVATE)
                    val merged = (prefs.getStringSet("gmail_imported_ids", emptySet()).orEmpty() + importedIds).toSet()
                    prefs.edit().putStringSet("gmail_imported_ids", merged).apply()
                }
                gmailConnected = true
                context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).edit().putBoolean("gmail_connected", true).apply()
                val period = "${gmailStartDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}–${gmailEndDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
                gmailStatus = if (newExpenses.isEmpty()) "No new matching bank alerts found for $period." else "${newExpenses.size} alert${if (newExpenses.size == 1) "" else "s"} added to review for $period."
            } catch (e: Exception) {
                gmailStatus = e.message ?: "Gmail sync failed. Try again."
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
                    if (token.isNullOrBlank()) gmailStatus = "Google did not provide Gmail access. Please try again."
                    else syncWithToken(token)
                }
                .onFailure { gmailStatus = it.message ?: "Gmail authorization failed." }
        } else {
            gmailStatus = "Gmail access was not granted."
        }
    }

    fun requestGmailSync() {
        if (activity == null) { gmailStatus = "Gmail authorization is unavailable in this screen."; return }
        gmailStatus = "Requesting Gmail access…"
        val request = AuthorizationRequest.builder().setRequestedScopes(gmailScope).build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent == null) gmailStatus = "Google did not return an authorization prompt."
                    else gmailAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) gmailStatus = "Google did not provide Gmail access. Please try again."
                    else syncWithToken(token)
                }
            }
            .addOnFailureListener { gmailStatus = it.message ?: "Gmail authorization failed." }
    }

    fun disconnectGmail() {
        Identity.getAuthorizationClient(context).revokeAccess(
            RevokeAccessRequest.builder().setScopes(gmailScope).build()
        ).addOnCompleteListener {
            gmailConnected = false
            context.getSharedPreferences("expensemail", Context.MODE_PRIVATE).edit()
                .remove("gmail_connected").remove("gmail_imported_ids").apply()
            gmailStatus = "Gmail disconnected. Previously imported transactions remain on this device."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Canvas, surface = Surface, onSurface = White)) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                NavigationBar(containerColor = Ink, contentColor = Muted, tonalElevation = 0.dp) {
                    listOf("Home", "Review", "Activity", "Settings").forEach { item ->
                        val icon = when (item) { "Home" -> Icons.Default.Home; "Review" -> Icons.Default.Inbox; "Activity" -> Icons.Default.AccountBalanceWallet; else -> Icons.Default.Settings }
                        NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, contentDescription = item) }, label = { Text(item, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Mint, selectedTextColor = Mint, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Surface2))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Canvas)) {
                when (tab) {
                    "Home" -> HomeScreen(expenses, onReview = { tab = "Review" }, onOpenGmail = { tab = "Settings" }, gmailConnected = gmailConnected)
                    "Review" -> ReviewScreen(
                        expenses = expenses,
                        importText = importText,
                        senderText = senderText,
                        onSenderChange = { senderText = it },
                        onTextChange = { importText = it; error = "" },
                        error = error,
                        onImport = {
                            val parsed = parseAlert(importText, senderText)
                            if (parsed == null) error = "I couldn't find an IDR amount. Paste the transaction alert text and try again."
                            else { persist(listOf(parsed.copy(approved = false)) + expenses); importText = ""; senderText = ""; error = "Added to review" }
                        },
                        onApprove = { id -> persist(expenses.map { if (it.id == id) it.copy(approved = true) else it }) },
                        onDelete = { id -> persist(expenses.filterNot { it.id == id }) },
                        onEdit = { editing = it }
                    )
                    "Activity" -> ActivityScreen(expenses.filter { it.approved })
                    else -> SettingsScreen(
                        onShareHelp = { tab = "Review" },
                        onSyncGmail = { requestGmailSync() },
                        onDisconnectGmail = { disconnectGmail() },
                        gmailConnected = gmailConnected,
                        gmailSyncing = gmailSyncing,
                        gmailStatus = gmailStatus,
                        startDate = gmailStartDate,
                        endDate = gmailEndDate,
                        onStartDateChange = { gmailStartDate = it; prefs.edit().putString("gmail_start_date", it.toString()).apply() },
                        onEndDateChange = { gmailEndDate = it; prefs.edit().putString("gmail_end_date", it.toString()).apply() }
                    )
                }
            }
        }
        editing?.let { expense -> EditExpenseDialog(expense, onDismiss = { editing = null }, onSave = { updated -> persist(expenses.map { if (it.id == updated.id) updated else it }); editing = null }) }
    }
}

@Composable
private fun HomeScreen(expenses: List<Expense>, onReview: () -> Unit, onOpenGmail: () -> Unit, gmailConnected: Boolean) {
    val approved = expenses.filter { it.approved }
    val currentMonthExpenses = approved.filter { isCurrentMonth(it.date) && it.recordType == "Expense" }
    val monthTotal = currentMonthExpenses.sumOf { it.amount }
    val pending = expenses.count { !it.approved }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("EXPENSEMAIL · v${BuildConfig.VERSION_NAME}", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp); Text("Your money, in view.", color = White, fontSize = 16.sp) }
                Box(Modifier.size(42.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Mint) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(20.dp)).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Inbox, null, tint = Mint)
                    Spacer(Modifier.width(10.dp))
                    Text("Import bank emails", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text("Choose a start and end date, then import BCA, Mandiri, and Jago alerts from Gmail.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = onOpenGmail, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(14.dp)) {
                    Text(if (gmailConnected) "Choose dates & sync Gmail" else "Set up Gmail import", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(24.dp)).padding(22.dp)) {
                Text("TRACKED THIS MONTH", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(7.dp)); Text(rupiah(monthTotal), color = White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(17.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Mint, CircleShape)); Spacer(Modifier.width(8.dp)); Text("${currentMonthExpenses.size} confirmed transactions", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Needs your review", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Check alerts before they count", color = Muted, fontSize = 13.sp) }
                Text("$pending", color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(15.dp)) { Text("Review transactions", fontWeight = FontWeight.Bold) }
        }
        item { Text("RECENT ACTIVITY", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp) }
        items(approved.take(4)) { expense -> ExpenseRow(expense) }
        item { Text("Transactions stay on this device. Email text is only used to create a transaction.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun ReviewScreen(expenses: List<Expense>, importText: String, senderText: String, onSenderChange: (String) -> Unit, onTextChange: (String) -> Unit, error: String, onImport: () -> Unit, onApprove: (String) -> Unit, onDelete: (String) -> Unit, onEdit: (Expense) -> Unit) {
    val pending = expenses.filter { !it.approved }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Review alerts", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("Confirm the details before adding them to your totals.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp)) }
        item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Share, null, tint = Mint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Import from Gmail", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Text("Share or paste the alert. Add the sender email or domain if the message text doesn't identify the bank.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp, bottom = 12.dp))
                OutlinedTextField(value = senderText, onValueChange = onSenderChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Sender email or domain (optional)") }, placeholder = { Text("noreply@bank.co.id", color = Muted) }, shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Surface2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint))
                Text("Bank is inferred from the domain; review the transaction details before confirming.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = importText, onValueChange = onTextChange, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), placeholder = { Text("Paste transaction alert text…", color = Muted) }, shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Surface2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint))
                if (error.isNotEmpty()) Text(error, color = if (error == "Added to review") Mint else Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                Spacer(Modifier.height(10.dp))
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink), shape = RoundedCornerShape(14.dp)) { Text("Read alert", fontWeight = FontWeight.Bold) }
            }
        }
        item { Text("PENDING · ${pending.size}", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 3.dp)) }
        if (pending.isEmpty()) item { Text("You're all caught up.", color = Muted, modifier = Modifier.padding(vertical = 18.dp)) }
        items(pending, key = { it.id }) { expense -> PendingCard(expense, onApprove, onDelete, onEdit) }
        item { Text("Tip: use Android's Share button in Gmail to send an alert here without granting mailbox-wide access.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
private fun PendingCard(expense: Expense, onApprove: (String) -> Unit, onDelete: (String) -> Unit, onEdit: (Expense) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(expense.merchant, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${expense.date}  ·  ${expense.bank}  ·  ${expense.recordType}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(rupiah(expense.amount), color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Text("${expense.category} · ${expense.source}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 11.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEdit(expense) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = White)) { Text("Edit") }
            OutlinedButton(onClick = { onDelete(expense.id) }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange)) { Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp)) }
            Button(onClick = { onApprove(expense.id) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) { Icon(Icons.Default.Check, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Confirm", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ActivityScreen(expenses: List<Expense>) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Activity", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("Confirmed transactions", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) }
        if (expenses.isEmpty()) item { Text("No confirmed transactions yet.", color = Muted) }
        items(expenses) { ExpenseRow(it) }
    }
}

@Composable
private fun ExpenseRow(expense: Expense) {
    Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) { Text(expense.category.take(1), color = Mint, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(expense.merchant, color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${expense.bank} · ${expense.recordType} · ${expense.date}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text("${if (expense.recordType == "Expense") "−" else ""}${rupiah(expense.amount)}", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsScreen(
    onShareHelp: () -> Unit,
    onSyncGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    gmailConnected: Boolean,
    gmailSyncing: Boolean,
    gmailStatus: String,
    startDate: LocalDate,
    endDate: LocalDate,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("ExpenseMail v${BuildConfig.VERSION_NAME} · Gmail import", color = Mint, fontSize = 12.sp)
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp)) {
            Text("Gmail sync", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Choose the email date range to check. Matching BCA, Livin’ by Mandiri, and Bank Jago alerts are imported to Review; they never count until you confirm them.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateChoice("From", startDate, onStartDateChange, Modifier.weight(1f))
                DateChoice("To", endDate, onEndDateChange, Modifier.weight(1f))
            }
            Text("Search period: ${startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))} – ${endDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (endDate.isBefore(startDate)) Text("Choose an end date on or after the start date.", color = Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Button(
                onClick = onSyncGmail,
                enabled = !gmailSyncing && !endDate.isBefore(startDate),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (gmailSyncing) "Checking Gmail…" else if (gmailConnected) "Sync Gmail" else "Connect Gmail & sync", fontWeight = FontWeight.Bold) }
            if (gmailConnected) TextButton(onClick = onDisconnectGmail) { Text("Disconnect Gmail", color = Orange) }
            if (gmailStatus.isNotBlank()) Text(gmailStatus, color = if (gmailStatus.contains("added") || gmailStatus.contains("No new")) Mint else Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Text("Google will ask for read-only Gmail access. This app searches only the selected date range and supported bank sender domains. Matching email content is processed on this phone.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
            Text("First-time setup: this app must be registered in Google Cloud before Gmail can connect. See the project's README for setup instructions.", color = Orange, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp)) {
            Text("Paste an email alert", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("You can also paste a bank alert to import a single transaction. This option works without connecting Gmail.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp))
            TextButton(onClick = onShareHelp) { Text("Open manual import", color = Mint) }
        }
        Text("Local-first · IDR · No analytics", color = Muted, fontSize = 12.sp)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DateChoice(label: String, date: LocalDate, onDateSelected: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showPicker = true }, modifier = modifier, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = White)) {
        Column {
            Text(label, color = Muted, fontSize = 10.sp)
            Text(date.format(DateTimeFormatter.ofPattern("d MMM yyyy")), color = White, fontSize = 12.sp)
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
