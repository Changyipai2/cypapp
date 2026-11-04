package com.dsh.ledger

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

fun writeExport(context: Context, name: String, content: String): File {
    val dir = context.getExternalFilesDir(null) ?: context.cacheDir
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, name)
    file.writeText(content)
    return file
}

fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = mime
    intent.putExtra(Intent.EXTRA_STREAM, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "分享檔案"))
}

@Composable
fun StatsScreen() {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val accountId = Store.currentAccountId
    val txs = Store.txs.filter { it.accountId == accountId && it.dateMillis.month() == month }
    val expense = txs.filter { it.kind == Kind.EXPENSE }.sumOf { it.cents }
    val income = txs.filter { it.kind == Kind.INCOME }.sumOf { it.cents }
    val byCategory = txs.filter { it.kind == Kind.EXPENSE }
        .groupBy { it.category }
        .map { entry -> entry.key to entry.value.sumOf { it.cents } }
        .sortedByDescending { it.second }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹ 上月") }
            Spacer(modifier = Modifier.weight(1f))
            Text(month.format(MONTH_FMT), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("下月 ›") }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("支出", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text(expense.toYuan(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("收入", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text(income.toYuan(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("結餘", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text((income - expense).toYuan(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("支出分類", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        if (byCategory.isEmpty()) {
            Text("本月沒有支出記錄", color = Color.Gray, fontSize = 13.sp)
        } else {
            val max = byCategory.first().second
            byCategory.forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pair.first, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(Color(0xFFE4E4DE), RoundedCornerShape(5.dp))
                    ) {
                        val frac = if (max == 0L) 0f else pair.second.toFloat() / max.toFloat()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(pair.second.toYuan(), fontSize = 12.sp, color = Color.DarkGray)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("近 6 個月支出", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        val months = (0..5).map { month.minusMonths(5L - it) }
        val values = months.map { m ->
            Store.txs.filter {
                it.accountId == accountId && it.kind == Kind.EXPENSE && it.dateMillis.month() == m
            }.sumOf { it.cents }
        }
        val maxValue = values.maxOrNull() ?: 0L
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            months.forEachIndexed { index, m ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(if (values[index] > 0) values[index].toYuan() else "", fontSize = 9.sp, color = Color.Gray)
                    val h = if (maxValue == 0L) 2 else ((values[index].toFloat() / maxValue.toFloat()) * 100f).toInt().coerceAtLeast(2)
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(h.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    )
                    Text(m.monthValue.toString() + "月", fontSize = 10.sp, color = Color.Gray)
                }
            }
        }
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var budgetDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingTable by remember { mutableStateOf<Csv.Table?>(null) }
    var pendingMapping by remember { mutableStateOf<Csv.Mapping?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                Csv.parse(Csv.decode(bytes))
            }.onSuccess { table ->
                if (table.header.isEmpty() || table.rows.isEmpty()) {
                    message = "這個檔案讀不到表格內容"
                } else {
                    pendingMapping = Csv.guess(table.header)
                    pendingTable = table
                }
            }.onFailure {
                message = "讀取失敗：" + (it.message ?: "未知錯誤")
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                Store.replaceAll(Csv.decode(bytes))
            }.onSuccess { message = "備份已還原" }
                .onFailure { message = "還原失敗：" + (it.message ?: "檔案格式不對") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("記帳設定", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("每月預算", fontWeight = FontWeight.Medium)
                Text(
                    if (Store.monthlyBudgetCents > 0) "目前：" + Store.monthlyBudgetCents.toYuan() + " 元"
                    else "尚未設定（不提醒）",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { budgetDialog = true }) { Text("設定預算") }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("資料", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val txs = Store.txs.filter { it.accountId == Store.currentAccountId }
                if (txs.isEmpty()) { message = "這個帳本還沒有資料" } else {
                    val file = writeExport(
                        context,
                        "ledger-" + LocalDate.now().toString() + ".csv",
                        Csv.export(txs, Store.currentAccount.name)
                    )
                    shareFile(context, file, "text/csv")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("匯出 CSV（目前帳本）") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val file = writeExport(
                    context,
                    "ledger-backup-" + LocalDate.now().toString() + ".json",
                    Store.toJson()
                )
                shareFile(context, file, "application/json")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("備份全部資料（JSON）") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { importLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("從其他記帳 App 的 CSV 匯入") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { restoreLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("還原備份（JSON）") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("清空目前帳本的記錄") }
        message?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("說明", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "・資料只存在這支手機，不需要網路。\n" +
                "・匯入時會自動辨識日期、金額、分類、備註欄位，匯入前可以手動調整。\n" +
                "・重複的記錄（同日期、金額、分類、備註）會自動跳過。\n" +
                "・換手機時：先「備份全部資料」，到新手機再「還原備份」。",
            fontSize = 13.sp,
            color = Color.DarkGray
        )
        Spacer(modifier = Modifier.height(30.dp))
    }

    if (budgetDialog) {
        var text by remember { mutableStateOf(if (Store.monthlyBudgetCents > 0) Store.monthlyBudgetCents.toYuan() else "") }
        AlertDialog(
            onDismissRequest = { budgetDialog = false },
            title = { Text("每月預算") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("金額（0 表示不提醒）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.setBudget(text.toCentsOrNull() ?: 0L)
                    budgetDialog = false
                    message = "預算已更新"
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { budgetDialog = false }) { Text("取消") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空記錄") },
            text = { Text("會刪除「" + Store.currentAccount.name + "」帳本裡全部記錄，建議先備份。確定清空？") },
            confirmButton = {
                TextButton(onClick = {
                    Store.clearAccount(Store.currentAccountId)
                    confirmClear = false
                    message = "已清空目前帳本"
                }) { Text("確定清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    val table = pendingTable
    val mapping = pendingMapping
    if (table != null && mapping != null) {
        ImportDialog(
            table = table,
            initial = mapping,
            onDismiss = { pendingTable = null; pendingMapping = null },
            onConfirm = { finalMap ->
                val result = Csv.toTxs(table, finalMap, Store.currentAccountId, Store.newId())
                val added = Store.addImported(result.first)
                pendingTable = null
                pendingMapping = null
                message = "匯入完成：" + added + " 筆" +
                    if (result.second > 0) "（略過 " + result.second + " 筆無法解析）" else ""
            }
        )
    }
}

@Composable
fun ImportDialog(
    table: Csv.Table,
    initial: Csv.Mapping,
    onDismiss: () -> Unit,
    onConfirm: (Csv.Mapping) -> Unit
) {
    var date by remember { mutableStateOf(initial.date) }
    var amount by remember { mutableStateOf(initial.amount) }
    var expenseAmount by remember { mutableStateOf(initial.expenseAmount) }
    var incomeAmount by remember { mutableStateOf(initial.incomeAmount) }
    var category by remember { mutableStateOf(initial.category) }
    var note by remember { mutableStateOf(initial.note) }
    var kind by remember { mutableStateOf(initial.kind) }
    var positiveIsIncome by remember { mutableStateOf(initial.positiveIsIncome) }
    var picking by remember { mutableStateOf<String?>(null) }

    val splitMode = expenseAmount >= 0 || incomeAmount >= 0

    fun headerName(index: Int): String = if (index in table.header.indices) table.header[index] else "（未設定）"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匯入 CSV") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("共 " + table.rows.size + " 列，請確認欄位對應：", fontSize = 13.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                MappingRow("日期", headerName(date)) { picking = "date" }
                if (splitMode) {
                    MappingRow("支出金額", headerName(expenseAmount)) { picking = "expenseAmount" }
                    MappingRow("收入金額", headerName(incomeAmount)) { picking = "incomeAmount" }
                } else {
                    MappingRow("金額", headerName(amount)) { picking = "amount" }
                    MappingRow("類型", headerName(kind)) { picking = "kind" }
                }
                MappingRow("分類", headerName(category)) { picking = "category" }
                MappingRow("備註", headerName(note)) { picking = "note" }
                if (!splitMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { positiveIsIncome = !positiveIsIncome }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (positiveIsIncome) "☑" else "☐", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("正數視為收入", fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("預覽：", fontSize = 12.sp, color = Color.Gray)
                table.rows.take(3).forEach { row ->
                    Text(row.joinToString(" ・ ") { it.take(12) }, fontSize = 11.sp, color = Color.DarkGray)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    Csv.Mapping(
                        date = date,
                        amount = amount,
                        expenseAmount = expenseAmount,
                        incomeAmount = incomeAmount,
                        category = category,
                        note = note,
                        kind = kind,
                        positiveIsIncome = positiveIsIncome
                    )
                )
            }) { Text("開始匯入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    val target = picking
    if (target != null) {
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("選擇欄位") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "不使用",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (target) {
                                    "date" -> date = -1
                                    "amount" -> amount = -1
                                    "expenseAmount" -> expenseAmount = -1
                                    "incomeAmount" -> incomeAmount = -1
                                    "category" -> category = -1
                                    "note" -> note = -1
                                    else -> kind = -1
                                }
                                picking = null
                            }
                            .padding(vertical = 10.dp)
                    )
                    table.header.forEachIndexed { index, name ->
                        Text(
                            name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (target) {
                                        "date" -> date = index
                                        "amount" -> amount = index
                                        "expenseAmount" -> expenseAmount = index
                                        "incomeAmount" -> incomeAmount = index
                                        "category" -> category = index
                                        "note" -> note = index
                                        else -> kind = index
                                    }
                                    picking = null
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("關閉") } }
        )
    }
}

@Composable
fun MappingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Text(
            value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text("▾", fontSize = 12.sp, color = Color.Gray)
    }
}
