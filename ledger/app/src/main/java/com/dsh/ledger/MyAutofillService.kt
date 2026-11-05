package com.dsh.ledger

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

/**
 * 只提供「登入帳號」的自動填入服務。
 * 刻意不提供密碼、不讀取你輸入的內容、不儲存任何東西。
 * 使用者需在系統設定中手動啟用：設定 → 系統 → 語言與輸入 → 自動填入服務 → 簡記帳。
 */
class MyAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        Vault.init(this)
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null); return
        }
        val ids = mutableListOf<AutofillId>()
        collectUsernameIds(structure, ids)
        if (ids.isEmpty() || Vault.accounts.isEmpty()) {
            callback.onSuccess(null); return
        }
        val response = FillResponse.Builder()
        Vault.accounts.forEach { acc ->
            val label = acc.bankName + (if (acc.loginId.isNotBlank()) " (" + acc.loginId + ")" else "")
            ids.forEach { id ->
                if (acc.loginId.isBlank()) return@forEach
                val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                    setTextViewText(android.R.id.text1, label)
                }
                val dataset = Dataset.Builder(presentation)
                    .setValue(id, AutofillValue.forText(acc.loginId), presentation)
                    .build()
                response.addDataset(dataset)
            }
        }
        callback.onSuccess(response.build())
    }

    private fun collectUsernameIds(structure: AssistStructure, out: MutableList<AutofillId>) {
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, out)
        }
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<AutofillId>) {
        val hints = node.autofillHints?.joinToString(" ")?.lowercase() ?: ""
        val id = node.autofillId
        val looksLikeUser = hints.contains("username") || hints.contains("email") ||
            node.hint?.contains("帳號") == true || node.idEntry?.contains("user") == true ||
            node.idEntry?.contains("login") == true || node.idEntry?.contains("id") == true
        if (id != null && looksLikeUser && node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
            out.add(id)
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // 刻意不儲存任何表單內容
        callback.onSuccess()
    }
}
