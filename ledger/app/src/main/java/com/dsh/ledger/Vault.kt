package com.dsh.ledger

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/** 一個網銀帳戶（只存「登入帳號」，不存密碼） */
data class BankAccount(
    val id: Long,
    val bankName: String,
    val accountTail: String,   // 末四碼
    val currency: String,      // TWD / USD ...
    val loginUrl: String,      // 網銀登入頁深連結
    val loginId: String,       // 登入帳號（加密存放）
    val queryUrl: String = "", // 交易查詢頁（登入後一鍵前往）
    val note: String = ""
)

/**
 * 帳戶保險箱：
 * - 登入帳號以 Android Keystore (AES-256-GCM) 加密後才寫入檔案
 * - 完全不儲存密碼；密碼交由系統 Autofill / 使用者手動輸入
 * - 所有資料留在本機，不上傳
 */
object Vault {
    private const val KEY_ALIAS = "ledger_vault_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val PREF = "vault.enc"
    private var dir: File? = null

    var accounts by mutableStateOf(listOf<BankAccount>())
        private set

    fun init(ctx: Context) {
        if (dir != null) return
        dir = ctx.filesDir
        load()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance("AES", KEYSTORE)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String? = runCatching {
        val parts = payload.split(":")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun file(): File {
        val d = dir ?: return File("/dev/null")
        return File(d, PREF)
    }

    private fun load() {
        val f = file()
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("accounts") ?: JSONArray()
            val list = mutableListOf<BankAccount>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val encLogin = o.optString("loginIdEnc", "")
                val loginId = if (encLogin.isBlank()) "" else decrypt(encLogin) ?: ""
                list.add(
                    BankAccount(
                        id = o.optLong("id"),
                        bankName = o.optString("bankName"),
                        accountTail = o.optString("accountTail"),
                        currency = o.optString("currency", "TWD"),
                        loginUrl = o.optString("loginUrl"),
                        loginId = loginId,
                        queryUrl = o.optString("queryUrl", ""),
                        note = o.optString("note", "")
                    )
                )
            }
            accounts = list
        }
    }

    fun save() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("bankName", a.bankName)
                    .put("accountTail", a.accountTail)
                    .put("currency", a.currency)
                    .put("loginUrl", a.loginUrl)
                    .put("loginIdEnc", if (a.loginId.isBlank()) "" else encrypt(a.loginId))
                    .put("queryUrl", a.queryUrl)
                    .put("note", a.note)
            )
        }
        val root = JSONObject().put("version", 1).put("accounts", arr)
        runCatching { file().writeText(root.toString()) }
    }

    fun add(account: BankAccount) {
        val id = (accounts.maxOfOrNull { it.id } ?: 0L) + 1L
        accounts = accounts + account.copy(id = id)
        save()
    }

    fun remove(id: Long) {
        accounts = accounts.filterNot { it.id == id }
        save()
    }

    fun update(account: BankAccount) {
        accounts = accounts.map { if (it.id == account.id) account else it }
        save()
    }
}
