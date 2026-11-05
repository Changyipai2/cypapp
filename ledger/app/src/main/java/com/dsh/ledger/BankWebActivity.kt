package com.dsh.ledger

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
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
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.app.KeyguardManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject

/**
 * 網銀瀏覽器（獨立 Activity，不經過 Compose，確保點輸入框一定叫得出鍵盤）
 * - 全螢幕，頂列只有一行
 * - 網址列預設收起
 * - 帳密與 OTP 由你本人輸入，App 不讀取、不儲存
 */
class BankWebActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_IDNO = "idNo"
        private const val EXTRA_UCODE = "userCode"

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
    private var currentUrl: String = ""
    private var idNo: String = ""
    private var userCode: String = ""
    private var autoFilled = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { "https://www.google.com" }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "網銀" }
        val queryUrl = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        idNo = intent.getStringExtra(EXTRA_IDNO).orEmpty()
        userCode = intent.getStringExtra(EXTRA_UCODE).orEmpty()
        currentUrl = startUrl

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(AColor.WHITE)
        }

        // ── 頂列（48dp）──
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
        val urlToggle = TextView(this).apply {
            text = "網址"
            textSize = 13f
            setTextColor(AColor.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                urlRow.visibility = if (urlRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        val chromeBtn = TextView(this).apply {
            text = "Chrome"
            textSize = 13f
            setTextColor(AColor.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                            setPackage("com.android.chrome")
                        }
                    )
                }.onFailure { Toast.makeText(this@BankWebActivity, "找不到 Chrome", Toast.LENGTH_SHORT).show() }
            }
        }
        bar.addView(back)
        bar.addView(titleView)
        if (idNo.isNotBlank() || userCode.isNotBlank()) {
            val fillBtn = TextView(this).apply {
                text = "帶入"
                textSize = 13f
                setTextColor(AColor.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { injectIdentifiers() }
            }
            bar.addView(fillBtn)
        }
        if (queryUrl.isNotBlank()) {
            val queryBtn = TextView(this).apply {
                text = "查詢"
                textSize = 13f
                setTextColor(AColor.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    web.loadUrl(queryUrl)
                    currentUrl = queryUrl
                    urlInput.setText(queryUrl)
                }
            }
            bar.addView(queryBtn)
        }
        bar.addView(urlToggle)
        bar.addView(chromeBtn)
        root.addView(bar)

        // ── 網址列（預設收起）──
        urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        urlInput = EditText(this).apply {
            setText(startUrl)
            setSingleLine(true)
            textSize = 12f
            hint = "貼上銀行登入網址"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val goBtn = TextView(this).apply {
            text = "前往"
            textSize = 13f
            setTextColor(0xFF00695C.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { load(normalize(urlInput.text.toString())) }
        }
        urlRow.addView(urlInput)
        urlRow.addView(goBtn)
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
                            Handler(mainLooper).postDelayed({ injectIdentifiers() }, 500)
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // 主文件偶發 ERR_CACHE_MISS：自動重試一次
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
        setContentView(root)

        web.loadUrl(normalize(startUrl))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    private fun injectIdentifiers(attempt: Int = 1) {
        if (idNo.isBlank() && userCode.isBlank()) return
        val js = "(function(){" +
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
            "return out.join(',');" +
            "})();"
        web.evaluateJavascript(js) { raw ->
            val res = raw?.replace("\"", "") ?: ""
            Log.i("BankFill", "attempt=" + attempt + " result=" + res)
            val missing = res.contains("NOID") || res.contains("NOCODE")
            if (missing && attempt < 6) {
                Handler(mainLooper).postDelayed({ injectIdentifiers(attempt + 1) }, 1200)
            } else {
                val msg = when {
                    res.contains("NOID") && res.contains("NOCODE") -> "找不到登入欄位，請按「帶入」重試"
                    res.contains("NOID") -> "已帶入用戶代號（找不到身分證字號欄位）"
                    res.contains("NOCODE") -> "已帶入身分證字號（找不到用戶代號欄位）"
                    else -> "已帶入身分證字號與用戶代號（密碼請自行輸入）"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
