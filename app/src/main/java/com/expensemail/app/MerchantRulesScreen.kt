package com.expensemail.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MerchantRulesScreen(
    rules: List<MerchantCategoryRule>,
    actionsEnabled: Boolean,
    onBack: () -> Unit,
    onChangeCategory: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MerchantCategoryRule?>(null) }
    var deleting by remember { mutableStateOf<MerchantCategoryRule?>(null) }
    val active = rules.filterNot { it.deleted }.sortedBy { it.key }
    val visible = active.filter { it.merchant.contains(search.trim(), ignoreCase = true) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Mint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back to Settings", color = Mint)
            }
            Text("Remembered merchants", color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Your choices apply to future expenses from the same merchant. Existing transactions keep their categories.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        if (!actionsEnabled) item {
            Text("Wait for Gmail sync to finish before changing merchant categories.", color = Orange, fontSize = 12.sp)
        }
        if (active.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No remembered merchants yet", color = White, fontWeight = FontWeight.SemiBold)
                Text("Edit a transaction, choose its category, then check “Remember category for this merchant” before saving.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        } else {
            item {
                OutlinedTextField(search, { search = it }, label = { Text("Search merchants") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${active.size} remembered merchants", color = Muted, fontSize = 12.sp)
            }
            if (visible.isEmpty()) item { Text("No matching merchants", color = Muted) }
            items(visible, key = { it.key }) { rule ->
                Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(rule.merchant, color = White, fontWeight = FontWeight.SemiBold)
                        Text(rule.category, color = Mint, fontSize = 13.sp)
                    }
                    IconButton(onClick = { editing = rule }, enabled = actionsEnabled) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit category for ${rule.merchant}", tint = Mint)
                    }
                    IconButton(onClick = { deleting = rule }, enabled = actionsEnabled) {
                        Icon(Icons.Default.Delete, contentDescription = "Forget ${rule.merchant}", tint = Orange)
                    }
                }
            }
        }
    }
    editing?.let { rule ->
        MerchantRuleCategoryDialog(rule, actionsEnabled, onDismiss = { editing = null }, onSave = { category ->
            onChangeCategory(rule.merchant, category)
            editing = null
        })
    }
    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Surface,
            title = { Text("Forget this merchant?", color = White) },
            text = { Text("Future expenses at ${rule.merchant} will use the standard category suggestions again. Existing transactions keep their categories.", color = Muted) },
            confirmButton = { TextButton(enabled = actionsEnabled, onClick = { onDelete(rule.key); deleting = null }) { Text("Forget merchant", color = Orange) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun MerchantRuleCategoryDialog(rule: MerchantCategoryRule, actionsEnabled: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var category by remember(rule.key) { mutableStateOf(rule.category) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface,
        title = { Text(rule.merchant, color = White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Category for future expenses", color = Muted, fontSize = 13.sp)
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(category, color = Mint, modifier = Modifier.weight(1f))
                        Text("Change", color = Mint)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        EXPENSE_CATEGORIES.filter { it != "Transfer" }.forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { category = value; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = actionsEnabled, onClick = { onSave(category) }) { Text("Save category", color = Mint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}
