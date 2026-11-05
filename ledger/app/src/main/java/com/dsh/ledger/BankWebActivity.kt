package com.dsh.ledger

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject

/**
 * 網銀操作視窗（獨立 Activity，不經 Compose，鍵盤與自動填入才正常）
 * - 帳密與 OTP 一律由使用者本人輸入；App 只帶入「身分證字號、用戶代號」
 * - 下方一顆【自動查詢明細】按鈕：按下後自動導航到明細頁、選條件、按查詢、找匯出
 */
class BankWebActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_IDNO = "idNo"
        private const val EXTRA_UCODE = "userCode"
        private const val TAG = "BankFlow"

        fun intent(
            ctx: Context,
            title: String,
            url: String,
            queryUrl: String,
            idNo: String = "",
            userCode: String = ""
        ): Intent =
            Intent(ctx, BankWebActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_QUERY, queryUrl)
                .putExtra(EXTRA_IDNO, idNo)
                .putExtra(EXTRA_UCODE, userCode)
    }

    private lateinit var web: WebView
    private lateinit var urlRow: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var statusView: TextView
    private var currentUrl: String = ""
    private var idNo: String = ""
    private var userCode: String = ""
    private var queryUrl: String = ""
    private var autoFilled = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun status(text: String) {
        statusView.text = text
        Log.i(TAG, text)
    }

    private fun js(script: String, cb: (String) -> Unit) {
        web.evaluateJavascript(script) { raw -> cb(raw?.replace("\"", "") ?: "") }
    }

    private fun post(ms: Long, block: () -> Unit) {
        Handler(mainLooper).postDelayed({ block() }, ms)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { "https://www.google.com" }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "網銀" }
        queryUrl = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        idNo = intent.getStringExtra(EXTRA_IDNO).orEmpty()
        userCode = intent.getStringExtra(EXTRA_UCODE).orEmpty()
        currentUrl = startUrl

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(AColor.WHITE)
        }

        // ── 頂列 ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF00695C.toInt())
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(AColor.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { if (web.canGoBack()) web.goBack() else finish() }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(AColor.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun barButton(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(AColor.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }
        bar.addView(back)
        bar.addView(titleView)
        if (idNo.isNotBlank() || userCode.isNotBlank()) {
            bar.addView(barButton("帶入") { injectIdentifiers() })
        }
        bar.addView(barButton("網址") {
            urlRow.visibility = if (urlRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        bar.addView(barButton("Chrome") {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply { setPackage("com.android.chrome") })
            }.onFailure { Toast.makeText(this, "找不到 Chrome", Toast.LENGTH_SHORT).show() }
        })
        root.addView(bar)

        // ── 網址列（預設收起）──
        urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
        urlInput = EditText(this).apply {
            setText(startUrl)
            setSingleLine(true)
            textSize = 12f
            hint = "貼上銀行網址"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlRow.addView(urlInput)
        urlRow.addView(TextView(this).apply {
            text = "前往"
            textSize = 13f
            setTextColor(0xFF00695C.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { load(normalize(urlInput.text.toString())) }
        })
        root.addView(urlRow)

        // ── WebView ──
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = true
            settings.loadsImagesAutomatically = true
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url != "about:blank") {
                        currentUrl = url
                        urlInput.setText(url)
                        if (!autoFilled && (idNo.isNotBlank() || userCode.isNotBlank())) {
                            autoFilled = true
                            post(500) { injectIdentifiers() }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        view?.postDelayed({ view.reload() }, 600)
                    }
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setDownloadListener(DownloadListener { url, userAgent, _, mimeType, _ ->
                runCatching {
                    val req = DownloadManager.Request(Uri.parse(url))
                    req.setMimeType(mimeType)
                    req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    req.addRequestHeader("User-Agent", userAgent)
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        Uri.parse(url).lastPathSegment ?: "bank_download"
                    )
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this@BankWebActivity, "已開始下載，完成後可在「設定 → 匯入」加入帳本", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this@BankWebActivity, "下載失敗：${it.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }
        root.addView(web)

        // ── 底部：狀態 + 自動查詢按鈕 ──
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F3F4.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        statusView = TextView(this).apply {
            text = "登入後按下面的按鈕，我會自動跳到交易明細並查詢"
            textSize = 12f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        val runBtn = Button(this).apply {
            text = "▶ 自動查詢明細"
            textSize = 15f
            setTextColor(AColor.WHITE)
            setBackgroundColor(0xFF00695C.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            setOnClickListener { runAutoFlow() }
        }
        bottom.addView(statusView)
        bottom.addView(runBtn)
        root.addView(bottom)

        setContentView(root)
        web.loadUrl(normalize(startUrl))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    // ───────── 自動流程 ─────────

    private fun runAutoFlow() {
        status("① 檢查登入狀態…")
        js("(function(){return document.querySelector('#CustID')?'LOGIN':'IN';})();") { r ->
            if (r.contains("LOGIN")) {
                status("還沒登入：請先輸入帳密並按「登入」，登入後再按這顆")
            } else {
                status("② 找「交易明細／帳戶查詢」連結…")
                findStatement()
            }
        }
    }

    private fun findStatement(attempt: Int = 1) {
        val script = "(function(){" +
            "var kw=['臺幣帳戶明細','帳戶明細','交易明細','存款明細','交易查詢','帳戶查詢','交易紀錄','明細查詢'];" +
            "var bad=['設定','新增','管理','說明','申請','連結','更多'];" +
            "var nodes=document.querySelectorAll('a,button,[role=button]');" +
            "var best=null,bestScore=-1,bestText='';" +
            "for(var i=0;i<nodes.length;i++){" +
            "var t=(nodes[i].innerText||nodes[i].textContent||'').trim();" +
            "if(!t||t.length>20){continue;}" +
            "var skip=false;for(var b=0;b<bad.length;b++){if(t.indexOf(bad[b])>=0){skip=true;break;}}" +
            "if(skip){continue;}" +
            "for(var k=0;k<kw.length;k++){" +
            "var sc=-1;if(t===kw[k]){sc=100-k;}else if(t.indexOf(kw[k])>=0){sc=50-k;}" +
            "if(sc>bestScore){bestScore=sc;best=nodes[i];bestText=t;}}}" +
            "if(best){try{best.scrollIntoView({block:'center'});}catch(e){}best.click();return 'CLICK:'+bestText;}" +
            "return 'NOTFOUND';})();"
        js(script) { res ->
            if (res.contains("CLICK:")) {
                status("③ 已開「" + res.substringAfter("CLICK:") + "」，等頁面…")
                post(2600) { openFirstDropdown() }
            } else if (attempt < 4) {
                post(1500) { findStatement(attempt + 1) }
            } else {
                status("✗ 找不到明細連結：請手動點進明細頁，再按一次按鈕")
            }
        }
    }

    /** 點開第一個「請選擇」下拉，並把選項結構寫進 log */
    private fun openFirstDropdown() {
        val script = "(function(){" +
            "var out=[];" +
            "var els=document.querySelectorAll('div,span,button,input,select,[role=combobox]');" +
            "var ph=null;" +
            "for(var i=0;i<els.length;i++){" +
            "var t=((els[i].innerText||els[i].value||'')+'').trim();" +
            "if(t==='請選擇'){ph=els[i];break;}}" +
            "out.push('FOUND_PLACEHOLDER='+(ph?'YES':'NO'));" +
            "if(ph){try{ph.click();}catch(e){out.push('CLICKERR');}}" +
            "var cands=[];" +
            "var all=document.querySelectorAll('li,[role=option],[class*=option],[class*=Option],[class*=item],[class*=Item],[class*=list],[class*=List]');" +
            "for(var j=0;j<all.length&&cands.length<40;j++){" +
            "var t2=(all[j].innerText||'').trim();" +
            "if(t2&&t2.length<=28&&cands.indexOf(t2)<0){cands.push(t2);}}" +
            "out.push('CANDS='+cands.join('|'));" +
            "return out.join(' || ');})();"
        js(script) { res ->
            Log.i(TAG, "dropdown " + res)
            status("④ 下拉：" + res.take(150))
        }
    }

    private fun fillQueryForm(attempt: Int = 1) {
        val script = "(function(){" +
            "var out=[];" +
            "var sels=document.querySelectorAll('select');" +
            "out.push('SELECTS='+sels.length);" +
            "for(var i=0;i<sels.length;i++){var s=sels[i];var opts=[];" +
            "for(var j=0;j<Math.min(s.options.length,10);j++){opts.push((s.options[j].text||'').trim());}" +
            "if(s.options.length>1){s.selectedIndex=1;s.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "out.push('['+i+']'+(s.name||s.id||'?')+'='+(s.value||'')+'/opts:'+opts.join('|'));}" +
            "var all=document.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit]');" +
            "var names=[];var target=null;" +
            "for(var k=0;k<all.length;k++){var t=(all[k].innerText||all[k].value||'').trim();" +
            "if(!t||t.length>14){continue;}names.push(t);" +
            "if(!target&&(t==='查詢'||t==='確定查詢')){target=all[k];}}" +
            "out.push('BTNS='+names.join(','));" +
            "if(target){try{target.scrollIntoView({block:'center'});}catch(e){}target.click();out.push('CLICKED=查詢');}" +
            "return out.join(' || ');})();"
        js(script) { res ->
            Log.i(TAG, "scan" + attempt + " " + res)
            val clicked = res.contains("CLICKED=查詢")
            if (clicked) {
                status("④ 已按「查詢」，等結果…")
                post(4500) { probeExport(1) }
            } else if (attempt < 3) {
                status("④ 選單還沒出現，重試…")
                post(1800) { fillQueryForm(attempt + 1) }
            } else {
                status("✗ 找不到「查詢」按鈕：請手動選條件。頁面元件：" + res.take(120))
            }
        }
    }

    private fun probeExport(attempt: Int) {
        val script = "(function(){" +
            "var kw=['匯出','下載','CSV','csv','Excel','EXCEL','存檔','列印'];" +
            "var all=document.querySelectorAll('button,a,[role=button],input[type=button]');var hit=null;var names=[];" +
            "for(var i=0;i<all.length;i++){var t=(all[i].innerText||all[i].value||'').trim();" +
            "if(!t||t.length>16){continue;}for(var k=0;k<kw.length;k++){if(t.indexOf(kw[k])>=0){names.push(t);if(!hit){hit=all[i];}break;}}}" +
            "if(hit){try{hit.scrollIntoView({block:'center'});}catch(e){}hit.click();return 'CLICK:'+names.join(',');}" +
            "return 'NONE:'+names.length;})();"
        js(script) { res ->
            Log.i(TAG, "export" + attempt + " " + res)
            if (res.contains("CLICK:")) {
                status("⑤ 已按「" + res.substringAfter("CLICK:") + "」，檔案會下載到 Download")
            } else if (attempt < 4) {
                post(2500) { probeExport(attempt + 1) }
            } else {
                status("⑤ 這頁沒有匯出／下載按鈕，需要手動查看")
            }
        }
    }

    /** 帶入身分證字號與用戶代號（不含密碼） */
    private fun injectIdentifiers(attempt: Int = 1) {
        if (idNo.isBlank() && userCode.isBlank()) return
        val script = "(function(){" +
            "var id=" + JSONObject.quote(idNo) + ", uc=" + JSONObject.quote(userCode) + ";" +
            "function setv(el,val){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
            "if(d&&d.set){d.set.call(el,val);}else{el.value=val;}" +
            "el.focus();" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));" +
            "el.dispatchEvent(new Event('blur',{bubbles:true}));}" +
            "function find(sels){for(var i=0;i<sels.length;i++){var e=document.querySelector(sels[i]);if(e){return e;}}return null;}" +
            "var out=[];" +
            "var a=find(['#CustID','input[name=CustID]','input[name=CustId]','input[id*=Cust]']);" +
            "if(!a){out.push('NOID');}else if(id&&(!a.value||a.value.indexOf('*')>=0)){setv(a,id);out.push('ID');}else{out.push('IDSET');}" +
            "var b=find(['#UserIdKeyin','input[name=UserIdKeyin]','input[id*=UserId]']);" +
            "if(!b){out.push('NOCODE');}else if(uc&&!b.value){setv(b,uc);out.push('CODE');}else{out.push('CODESET');}" +
            "return out.join(',');})();"
        js(script) { res ->
            Log.i(TAG, "inject" + attempt + " " + res)
            val missing = res.contains("NOID") || res.contains("NOCODE")
            if (missing && attempt < 6) {
                post(1200) { injectIdentifiers(attempt + 1) }
            } else if (!autoFilled) {
                autoFilled = true
            }
        }
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return t
        return if (t.contains("://")) t else "https://$t"
    }

    private fun load(url: String) {
        if (url.isBlank()) return
        currentUrl = url
        web.loadUrl(url)
    }

    override fun onDestroy() {
        runCatching {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
