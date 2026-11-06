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
        private const val EXTRA_TAIL = "tail"
        private const val EXTRA_ACCTNO = "acctNo"
        private const val TAG = "BankFlow"
        private const val DETAIL_URL = "https://www.cathaybk.com.tw/OnlineBanking/AcctInq/B0103_TxnDtlInq"

        fun intent(
            ctx: Context,
            title: String,
            url: String,
            queryUrl: String,
            idNo: String = "",
            userCode: String = "",
            tail: String = "",
            acctNo: String = ""
        ): Intent =
            Intent(ctx, BankWebActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_QUERY, queryUrl)
                .putExtra(EXTRA_IDNO, idNo)
                .putExtra(EXTRA_UCODE, userCode)
                .putExtra(EXTRA_TAIL, tail)
                .putExtra(EXTRA_ACCTNO, acctNo)
    }

    private lateinit var web: WebView
    private lateinit var urlRow: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var statusView: TextView
    private var currentUrl: String = ""
    private var idNo: String = ""
    private var userCode: String = ""
    private var queryUrl: String = ""
    private var accountTail: String = ""
    private var bankAcctNo: String = ""
    private var profile: BankProfile? = null
    private var autoFilled = false
    private var autoRan = false
    private var flowRunning = false
    private lateinit var resultView: TextView
    private lateinit var progressView: android.widget.ProgressBar

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 已記住的銀行帳號（16 碼），存在本機 SharedPreferences */
    private fun storedAccts(): List<String> =
        getSharedPreferences("bank_accts", Context.MODE_PRIVATE)
            .getString("nums", "")?.split(",")?.map { it.trim() }?.filter { it.length >= 8 } ?: emptyList()

    private fun saveAccts(list: List<String>) {
        getSharedPreferences("bank_accts", Context.MODE_PRIVATE)
            .edit().putString("nums", list.joinToString(",")).apply()
    }

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
        accountTail = intent.getStringExtra(EXTRA_TAIL).orEmpty()
        bankAcctNo = intent.getStringExtra(EXTRA_ACCTNO).orEmpty()
        if (bankAcctNo.isNotBlank()) saveAccts(listOf(bankAcctNo))
        profile = BankProfiles.find(title)
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
                        if (!autoRan && storedAccts().isNotEmpty()) {
                            autoRan = true
                            post(1500) {
                                js("(function(){return document.querySelector('#CustID')?'LOGIN':'IN';})();") { r ->
                                    if (!r.contains("LOGIN") && !flowRunning) {
                                        status("已登入，自動抓取明細…")
                                        flowRunning = true
                                        resultView.visibility = View.GONE
                                        progressView.visibility = View.VISIBLE
                                        directFetch(storedAccts())
                                    }
                                }
                            }
                        }
                        if (!autoFilled && (idNo.isNotBlank() || userCode.isNotBlank())) {
                            autoFilled = true
                            post(500) { injectIdentifiers() }
                        }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    installNetSpy()
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
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun saveBase64(name: String, b64: String) {
                    try {
                        val dir = java.io.File(filesDir, "bank_downloads").apply { mkdirs() }
                        val f = java.io.File(dir, name.ifBlank { "bank_export.csv" })
                        f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                        runOnUiThread {
                            Toast.makeText(this@BankWebActivity, "已存到 " + f.absolutePath, Toast.LENGTH_LONG).show()
                            status("檔案已存：" + f.name)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "saveBase64 fail", e)
                    }
                }
                @android.webkit.JavascriptInterface
                fun onData(payload: String) {
                    Log.i(TAG, "DATA len=" + payload.length)
                    runOnUiThread { handleBankData(payload) }
                }
            }, "LedgerBridge")
            setDownloadListener(DownloadListener { url, userAgent, _, mimeType, _ ->
                if (url.startsWith("blob:")) {
                    val jsCode = "(function(){fetch(" + JSONObject.quote(url) + ")" +
                        ".then(function(r){return r.blob();})" +
                        ".then(function(b){var fr=new FileReader();fr.onload=function(){" +
                        "var s=fr.result;var i=s.indexOf(',');" +
                        "LedgerBridge.saveBase64('bank_export.csv', s.substring(i+1));};" +
                        "fr.readAsDataURL(b);})" +
                        ".catch(function(e){});return 1;})();"
                    js(jsCode) { status("⑥ blob 下載，轉存中…") }
                    return@DownloadListener
                }
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
        progressView = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        }
        resultView = TextView(this).apply {
            textSize = 13f
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
            setOnClickListener { if (!flowRunning) runAutoFlow() }
        }
        val spyBtn = Button(this).apply {
            text = "▶ 記錄銀行內部請求（診斷用）"
            textSize = 13f
            setBackgroundColor(0xFF37474F.toInt())
            setTextColor(AColor.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            setOnClickListener { dumpNetSpy() }
        }
        bottom.addView(progressView)
        bottom.addView(resultView)
        bottom.addView(statusView)
        bottom.addView(runBtn)
        val histBtn = Button(this).apply {
            text = "▶ 補抓歷史（分批）"
            textSize = 13f
            setTextColor(AColor.WHITE)
            setBackgroundColor(0xFF00796B.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            setOnClickListener { if (!flowRunning) runHistoryFlow() }
        }
        bottom.addView(histBtn)
        bottom.addView(spyBtn)
        root.addView(bottom)

        setContentView(root)
        web.loadUrl(normalize(startUrl))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    // ───────── 自動流程（優先：直接向銀行 API 取資料）─────────

    private fun runAutoFlow() {
        flowRunning = true
        resultView.visibility = View.GONE
        progressView.visibility = View.VISIBLE
        status("① 檢查登入狀態…")
        js("(function(){return document.querySelector('#CustID')?'LOGIN':'IN';})();") { r ->
            if (r.contains("LOGIN")) {
                finishFlow(false, "還沒登入\n請先輸入帳密並按「登入」，登入成功後再按一次")
            } else {
                val stored = storedAccts()
                if (stored.isNotEmpty()) {
                    status("② 用已記住的帳號 " + stored.size + " 個，直接向銀行取明細…")
                    directFetch(stored)
                } else {
                    status("② 找銀行帳號…")
                    scanAccountNumbers { nums ->
                        if (nums.isNotEmpty()) {
                            saveAccts(nums)
                            status("② 找到 " + nums.size + " 個帳號，直接向銀行取明細…")
                            directFetch(nums)
                        } else {
                            status("② 先跳到「臺幣帳戶明細」頁…")
                            load(DETAIL_URL)
                            post(3800) {
                                scanAccountNumbers { nums2 ->
                                    if (nums2.isNotEmpty()) {
                                        saveAccts(nums2)
                                        directFetch(nums2)
                                    } else {
                                        status("② 頁面上沒有帳號，開帳號下拉讀取…")
                                        openAccountDropdown()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 從頁面 HTML 找 accountNumber；找不到就回空 */
    private fun scanAccountNumbers(cb: (List<String>) -> Unit) {
        val script = "(function(){" +
            "var html=document.documentElement.innerHTML||'';" +
            "var key='\"accountNumber\":\"';var out=[];var seen={};var i=0;" +
            "while(true){var k=html.indexOf(key,i);if(k<0){break;}" +
            "var st=k+key.length;var en=st;" +
            "while(en<html.length){var c=html.charAt(en);if(c<'0'||c>'9'){break;}en++;}" +
            "if(en-st>=8){var v=html.substring(st,en);if(!seen[v]){seen[v]=1;out.push(v);}}" +
            "i=en>k?en:k+1;}" +
            "return out.join(',');})();"
        js(script) { res ->
            val list = res.split(",").map { it.trim() }.filter { it.length >= 8 }
            cb(list)
        }
    }

    private fun openAccountDropdown() {
        val script = "(function(){" +
            "var els=document.querySelectorAll('div,span,button,input,[role=combobox]');" +
            "for(var i=0;i<els.length;i++){" +
            "var t=((els[i].innerText||els[i].value||'')+'').trim();" +
            "if(t!=='請選擇'){continue;}" +
            "if(els[i].offsetParent===null){continue;}" +
            "try{els[i].click();}catch(e){e=null;}" +
            "return 'OPEN';}" +
            "return 'NOPH';})();"
        js(script) { res ->
            if (res.contains("OPEN")) {
                status("③ 已開帳號下拉，讀取帳號…")
                post(1300) {
                    val read = "(function(){" +
                        "var out=[];var els=document.querySelectorAll('*');" +
                        "for(var i=0;i<els.length;i++){var e=els[i];" +
                        "if(e.offsetParent===null){continue;}" +
                        "if(e.children.length>4){continue;}" +
                        "var t=(e.innerText||'').trim();" +
                        "if(!t||t.length>32){continue;}" +
                        "var j=0;while(j<t.length){var c=t.charAt(j);if(c<'0'||c>'9'){break;}j++;}" +
                        "if(j>=8){var v=t.substring(0,j);if(out.indexOf(v)<0){out.push(v);}}}" +
                        "return out.join(',');})();"
                    js(read) { r2 ->
                        val nums = r2.split(",").map { it.trim() }.filter { it.length >= 8 }
                        if (nums.isNotEmpty()) {
                            saveAccts(nums)
                            status("③ 讀到 " + nums.size + " 個帳號，直接取明細…")
                            directFetch(nums)
                        } else {
                            finishFlow(false, "找不到銀行帳號\n請確認停在「臺幣帳戶明細」頁")
                        }
                    }
                }
            } else {
                status("③ 這頁沒有下拉，先找「臺幣帳戶明細」…")
                findStatement()
            }
        }
    }

    private fun findStatement(attempt: Int = 1) {
        val script = "(function(){" +
            "var kw=['臺幣帳戶明細','帳戶明細','交易明細','存款明細','交易查詢','帳戶查詢','交易紀錄','明細查詢'];" +
            "var bad=['設定','新增','管理','說明','申請','連結','更多'];" +
            "function pick(sel){" +
            "var nodes=document.querySelectorAll(sel);var best=null,bs=-1,bt='';" +
            "for(var i=0;i<nodes.length;i++){var e=nodes[i];" +
            "if(e.offsetParent===null){continue;}" +
            "if(e.children.length>3){continue;}" +
            "var t=(e.innerText||e.textContent||'').trim();" +
            "if(!t||t.length>20){continue;}" +
            "var skip=false;for(var b2=0;b2<bad.length;b2++){if(t.indexOf(bad[b2])>=0){skip=true;break;}}" +
            "if(skip){continue;}" +
            "for(var k=0;k<kw.length;k++){var sc=-1;" +
            "if(t===kw[k]){sc=100-k;}else if(t.indexOf(kw[k])>=0&&t.length<=12){sc=50-k;}" +
            "if(sc>bs){bs=sc;best=e;bt=t;}}}" +
            "if(best){try{best.scrollIntoView({block:'center'});}catch(e2){}best.click();return 'CLICK:'+bt;}" +
            "return null;}" +
            "var r=pick('a,button,[role=button]');if(r){return r;}" +
            "r=pick('li,div,span,p,td,dd');if(r){return r;}" +
            "return 'NOTFOUND';})();"
        js(script) { res ->
            if (res.contains("CLICK:")) {
                status("③ 已開「" + res.substringAfter("CLICK:") + "」，等頁面…")
                post(2600) { openAccountDropdown() }
            } else if (attempt < 5) {
                post(1600) { findStatement(attempt + 1) }
            } else {
                finishFlow(false, "找不到「交易明細／帳戶查詢」連結\n請先手動點進明細頁，再按一次按鈕")
            }
        }
    }

    /** 用登入中的 session 直接呼叫銀行 API：取 JWT → 帶 Authorization 取明細 */
    private fun directFetch(rawNums: List<String>) {
        val accts = rawNums.map { it.padStart(16, '0') }.distinct()
        val pf = profile
        if (pf == null || !pf.ready) {
            finishFlow(false, "「" + title + "」還沒建檔，無法直接抓資料")
            return
        }
        val jwtPath = pf.jwtPath.ifBlank { "" }
        val detailPath = pf.detailPath
        val cust = idNo.ifBlank { "" }
        if (cust.isBlank()) {
            finishFlow(false, "缺少身分證字號（API 需要）\n請在帳戶資料填上身分證字號")
            return
        }
        val jsParts = mutableListOf<String>()
        jsParts.add("(function(){")
        jsParts.add("var cust=" + JSONObject.quote(cust) + ";")
        jsParts.add("var accts=" + org.json.JSONArray(accts).toString() + ";")
        jsParts.add("function p2(n){return (n<10?'0':'')+n;}")
        jsParts.add("function seq(){var d=new Date();var ts=''+d.getFullYear()+p2(d.getMonth()+1)+p2(d.getDate())+p2(d.getHours())+p2(d.getMinutes())+p2(d.getSeconds());var h='';for(var i=0;i<32;i++){h+='0123456789abcdef'.charAt(Math.floor(Math.random()*16));}return ts+h.substring(0,8)+'-'+h.substring(8,12)+'-'+h.substring(12,16)+'-'+h.substring(16,20)+'-'+h.substring(20,32);}")
        jsParts.add("var now=new Date();var end=''+now.getFullYear()+'-'+p2(now.getMonth()+1)+'-'+p2(now.getDate());")
        jsParts.add("var s0=new Date(now.getTime()-89*86400000);var start=''+s0.getFullYear()+'-'+p2(s0.getMonth()+1)+'-'+p2(s0.getDate());")
        jsParts.add("function pickJwt(){try{var st='';try{st=window.sessionStorage;}catch(e){}if(!st){return '';}for(var i=0;i<st.length;i++){var v=st.getItem(st.key(i));if(v&&v.length>60&&v.indexOf('eyJ')===0&&v.split('.').length===3){return v;}}}catch(e){}return '';}")
        jsParts.add("function run(jwt){var results=[];var pending=accts.length;")
        jsParts.add("function done(){if(pending<=0){LedgerBridge.onData(JSON.stringify({accounts:results,jwtLen:jwt.length}));}}")
        jsParts.add("for(var i=0;i<accts.length;i++){(function(acc){")
        jsParts.add("fetch(" + JSONObject.quote(detailPath) + ",{method:'POST',credentials:'include',")
        jsParts.add("headers:{'Content-Type':'application/json','Accept':'application/json, text/plain, */*','Authorization':'Bearer '+jwt},")
        jsParts.add("body:JSON.stringify({functionSeqNo:seq(),content:{customerId:cust,queryFilters:[{accountNumber:acc,startDate:start,endDate:end}]}})")
        jsParts.add("}).then(function(r){var st=r.status;return r.text().then(function(txx){var j=null;try{j=JSON.parse(txx);}catch(e2){j={_raw:txx.substring(0,200)};}return {st:st,j:j};});})")
        jsParts.add(".then(function(o){results.push({acct:acc,status:o.st,resp:o.j});pending--;done();})")
        jsParts.add(".catch(function(e){results.push({acct:acc,error:String(e)});pending--;done();});})(accts[i]);}}")
        if (jwtPath.isBlank()) { jsParts.add("run('');return 'NOJWTNEEDED';})();"); web.evaluateJavascript(jsParts.joinToString(""), null); status("④ 直接呼叫 API…"); return }
        jsParts.add("var cached=pickJwt();if(cached){run(cached);return 'CACHED';}")
        jsParts.add("var cached=pickJwt();if(cached){run(cached);return 'CACHED';}")
        if (jwtPath.isNotBlank()) jsParts.add("fetch(" + JSONObject.quote(jwtPath) + ",{method:'POST',credentials:'include',headers:{'Content-Type':'application/json','Accept':'application/json, text/plain, */*'},body:'{}'})")
        jsParts.add(".then(function(r){return r.text();})")
        jsParts.add(".then(function(txt){var jwt='';try{var o=JSON.parse(txt);jwt=(o&&o.Data&&o.Data.JwtToken)?o.Data.JwtToken:'';}catch(e){}")
        jsParts.add("if(!jwt){LedgerBridge.onData(JSON.stringify({accounts:[],error:'NOJWT:'+txt.substring(0,160)}));return;}run(jwt);})")
        jsParts.add(".catch(function(e){LedgerBridge.onData(JSON.stringify({accounts:[],error:'JWTFAIL:'+String(e)}));});")
        jsParts.add("return 'STARTED';})();")
        web.evaluateJavascript(jsParts.joinToString(""), null)
        status("④ 取得 JWT 並呼叫 API…")
    }

    /** 分批補抓歷史（從帳本最早一筆或 2020-01-01 到今天，每段 89 天） */
    private fun runHistoryFlow() {
        flowRunning = true
        resultView.visibility = View.GONE
        progressView.visibility = View.VISIBLE
        status("① 準備補抓歷史…")
        val earliest = Store.txs.minOfOrNull { it.dateMillis }?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            ?: java.time.LocalDate.of(2020, 1, 1)
        val from = earliest.withDayOfMonth(1)
        val today = java.time.LocalDate.now()
        val chunks = mutableListOf<Pair<String, String>>()
        var cur = from
        while (cur.isBefore(today) || cur.isEqual(today)) {
            val end = cur.plusDays(88).let { if (it.isAfter(today)) today else it }
            chunks.add(cur.toString() to end.toString())
            cur = end.plusDays(1)
        }
        status("① 共 " + chunks.size + " 段（" + from + " ~ " + today + "）")
        val nums = storedAccts()
        if (nums.isEmpty()) {
            finishFlow(false, "沒有記住銀行帳號\n請先按「自動查詢明細」成功一次")
            return
        }
        directFetchChunks(nums.map { it.padStart(16, '0') }.distinct(), chunks)
    }

    /** 依指定區間陣列，分批呼叫 API 後一次匯入 */
    private fun directFetchChunks(acctsIn: List<String>, chunks: List<Pair<String, String>>) {
        val pf = profile
        if (pf == null || !pf.ready) { finishFlow(false, "「" + title + "」還沒建檔，無法批次抓取"); return }
        val detailPath = pf.detailPath
        val jwtPath = pf.jwtPath
        val cust = idNo.ifBlank { "" }
        if (cust.isBlank()) { finishFlow(false, "缺少身分證字號，無法呼叫 API"); return }
        val jl = mutableListOf<String>()
        jl.add("(function(){")
        jl.add("var cust=" + JSONObject.quote(cust) + ";")
        jl.add("var acc=" + JSONObject.quote(acctsIn.firstOrNull() ?: "") + ";")
        jl.add("var chunks=" + org.json.JSONArray(chunks.map { listOf(it.first, it.second) }).toString() + ";")
        jl.add("function p2(n){return (n<10?'0':'')+n;}")
        jl.add("function seq(){var d=new Date();var ts=''+d.getFullYear()+p2(d.getMonth()+1)+p2(d.getDate())+p2(d.getHours())+p2(d.getMinutes())+p2(d.getSeconds());var h='';for(var i=0;i<32;i++){h+='0123456789abcdef'.charAt(Math.floor(Math.random()*16));}return ts+h.substring(0,8)+'-'+h.substring(8,12)+'-'+h.substring(12,16)+'-'+h.substring(16,20)+'-'+h.substring(20,32);}")
        jl.add("function pickJwt(){try{var st=window.sessionStorage;for(var i=0;i<st.length;i++){var v=st.getItem(st.key(i));if(v&&v.length>60&&v.indexOf('eyJ')===0&&v.split('.').length===3){return v;}}}catch(e){}return '';}")
        jl.add("function run(jwt){var results=[];var idx=0;")
        jl.add("function next(){if(idx>=chunks.length){LedgerBridge.onData(JSON.stringify({accounts:results}));return;}var c=chunks[idx++];")
        jl.add("fetch(" + JSONObject.quote(detailPath) + ",{method:'POST',credentials:'include',")
        jl.add("headers:{'Content-Type':'application/json','Accept':'application/json, text/plain, */*','Authorization':'Bearer '+jwt},")
        jl.add("body:JSON.stringify({functionSeqNo:seq(),content:{customerId:cust,queryFilters:[{accountNumber:acc,startDate:c[0],endDate:c[1]}]}})")
        jl.add("}).then(function(r){var st=r.status;return r.text().then(function(t){var j=null;try{j=JSON.parse(t);}catch(e){j={_raw:t.substring(0,120)};}return {st:st,j:j};});})")
        jl.add(".then(function(o){results.push({acct:acc,status:o.st,resp:o.j});next();})")
        jl.add(".catch(function(e){results.push({acct:acc,error:String(e)});next();});}")
        jl.add("next();}")
        jl.add("var cached=pickJwt();if(cached){run(cached);return 'CACHED';}")
        jl.add("fetch(" + JSONObject.quote(jwtPath) + ",{method:'POST',credentials:'include',headers:{'Content-Type':'application/json','Accept':'application/json, text/plain, */*'},body:'{}'})")
        jl.add(".then(function(r){return r.text();}).then(function(txt){var jwt='';try{var o=JSON.parse(txt);jwt=(o&&o.Data&&o.Data.JwtToken)?o.Data.JwtToken:'';}catch(e){}")
        jl.add("if(!jwt){LedgerBridge.onData(JSON.stringify({accounts:[],error:'NOJWT:'+txt.substring(0,120)}));return;}run(jwt);})")
        jl.add(".catch(function(e){LedgerBridge.onData(JSON.stringify({accounts:[],error:'JWTFAIL:'+String(e)}));});")
        jl.add("return 'STARTED';})();")
        web.evaluateJavascript(jl.joinToString(""), null)
        status("② 分批抓取中…")
    }

    /** API 回應 → 匯入帳本（自動略過重複） */
    private fun handleBankData(payload: String) {
        var pos0 = 0
        var n0 = 0
        while (pos0 < payload.length && n0 < 6) {
            val end0 = minOf(pos0 + 2000, payload.length)
            Log.i(TAG, "PAYLOAD#" + n0 + "#" + payload.substring(pos0, end0))
            pos0 = end0
            n0++
        }
        runCatching {
            Store.init(applicationContext)
            val root = org.json.JSONObject(payload)
            val arr = root.optJSONArray("accounts") ?: org.json.JSONArray()
            val rows = mutableListOf<Tx>()
            var total = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val resp = o.optJSONObject("resp")
                if (resp != null) {
                    val text = resp.toString()
                    var pos = 0
                    var nth = 0
                    while (pos < text.length && nth < 4) {
                        val end = minOf(pos + 2200, text.length)
                        Log.i(TAG, "APIRESP#" + nth + "#" + text.substring(pos, end))
                        pos = end
                        nth++
                    }
                }
                val datas = resp?.optJSONObject("content")?.optJSONArray("datas")
                    ?: o.optJSONArray("datas") ?: continue
                for (j in 0 until datas.length()) {
                    val d = datas.getJSONObject(j)
                    val details = d.optJSONArray("details") ?: continue
                    for (k in 0 until details.length()) {
                        val t = details.getJSONObject(k)
                        val dateStr = t.optString("accountDate").ifBlank { t.optString("txnDateTime") }
                        val date = runCatching { java.time.LocalDate.parse(dateStr.take(10)) }.getOrNull() ?: continue
                        val inc = if (t.isNull("incomeAmt")) 0.0 else t.optDouble("incomeAmt", 0.0)
                        val exp = if (t.isNull("expendAmt")) 0.0 else t.optDouble("expendAmt", 0.0)
                        val amount = if (inc > 0.0) inc else exp
                        val cents = Math.round(amount * 100)
                        if (cents <= 0) continue
                        total++
                        val kind = if (inc > 0.0) Kind.INCOME else Kind.EXPENSE
                        val note = t.optString("description").ifBlank { "網銀" }
                        rows.add(
                            Tx(
                                id = 0,
                                accountId = 0,
                                cents = cents,
                                kind = kind,
                                category = "尚未分類",
                                note = note,
                                dateMillis = date.toMillis()
                            )
                        )
                    }
                }
            }
            if (rows.isEmpty()) {
                finishFlow(false, "API 有回應但沒有交易資料\n技術：" + payload.take(150))
                return
            }
            var target = Store.accounts.firstOrNull { it.name == "國泰世華" }
            if (target == null) {
                Store.addAccount("國泰世華")
                target = Store.accounts.firstOrNull { it.name == "國泰世華" }
            }
            val accId = target?.id ?: 1L
            var next = Store.newId()
            val withIds = rows.map { it.copy(id = next++, accountId = accId) }
            val added = Store.addImported(withIds)
            finishFlow(true, "抓回 " + total + " 筆交易，新增 " + added + " 筆到帳本「國泰世華」\n（重複的 " + (total - added) + " 筆自動略過）")
        }.onFailure {
            finishFlow(false, "匯入失敗：" + (it.message ?: "未知錯誤"))
        }
    }

    /** 流程結束回饋：綠色成功／紅色失敗，同時 Toast 與 log */
    private fun finishFlow(ok: Boolean, message: String) {
        flowRunning = false
        progressView.visibility = View.GONE
        resultView.apply {
            text = (if (ok) "\u2705 完成\n" else "\u26a0\ufe0f 失敗\n") + message
            setTextColor(if (ok) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt())
            setBackgroundColor(if (ok) 0xFFE8F5E9.toInt() else 0xFFFFEBEE.toInt())
            visibility = View.VISIBLE
        }
        status("（流程結束）" + if (ok) "成功" else "失敗")
        Toast.makeText(this, if (ok) "自動查詢完成" else "自動查詢失敗，請看畫面說明", Toast.LENGTH_LONG).show()
        Log.i(TAG, "RESULT ok=" + ok + " " + message.replace("\n", " / "))
    }


    /** 側錄銀行頁面發出的 XHR／fetch（只記錄，不修改內容） */
    private fun installNetSpy() {
        val spy = "(function(){" +
            "if(window.__DSH_SPY){return 'ALREADY';}window.__DSH_SPY=1;window.__DSH=[];" +
            "var of=window.fetch;" +
            "if(of){window.fetch=function(input,init){" +
            "var u=(typeof input==='string')?input:(input&&input.url?input.url:'');" +
            "var hh='';try{if(init&&init.headers){var H=init.headers;if(typeof H.forEach==='function'){H.forEach(function(v,k){hh+=k+':'+v+';';});}else{hh=JSON.stringify(H);}}}catch(e){}" +
            "var r={url:String(u),body:(init&&init.body)?String(init.body).slice(0,400):'',text:'',status:0,headers:hh.slice(0,400)};" +
            "window.__DSH.push(r);" +
            "return of.apply(this,arguments).then(function(resp){r.status=resp.status;" +
            "try{resp.clone().text().then(function(t){r.text=String(t).slice(0,2000);}).catch(function(){});}catch(e){}" +
            "return resp;});};}" +
            "var op=XMLHttpRequest.prototype.open;var sd=XMLHttpRequest.prototype.send;var srh=XMLHttpRequest.prototype.setRequestHeader;" +
            "XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{if(this.__hdrs){this.__hdrs.push(k+':'+v);if(this.__dsh){this.__dsh.headers=this.__hdrs.join(';').slice(0,400);}}}catch(e){}return srh.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__dsh={url:String(u),body:'',text:'',status:0,headers:''};this.__hdrs=[];window.__DSH.push(this.__dsh);return op.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.send=function(b){var self=this;" +
            "try{if(self.__dsh){self.__dsh.body=b?String(b).slice(0,400):'';" +
            "self.addEventListener('load',function(){try{self.__dsh.status=self.status;self.__dsh.text=String(self.responseText||'').slice(0,2000);}catch(e){}});}}" +
            "catch(e){}" +
            "return sd.apply(self,arguments);};" +
            "return 'SPYON';})();"
        web.evaluateJavascript(spy, null)
    }


    /** 把側錄到的請求寫進 log */
    private fun dumpNetSpy() {
        web.evaluateJavascript("(function(){try{return 'N='+((window.__DSH||[]).length)+'::'+JSON.stringify((window.__DSH||[]).slice(-8));}catch(e){return 'ERR';}})();") { raw ->
            val res = raw ?: "EMPTY"
            Log.i(TAG, "SPYLEN=" + res.length)
            var i = 0
            var chunk = 0
            while (i < res.length) {
                val end = minOf(i + 2600, res.length)
                Log.i(TAG, "SPY#" + chunk + "#" + res.substring(i, end))
                i = end
                chunk++
            }
            status("已記錄 " + res.length + " 字元")
            Toast.makeText(this, "已記錄，可以告訴我了", Toast.LENGTH_SHORT).show()
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
