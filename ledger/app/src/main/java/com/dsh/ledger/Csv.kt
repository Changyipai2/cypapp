package com.dsh.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Csv {

    data class Table(val header: List<String>, val rows: List<List<String>>)

    data class Mapping(
        val date: Int = -1,
        val amount: Int = -1,
        val expenseAmount: Int = -1,
        val incomeAmount: Int = -1,
        val category: Int = -1,
        val note: Int = -1,
        val kind: Int = -1,
        val positiveIsIncome: Boolean = false,
        val encoding: String = "UTF-8"
    )

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyyMMdd",
        "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss", "M/d/yyyy", "MM/dd/yyyy", "yyyy年M月d日"
    )

    fun escape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    }

    fun export(txs: List<Tx>, accountName: String): String {
        val sb = StringBuilder()
        sb.append("日期,類型,分類,金額,備註,帳本\n")
        for (t in txs.sortedBy { it.dateMillis }) {
            sb.append(escape(t.dateMillis.toLocalDate().toString())).append(',')
                .append(if (t.kind == Kind.EXPENSE) "支出" else "收入").append(',')
                .append(escape(t.category)).append(',')
                .append(t.cents.toYuan()).append(',')
                .append(escape(t.note)).append(',')
                .append(escape(accountName)).append('\n')
        }
        return sb.toString()
    }

    fun splitLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            cur.append('"'); i++
                        } else quoted = false
                    } else cur.append(c)
                }
                c == '"' -> quoted = true
                c == ',' || c == ';' || c == '\t' -> {
                    out.add(cur.toString()); cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }
    }

    fun parse(text: String): Table {
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Table(emptyList(), emptyList())
        val header = splitLine(lines.first())
        val rows = lines.drop(1).map { splitLine(it) }
        return Table(header, rows)
    }

    private fun indexOfKeyword(header: List<String>, words: List<String>, exclude: List<String> = emptyList()): Int {
        header.forEachIndexed { i, raw ->
            val h = raw.lowercase().replace(" ", "")
            if (words.any { h.contains(it) } && exclude.none { h.contains(it) }) return i
        }
        return -1
    }

    fun guess(header: List<String>): Mapping {
        val expense = indexOfKeyword(header, listOf("支出金額", "支出额", "支出", "expense", "花費"))
        val income = indexOfKeyword(header, listOf("收入金額", "收入额", "收入", "income"))
        val hasSplit = expense != -1 && income != -1
        val amount = if (hasSplit) -1 else indexOfKeyword(header, listOf("金額", "amount", "價格", "money"))
        val kind = indexOfKeyword(header, listOf("類型", "收支", "type", "收/支"), listOf("分類"))
        val date = indexOfKeyword(header, listOf("日期", "時間", "date", "time"))
        val category = indexOfKeyword(header, listOf("分類", "類別", "項目", "category", "標籤"))
        val note = indexOfKeyword(header, listOf("備註", "說明", "描述", "note", "memo", "remark"))
        return Mapping(
            date = date,
            amount = amount,
            expenseAmount = if (hasSplit) expense else -1,
            incomeAmount = if (hasSplit) income else -1,
            category = category,
            note = note,
            kind = kind
        )
    }

    fun parseDate(raw: String): LocalDate? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        for (p in DATE_PATTERNS) {
            runCatching {
                val fmt = DateTimeFormatter.ofPattern(p)
                return if (p.contains("H")) LocalDateTime.parse(s, fmt).toLocalDate() else LocalDate.parse(s, fmt)
            }
        }
        val digits = s.filter { it.isDigit() }
        if (digits.length >= 8) {
            runCatching {
                return LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
            }
        }
        return null
    }

    fun parseAmount(raw: String): Long? {
        val t = raw.trim().replace(",", "").replace(" ", "")
            .replace("NT$", "").replace("NT", "").replace("$", "").replace("元", "")
        if (t.isEmpty()) return null
        return runCatching {
            BigDecimal(t).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
        }.getOrNull()
    }

    private fun detectKind(raw: String): Kind? {
        val s = raw.lowercase()
        return when {
            s.contains("收入") || s.contains("income") || s.contains("入") -> Kind.INCOME
            s.contains("支出") || s.contains("expense") || s.contains("出") -> Kind.EXPENSE
            else -> null
        }
    }

    fun toTxs(table: Table, map: Mapping, accountId: Long, startId: Long): Pair<List<Tx>, Int> {
        val out = mutableListOf<Tx>()
        var skipped = 0
        var id = startId
        fun cell(row: List<String>, idx: Int): String = if (idx in row.indices) row[idx].trim() else ""

        for (row in table.rows) {
            val date = parseDate(cell(row, map.date))
            if (date == null) { skipped++; continue }
            var cents: Long
            var kind: Kind
            if (map.expenseAmount >= 0 || map.incomeAmount >= 0) {
                val e = Math.abs(parseAmount(cell(row, map.expenseAmount)) ?: 0L)
                val i = Math.abs(parseAmount(cell(row, map.incomeAmount)) ?: 0L)
                when {
                    i > 0L -> { cents = i; kind = Kind.INCOME }
                    e > 0L -> { cents = e; kind = Kind.EXPENSE }
                    else -> { skipped++; continue }
                }
            } else {
                val raw = parseAmount(cell(row, map.amount))
                if (raw == null || raw == 0L) { skipped++; continue }
                val explicit = if (map.kind >= 0) detectKind(cell(row, map.kind)) else null
                kind = when {
                    explicit != null -> explicit
                    raw < 0 -> Kind.EXPENSE
                    map.positiveIsIncome -> Kind.INCOME
                    else -> Kind.EXPENSE
                }
                cents = if (raw < 0) -raw else raw
            }
            val category = cell(row, map.category).ifBlank { if (kind == Kind.EXPENSE) "其他" else "其他" }
            out.add(
                Tx(
                    id = id,
                    accountId = accountId,
                    cents = cents,
                    kind = kind,
                    category = category,
                    note = cell(row, map.note),
                    dateMillis = date.toMillis()
                )
            )
            id++
        }
        return out to skipped
    }

    fun decode(bytes: ByteArray): String {
        val utf8 = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        return if (utf8.contains('\uFFFD')) runCatching { bytes.toString(charset("Big5")) }.getOrDefault(utf8) else utf8
    }
}
