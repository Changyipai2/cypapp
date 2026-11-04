package com.dsh.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class Kind { EXPENSE, INCOME }

data class Account(val id: Long, val name: String)

data class Tx(
    val id: Long,
    val accountId: Long,
    val cents: Long,
    val kind: Kind,
    val category: String,
    val note: String,
    val dateMillis: Long
)

val DEFAULT_EXPENSE_CATEGORIES = listOf("飲食", "交通", "購物", "娛樂", "居住", "醫療", "教育", "帳單", "其他")
val DEFAULT_INCOME_CATEGORIES = listOf("薪水", "獎金", "投資", "退款", "其他")

fun Long.toYuan(): String {
    val negative = this < 0
    val abs = if (negative) -this else this
    val body = (abs / 100).toString() + "." + (abs % 100).toString().padStart(2, '0')
    return if (negative) "-$body" else body
}

fun String.toCentsOrNull(): Long? {
    val t = this.trim().replace(",", "").replace("$", "").replace("NT", "").replace("元", "")
    if (t.isEmpty()) return null
    return runCatching {
        BigDecimal(t).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
    }.getOrNull()
}

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun LocalDate.toMillis(): Long = this.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun Long.month(): YearMonth = YearMonth.from(toLocalDate())
