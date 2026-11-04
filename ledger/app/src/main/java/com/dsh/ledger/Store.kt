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
            accArr.put(JSONObject().put("id", it.id).put("name", it.name))
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
                accs.add(Account(o.optLong("id", (i + 1).toLong()), o.optString("name", "帳本")))
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

    fun categoriesOf(kind: Kind): List<String> {
        val base = if (kind == Kind.EXPENSE) DEFAULT_EXPENSE_CATEGORIES else DEFAULT_INCOME_CATEGORIES
        val used = txs.filter { it.kind == kind }.map { it.category }.distinct()
        return (base + used.filterNot { base.contains(it) }).distinct()
    }
}
