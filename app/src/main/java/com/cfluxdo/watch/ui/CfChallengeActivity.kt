package com.cfluxdo.watch.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import com.cfluxdo.watch.net.DiscourseClient
import java.util.concurrent.Executors

/**
 * On-demand Cloudflare verification activity.
 * Instantiates WebView ONLY when Cloudflare actually challenges the user, proxies it through
 * CFluxDo's exact same Rust gateway (same exit IP!), extracts cf_clearance + User-Agent,
 * and completely destroys WebView on exit so normal reading uses 0MB WebView memory.
 */
class CfChallengeActivity : AppCompatActivity() {

    private val app get() = WatchApp.instance
    private var webView: WebView? = null
    private var proxyApplied = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cf_challenge)

        val btnClose = findViewById<TextView>(R.id.btnCloseCf)
        btnClose.setOnClickListener {
            syncCookiesFromWebView()
            setResult(RESULT_OK)
            finish()
        }

        val wv = findViewById<WebView>(R.id.webView)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Sync real WebView User-Agent back to native DiscourseClient so TLS + UA match 1:1
        app.discourse.updateUserAgent(wv.settings.userAgentString)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
        for (c in app.cookieStore.exportForWebView()) {
            cm.setCookie(DiscourseClient.BASE_URL, c)
        }
        cm.flush()

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Local Rust gateway uses its embedded MITM CA when proxying WebView
                if (proxyApplied) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val synced = syncCookiesFromWebView()
                val title = view?.title.orEmpty()
                if (synced && !title.contains("Just a moment", ignoreCase = true) &&
                    url?.contains("linux.do") == true
                ) {
                    Toast.makeText(this@CfChallengeActivity, "验证完成，已同步凭证", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val port = app.gateway.activePort
        if (app.gateway.isRunning && port > 0 &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$port")
                .addBypassRule("<local>")
                .build()
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                Executors.newSingleThreadExecutor()
            ) {
                proxyApplied = true
                runOnUiThread {
                    webView?.loadUrl("${DiscourseClient.BASE_URL}/")
                }
            }
        } else {
            wv.loadUrl("${DiscourseClient.BASE_URL}/")
        }
    }

    private fun syncCookiesFromWebView(): Boolean {
        val raw = CookieManager.getInstance().getCookie(DiscourseClient.BASE_URL)
        if (!raw.isNullOrBlank()) {
            app.cookieStore.importCookieHeaderString(raw)
            return raw.contains("cf_clearance=") || raw.contains("_t=")
        }
        return false
    }

    override fun onDestroy() {
        syncCookiesFromWebView()
        if (proxyApplied && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                ProxyController.getInstance().clearProxyOverride(
                    Executors.newSingleThreadExecutor()
                ) {}
            } catch (_: Exception) {
            }
        }
        webView?.let { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
