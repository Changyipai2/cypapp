package com.dsh.ledger

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Store {
    private var file: File? = null

    var accounts by mutableStateOf(listOf(Account(1L, "個人")))
        private set
    var txs by mutableStateOf(emptyList<Tx>())
        private set
    var currentAccountId by mutableStateOf(1L)
        private set
    var monthlyBudgetCents by mutableStateOf(0L)
        private set

    val currentAccount: Account
        get() = accounts.firstOrNull { it.id == currentAccountId } ?: accounts.first()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "ledger.json")
        file = f
        if (f.exists()) {
            runCatching { loadFromJson(f.readText()) }
        }
        // 自動匯入：app 專屬外部目錄若有 import.json，就「合併」進現有帳本（不覆蓋）
        // 路徑: /sdcard/Android/data/com.dsh.ledger/files/import.json
        val importFile = File(ctx.getExternalFilesDir(null) ?: return, "import.json")
        if (importFile.exists()) {
            val stats = runCatching { mergeFromJson(importFile.readText()) }.getOrNull()
            if (stats != null) {
                runCatching { importFile.renameTo(File(importFile.parentFile, "import_done.json")) }
                lastImportResult = stats
            }
        }
    }

    /** 最近一次自動匯入的結果（給設定頁顯示） */
    var lastImportResult by mutableStateOf<String?>(null)
        private set

    /** 合併外部 JSON：帳本依名稱對應，記錄依內容去重建檔 */
    fun mergeFromJson(text: String): String {
        val root = JSONObject(text)
        val inAccs = mutableListOf<Pair<Long, String>>()
        val inAccTypes = mutableMapOf<String, AccountType>()
        root.optJSONArray("accounts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                inAccs.add(o.optLong("id") to o.optString("name"))
                if (o.optString("type", "ASSET") == "LIABILITY") inAccTypes[o.optString("name")] = AccountType.LIABILITY
            }
        }
        val beforeAccounts = accounts.size
        val beforeTxs = txs.size
        val nameToId = accounts.associate { it.name to it.id }.toMutableMap()
        var nextAcc = (accounts.maxOfOrNull { it.id } ?: 0L) + 1L
        val idMap = mutableMapOf<Long, Long>()
        val newAccounts = accounts.toMutableList()
        inAccs.forEach { (oldId, name) ->
            val id = nameToId.getOrPut(name) {
                val inType = inAccTypes[name] ?: AccountType.ASSET
                val created = Account(nextAcc++, name, inType)
                newAccounts.add(created)
                nameToId[name] = created.id
                created.id
            }
            idMap[oldId] = id
        }
        accounts = newAccounts

        val seen = txs.map { listOf(it.accountId, it.cents, it.kind.name, it.dateMillis, it.category, it.note).joinToString("|") }.toMutableSet()
        val added = mutableListOf<Tx>()
        var nextId = (txs.maxOfOrNull { it.id } ?: 0L) + 1L
        root.optJSONArray("txs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val accId = idMap[o.optLong("accountId")] ?: continue
                val kind = if (o.optString("kind", "EXPENSE") == "INCOME") Kind.INCOME else Kind.EXPENSE
                val cents = o.optLong("cents", 0L)
                val date = o.optLong("date", 0L)
                val cat = o.optString("category", "其他")
                val note = o.optString("note", "")
                val key = listOf(accId, cents, kind.name, date, cat, note).joinToString("|")
                if (!seen.add(key)) continue
                added.add(Tx(nextId++, accId, cents, kind, cat, note, date))
            }
        }
        txs = txs + added
        save()
        return "帳本 +" + (accounts.size - beforeAccounts) + "，記錄 +" + added.size +
            "（現有 " + txs.size + " 筆，原 " + beforeTxs + " 筆）"
    }

    fun save() {
        file?.let { runCatching { it.writeText(toJson()) } }
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("budget", monthlyBudgetCents)
        root.put("currentAccount", currentAccountId)
        val accArr = JSONArray()
        accounts.forEach {
            accArr.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type.name))
        }
        root.put("accounts", accArr)
        val txArr = JSONArray()
        txs.forEach {
            txArr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("accountId", it.accountId)
                    .put("cents", it.cents)
                    .put("kind", it.kind.name)
                    .put("category", it.category)
                    .put("note", it.note)
                    .put("date", it.dateMillis)
            )
        }
        root.put("txs", txArr)
        return root.toString()
    }

    fun loadFromJson(text: String) {
        val root = JSONObject(text)
        monthlyBudgetCents = root.optLong("budget", 0L)
        val accArr = root.optJSONArray("accounts")
        val accs = mutableListOf<Account>()
        if (accArr != null) {
            for (i in 0 until accArr.length()) {
                val o = accArr.getJSONObject(i)
                accs.add(
                    Account(
                        o.optLong("id", (i + 1).toLong()),
                        o.optString("name", "帳本"),
                        if (o.optString("type", "ASSET") == "LIABILITY") AccountType.LIABILITY else AccountType.ASSET
                    )
                )
            }
        }
        if (accs.isEmpty()) accs.add(Account(1L, "個人"))
        accounts = accs
        val txArr = root.optJSONArray("txs")
        val list = mutableListOf<Tx>()
        if (txArr != null) {
            for (i in 0 until txArr.length()) {
                val o = txArr.getJSONObject(i)
                list.add(
                    Tx(
                        id = o.optLong("id", (i + 1).toLong()),
                        accountId = o.optLong("accountId", accounts.first().id),
                        cents = o.optLong("cents", 0L),
                        kind = if (o.optString("kind", "EXPENSE") == "INCOME") Kind.INCOME else Kind.EXPENSE,
                        category = o.optString("category", "其他"),
                        note = o.optString("note", ""),
                        dateMillis = o.optLong("date", System.currentTimeMillis())
                    )
                )
            }
        }
        txs = list
        currentAccountId = root.optLong("currentAccount", accounts.first().id)
        if (accounts.none { it.id == currentAccountId }) currentAccountId = accounts.first().id
        save()
    }

    fun upsert(tx: Tx) {
        txs = if (txs.any { it.id == tx.id }) txs.map { if (it.id == tx.id) tx else it } else txs + tx
        save()
    }

    fun newId(): Long = (txs.maxOfOrNull { it.id } ?: 0L) + 1L

    fun delete(id: Long) {
        txs = txs.filterNot { it.id == id }
        save()
    }

    fun clearAccount(accountId: Long) {
        txs = txs.filterNot { it.accountId == accountId }
        save()
    }

    fun selectAccount(id: Long) {
        currentAccountId = id
        save()
    }

    fun addAccount(name: String) {
        val id = (accounts.maxOfOrNull { it.id } ?: 0L) + 1L
        accounts = accounts + Account(id, name)
        currentAccountId = id
        save()
    }

    fun setBudget(cents: Long) {
        monthlyBudgetCents = cents
        save()
    }

    fun addImported(list: List<Tx>): Int {
        val before = txs.size
        val added = mutableListOf<Tx>()
        val seen = txs.toMutableList()
        for (t in list) {
            val dup = seen.any {
                it.dateMillis == t.dateMillis && it.cents == t.cents &&
                    it.kind == t.kind && it.note == t.note && it.category == t.category
            }
            if (!dup) {
                added.add(t)
                seen.add(t)
            }
        }
        if (added.isEmpty()) return 0
        txs = txs + added
        save()
        return txs.size - before
    }

    fun replaceAll(text: String) = loadFromJson(text)

    fun setAccountType(id: Long, type: AccountType) {
        accounts = accounts.map { if (it.id == id) it.copy(type = type) else it }
        save()
    }

    /** 某帳本的累計淨額（收入 - 支出），單位分 */
    fun netOf(accountId: Long): Long =
        txs.filter { it.accountId == accountId }
            .sumOf { if (it.kind == Kind.INCOME) it.cents else -it.cents }

    /** 資產合計（ASSET 帳本淨額，負數視為 0 之外的實際值照算） */
    fun totalAssets(): Long = accounts.filter { it.type == AccountType.ASSET }.sumOf { netOf(it.id) }

    /** 負債合計（LIABILITY 帳本以正數呈現欠款） */
    fun totalLiabilities(): Long =
        accounts.filter { it.type == AccountType.LIABILITY }.sumOf { -netOf(it.id) }

    fun categoriesOf(kind: Kind): List<String> {
        val base = if (kind == Kind.EXPENSE) DEFAULT_EXPENSE_CATEGORIES else DEFAULT_INCOME_CATEGORIES
        val used = txs.filter { it.kind == kind }.map { it.category }.distinct()
        return (base + used.filterNot { base.contains(it) }).distinct()
    }
}
