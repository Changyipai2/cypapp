package com.dsh.ledger

/**
 * 各家網銀的「取明細」設定檔。
 * detailPath 有值 → 登入後直接呼叫 API（最快最穩）。
 * detailPath 空白 → 尚未建檔，需先用「記錄銀行內部請求」側錄一次，再由開發者補上。
 */
data class BankProfile(
    val name: String,
    val loginUrl: String,
    val jwtPath: String = "",
    val detailPath: String = "",
    val note: String = ""
) {
    val ready: Boolean get() = detailPath.isNotBlank()
}

object BankProfiles {
    val all: List<BankProfile> = listOf(
        BankProfile(
            name = "國泰世華",
            loginUrl = "https://www.cathaybk.com.tw/mybank/",
            jwtPath = "/MyBank/Customized/GetJWT",
            detailPath = "/OnlineBankingApi/ClientBank/Api/ClientBank/B_ACCT_Q_TransferDetail",
            note = "已建檔"
        ),
        BankProfile("台北富邦", "https://www.fubon.com/banking/"),
        BankProfile("玉山銀行", "https://www.esunbank.com/zh-TW/personal"),
        BankProfile("台新 Richart", "https://richart.tw/"),
        BankProfile("中國信託", "https://www.ctbcbank.com/"),
        BankProfile("永豐銀行", "https://bank.sinopac.com/"),
        BankProfile("聯邦銀行", "https://netbank.ubot.com.tw/"),
        BankProfile("華南銀行", "https://www.hncb.com.tw/"),
        BankProfile("臺灣銀行", "https://www.bot.com.tw/"),
        BankProfile("土地銀行", "https://www.landbank.com.tw/"),
        BankProfile("中華郵政", "https://ipost.post.gov.tw/")
    )

    fun find(bankName: String): BankProfile? {
        val n = bankName.trim()
        if (n.isEmpty()) return null
        return all.firstOrNull { it.name == n }
            ?: all.firstOrNull { n.contains(it.name) || it.name.contains(n) }
    }
}
