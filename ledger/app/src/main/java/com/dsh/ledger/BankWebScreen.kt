@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dsh.ledger

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * App 內建網銀瀏覽器
 * - 你在此輸入帳密與 OTP（App 不讀取、不儲存任何輸入內容）
 * - 登入後可手動切換到「查詢頁」
 * - 下載檔案會交給系統下載，完成後可回 App 匯入
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
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    val retryFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 15.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        val wv = webView
                        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
                    }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (!queryUrl.isNullOrBlank()) {
                        androidx.compose.material3.TextButton(onClick = {
                            webView?.loadUrl(queryUrl)
                        }) { Text("查詢頁", fontSize = 13.sp) }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
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
                        settings.userAgentString = settings.userAgentString + " LedgerApp/1.0"
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                loading = false
                                // 只重試主文件，且只重試一次，避免無限迴圈
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
                        loadUrl(startUrl)
                        webView = this
                    }
                }
            )
        }
    }
}
