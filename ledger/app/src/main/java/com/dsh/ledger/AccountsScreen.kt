package com.dsh.ledger

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 常用銀行：名稱 + 網銀登入頁 */
/** 銀行：名稱 / 登入頁 / 交易查詢頁（登入後可一鍵前往） */
data class BankPreset(val name: String, val loginUrl: String, val queryUrl: String)

val BANK_PRESETS = listOf(
    BankPreset("國泰世華", "https://www.cathaybk.com.tw/mybank/", ""),
    BankPreset("台北富邦", "https://www.fubon.com/banking/", ""),
    BankPreset("玉山銀行", "https://www.esunbank.com/zh-TW/personal", ""),
    BankPreset("台新 Richart", "https://richart.tw/", ""),
    BankPreset("中國信託", "https://www.ctbcbank.com/", ""),
    BankPreset("永豐銀行", "https://bank.sinopac.com/", ""),
    BankPreset("聯邦銀行", "https://netbank.ubot.com.tw/", ""),
    BankPreset("其他", "", "")
)

@Composable
fun AccountsScreen() {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<BankAccount?>(null) }
    var openAccount by remember { mutableStateOf<BankAccount?>(null) }

    val webAccount = openAccount
    // 改用獨立 Activity 開網銀（Compose 內嵌 WebView 在部分機型叫不出鍵盤）
    LaunchedEffect(webAccount) {
        if (webAccount != null) {
            runCatching {
                context.startActivity(
                    BankWebActivity.intent(
                        context, webAccount.bankName, webAccount.loginUrl,
                        webAccount.queryUrl, webAccount.idNo, webAccount.userCode
                    )
                )
            }.onFailure {
                android.widget.Toast.makeText(context, "無法開啟網銀：${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            openAccount = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("帳戶保險箱", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "・登入帳號以 Android Keystore（AES-256）加密，只存在這支手機\n" +
                            "・不儲存密碼，登入時由你輸入或系統自動填入\n" +
                            "・登入與 OTP 一律由你本人完成",
                        color = Color.White.copy(alpha = .92f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (Vault.accounts.isEmpty()) {
            item {
                Text(
                    "還沒有帳戶。按下方「新增帳戶」加入銀行。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(Vault.accounts, key = { it.id }) { acc ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var revealed by remember(acc.id) { mutableStateOf(false) }
                    LaunchedEffect(revealed) {
                        if (revealed) {
                            kotlinx.coroutines.delay(6000)
                            revealed = false
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(acc.bankName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        val idText = if (acc.idNo.isBlank()) "" else
                            " ・ 身分證 " + (if (revealed) acc.idNo else maskIdNo(acc.idNo))
                        val codeText = if (acc.userCode.isBlank()) "" else
                            " ・ 代號 " + (if (revealed) acc.userCode else maskCode(acc.userCode))
                        Text(
                            acc.currency + " ・ 末四碼 " + acc.accountTail.ifBlank { "----" } + idText + codeText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (acc.idNo.isNotBlank() || acc.userCode.isNotBlank()) {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                painterResource(if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye),
                                contentDescription = if (revealed) "隱藏" else "顯示（6 秒後自動隱藏）"
                            )
                        }
                    }
                    if (acc.loginUrl.isNotBlank()) {
                        OutlinedButton(onClick = { openAccount = acc }) { Text("登入", fontSize = 13.sp) }
                    }
                    IconButton(onClick = { confirmDelete = acc }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0xFFC62828))
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("新增帳戶")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "※ 目前不支援「自動登入」。網銀的密碼與 OTP 必須由你本人輸入，這是銀行的使用者約定與法規要求。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onSave = { acc ->
                Vault.add(acc)
                showAdd = false
            }
        )
    }

    confirmDelete?.let { acc ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("刪除帳戶資料") },
            text = { Text("會刪除「" + acc.bankName + "」在本機儲存的登入帳號（不改動銀行端的任何設定）。") },
            confirmButton = {
                TextButton(onClick = {
                    Vault.remove(acc.id)
                    confirmDelete = null
                }) { Text("刪除", color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
fun AddAccountDialog(onDismiss: () -> Unit, onSave: (BankAccount) -> Unit) {
    var bankName by remember { mutableStateOf(BANK_PRESETS[0].name) }
    var url by remember { mutableStateOf(BANK_PRESETS[0].loginUrl) }
    var queryUrl by remember { mutableStateOf(BANK_PRESETS[0].queryUrl) }
    var tail by remember { mutableStateOf("") }
    var idNo by remember { mutableStateOf("") }
    var userCode by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("TWD") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增帳戶") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("銀行", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    BANK_PRESETS.take(3).forEach { b ->
                        TextButton(onClick = {
                            bankName = b.name; url = b.loginUrl; queryUrl = b.queryUrl
                        }) { Text(b.name, fontSize = 11.sp) }
                    }
                }
                Row(modifier = Modifier.padding(bottom = 6.dp)) {
                    BANK_PRESETS.drop(3).take(3).forEach { b ->
                        TextButton(onClick = {
                            bankName = b.name; url = b.loginUrl; queryUrl = b.queryUrl
                        }) { Text(b.name, fontSize = 11.sp) }
                    }
                }
                OutlinedTextField(
                    value = bankName, onValueChange = { bankName = it },
                    label = { Text("銀行名稱") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("網銀登入網址（可空白）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = queryUrl, onValueChange = { queryUrl = it },
                    label = { Text("交易查詢頁網址（登入後一鍵前往）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = tail, onValueChange = { tail = it.take(4) },
                        label = { Text("帳號末四碼") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = currency, onValueChange = { currency = it.uppercase().take(3) },
                        label = { Text("幣別") }, singleLine = true,
                        modifier = Modifier.width(90.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = idNo, onValueChange = { idNo = it.uppercase().take(10) },
                    label = { Text("身分證字號（加密儲存，登入頁自動帶入）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userCode, onValueChange = { userCode = it },
                    label = { Text("網銀用戶代號（加密儲存，登入頁自動帶入）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("備註") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (bankName.isNotBlank()) {
                        onSave(
                            BankAccount(
                                id = 0, bankName = bankName.trim(), accountTail = tail.trim(),
                                currency = currency.ifBlank { "TWD" }, loginUrl = url.trim(),
                                note = note.trim(), queryUrl = queryUrl.trim(),
                                idNo = idNo.trim(), userCode = userCode.trim()
                            )
                        )
                    }
                }
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 身分證字號打碼：保留首碼與末三碼 */
private fun maskIdNo(s: String): String =
    if (s.length <= 4) "****" else s.take(1) + "******" + s.takeLast(3)

/** 用戶代號打碼：保留前兩碼 */
private fun maskCode(s: String): String =
    if (s.length <= 2) "****" else s.take(2) + "****"
