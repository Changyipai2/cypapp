@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dsh.ledger

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * App 內建網銀瀏覽器
 * - 頂列只有一行（48dp）；網址列預設收起，按「網址」才展開，把空間留給銀行頁面
 * - 帳密與 OTP 由你本人輸入（App 不讀取、不儲存）
 * - 內建瀏覽器若打不開，可按「Chrome」改用系統瀏覽器（鍵盤與自動填入最相容）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BankWebScreen(
    title: String,
    startUrl: String,
    queryUrl: String?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showUrlBar by remember { mutableStateOf(false) }
    var urlBar by remember { mutableStateOf(startUrl) }
    var userTyping by remember { mutableStateOf(false) }
    val retryFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return t
        return if (t.contains("://")) t else "https://$t"
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 精簡頂列（48dp）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
            }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White) }
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!queryUrl.isNullOrBlank()) {
                TextButton(onClick = { webView?.loadUrl(queryUrl) }) {
                    Text("查詢", color = Color.White, fontSize = 12.sp)
                }
            }
            TextButton(onClick = {
                showUrlBar = !showUrlBar
                if (!showUrlBar) {
                    userTyping = false
                    focusManager.clearFocus()
                }
            }) { Text("網址", color = Color.White, fontSize = 12.sp) }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalize(urlBar))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        setPackage("com.android.chrome")
                    })
                }.onFailure {
                    Toast.makeText(context, "找不到 Chrome", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Chrome", color = Color.White, fontSize = 12.sp) }
        }

        // ── 網址列：預設收起 ──
        if (showUrlBar) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = urlBar,
                    onValueChange = { urlBar = it; userTyping = true },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    placeholder = { Text("貼上銀行登入網址", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        userTyping = false
                        focusManager.clearFocus()
                        webView?.loadUrl(normalize(urlBar))
                    })
                )
                TextButton(onClick = {
                    userTyping = false
                    focusManager.clearFocus()
                    webView?.loadUrl(normalize(urlBar))
                }) { Text("前往", fontSize = 13.sp) }
            }
        }

        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.allowFileAccess = true
                        settings.loadsImagesAutomatically = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                                // 把焦點交還給網頁，避免 Compose 輸入框搶走鍵盤
                                focusManager.clearFocus(force = true)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (url != null && url != "about:blank") {
                                    if (!userTyping) urlBar = url
                                    retryFlag.set(false)
                                    view?.requestFocus()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                loading = false
                                if (request?.isForMainFrame == true && retryFlag.compareAndSet(false, true)) {
                                    view?.postDelayed({ view.reload() }, 800)
                                }
                            }
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(req)
                                Toast.makeText(ctx, "已開始下載，完成後可在「設定 → 匯入」加入帳本", Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(ctx, "下載失敗：${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        })
                        webView = this
                        loadUrl(normalize(startUrl))
                    }
                }
            )
        }
    }
}
