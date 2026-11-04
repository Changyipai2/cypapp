package com.dsh.ledger

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 E", Locale.TAIWAN)
val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.TAIWAN)
val EXPENSE_COLOR = Color(0xFFD84315)
val INCOME_COLOR = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF00695C),
                    secondary = Color(0xFF00897B),
                    background = Color(0xFFF7F7F5)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var tab by remember { mutableStateOf(0) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tx?>(null) }
    var accountDialog by remember { mutableStateOf(false) }
    val titles = listOf("簡記帳", "統計", "設定")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[tab], fontWeight = FontWeight.Bold) },
                actions = {
                    if (tab == 0) {
                        TextButton(onClick = { accountDialog = true }) {
                            Text(Store.currentAccount.name)
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("記錄") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("統計") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("設定") }
                )
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { editing = null; editorOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新增")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> TxList(onEdit = { editing = it; editorOpen = true })
                1 -> StatsScreen()
                else -> SettingsScreen()
            }
        }
    }

    if (editorOpen) {
        TxEditor(initial = editing, onDismiss = { editorOpen = false; editing = null })
    }
    if (accountDialog) {
        AccountDialog(onDismiss = { accountDialog = false })
    }
}

@Composable
fun TxList(onEdit: (Tx) -> Unit) {
    val accountId = Store.currentAccountId
    val all = Store.txs.filter { it.accountId == accountId }
    val month = YearMonth.now()
    val monthTxs = all.filter { it.dateMillis.month() == month }
    val expense = monthTxs.filter { it.kind == Kind.EXPENSE }.sumOf { it.cents }
    val income = monthTxs.filter { it.kind == Kind.INCOME }.sumOf { it.cents }

    Column(modifier = Modifier.fillMaxSize()) {
        SummaryCard(expense, income)
        if (all.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("還沒有記錄，按右下角 + 開始記帳", color = Color.Gray)
            }
        } else {
            val grouped = all.sortedByDescending { it.dateMillis }.groupBy { it.dateMillis.toLocalDate() }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                grouped.forEach { (date, list) ->
                    item(key = "h-" + date.toString()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFEFEFEA))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(date.format(DATE_FMT), fontSize = 13.sp, color = Color.DarkGray)
                            Spacer(modifier = Modifier.weight(1f))
                            val dayExpense = list.filter { it.kind == Kind.EXPENSE }.sumOf { it.cents }
                            if (dayExpense > 0) {
                                Text("支出 " + dayExpense.toYuan(), fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                    items(list, key = { tx -> tx.id }) { tx ->
                        TxRow(tx) { onEdit(tx) }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryCard(expense: Long, income: Long) {
    val budget = Store.monthlyBudgetCents
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(YearMonth.now().format(MONTH_FMT) + " 本月", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("支出 " + expense.toYuan(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text("收入 " + income.toYuan(), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Text("結餘 " + (income - expense).toYuan(), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            }
            if (budget > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                val ratio = (expense.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
                val over = expense > budget
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(8.dp)
                            .background(if (over) Color(0xFFFFD54F) else Color.White, RoundedCornerShape(4.dp))
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    if (over) "已超出預算 " + (expense - budget).toYuan() + " 元"
                    else "預算剩 " + (budget - expense).toYuan() + " / " + budget.toYuan(),
                    color = if (over) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun TxRow(tx: Tx, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tx.category, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            if (tx.note.isNotBlank()) {
                Text(tx.note, fontSize = 12.sp, color = Color.Gray)
            }
        }
        Text(
            (if (tx.kind == Kind.EXPENSE) "-" else "+") + tx.cents.toYuan(),
            color = if (tx.kind == Kind.EXPENSE) EXPENSE_COLOR else INCOME_COLOR,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxEditor(initial: Tx?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(initial?.kind ?: Kind.EXPENSE) }
    var amount by remember { mutableStateOf(initial?.cents?.toYuan() ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var date by remember { mutableStateOf(initial?.dateMillis?.toLocalDate() ?: LocalDate.now()) }
    var error by remember { mutableStateOf<String?>(null) }
    val categories = Store.categoriesOf(kind)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新增記錄" else "編輯記錄") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = {
                            Store.delete(initial.id)
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "刪除")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row {
                Button(
                    onClick = { kind = Kind.EXPENSE },
                    modifier = Modifier.weight(1f)
                ) { Text("支出") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { kind = Kind.INCOME },
                    modifier = Modifier.weight(1f)
                ) { Text("收入") }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("金額") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("分類", fontSize = 13.sp, color = Color.Gray)
            LazyRow(modifier = Modifier.padding(vertical = 6.dp)) {
                items(categories) { c ->
                    val selected = category == c
                    Text(
                        c,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color(0xFFE8E8E3),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { category = c }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (selected) Color.White else Color.DarkGray,
                        fontSize = 14.sp
                    )
                }
            }
            OutlinedTextField(
                value = if (categories.contains(category)) "" else category,
                onValueChange = { category = it },
                label = { Text("自訂分類（選填）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("備註") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("日期：" + date.toString(), fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    DatePickerDialog(
                        context,
                        { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                        date.year,
                        date.monthValue - 1,
                        date.dayOfMonth
                    ).show()
                }) { Text("選擇日期") }
            }
            error?.let {
                Text(it, color = EXPENSE_COLOR, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val cents = amount.toCentsOrNull()
                    if (cents == null || cents <= 0L) {
                        error = "請輸入正確的金額"
                        return@Button
                    }
                    val finalCategory = category.ifBlank { categories.firstOrNull() ?: "其他" }
                    Store.upsert(
                        Tx(
                            id = initial?.id ?: Store.newId(),
                            accountId = initial?.accountId ?: Store.currentAccountId,
                            cents = cents,
                            kind = kind,
                            category = finalCategory,
                            note = note.trim(),
                            dateMillis = date.toMillis()
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("儲存") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun AccountDialog(onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("帳本") },
        text = {
            Column {
                Store.accounts.forEach { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Store.selectAccount(acc.id)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            acc.name,
                            color = if (acc.id == Store.currentAccountId) MaterialTheme.colorScheme.primary else Color.DarkGray,
                            fontWeight = if (acc.id == Store.currentAccountId) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新帳本名稱") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        Store.addAccount(newName.trim())
                        onDismiss()
                    }
                }
            ) { Text("新增") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        }
    )
}
