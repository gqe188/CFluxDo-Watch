package com.cfluxdo.watch.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import com.cfluxdo.watch.net.ClientHints
import com.cfluxdo.watch.net.DiscourseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * On-demand Cloudflare verification activity.
 *
 * Fixes UA consistency: sets the sanitized User-Agent on WebView BEFORE loading and aligns
 * Rust gateway's webview_user_agent so cf_clearance matches native API requests 1:1.
 * Also detects outdated watch WebViews (e.g. Chromium 83) and offers 1-tap jump to
 * same-Wi-Fi PC/phone CF credential sync (QrLoginActivity MODE_CF_SYNC).
 */
class CfChallengeActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = WatchApp.instance
    private var webView: WebView? = null
    private var proxyApplied = false
    private var initialCfClearance: String? = null
    private var notifiedPassed = false

    private val lanSyncLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cf_challenge)

        initialCfClearance = app.cookieStore.getCookieValue("cf_clearance")

        val btnClose = findViewById<TextView>(R.id.btnCloseCf)
        btnClose.setOnClickListener {
            syncCookiesFromWebView()
            setResult(RESULT_OK)
            finish()
        }

        val btnSyncLan = findViewById<TextView>(R.id.btnSyncCfLan)
        btnSyncLan.setOnClickListener {
            lanSyncLauncher.launch(
                Intent(this, QrLoginActivity::class.java).putExtra(
                    QrLoginActivity.EXTRA_MODE,
                    QrLoginActivity.MODE_CF_SYNC
                )
            )
        }

        val tvWarn = findViewById<TextView>(R.id.tvWebViewWarn)
        val wv = findViewById<WebView>(R.id.webView)
        webView = wv
        RotaryHelper.attachToWebView(wv)

        val rawUa = wv.settings.userAgentString.orEmpty()
        val chromeMajor = ClientHints.extractChromeMajor(rawUa)
        if (chromeMajor != null && chromeMajor < 105) {
            tvWarn.visibility = View.VISIBLE
            tvWarn.text = "内置 WebView 过旧 (Chrome $chromeMajor)，易卡盾；推荐点上方「同Wi-Fi同步」由电脑/手机过盾"
        }

        // Align WebView UA == DiscourseClient UA == Rust gateway webview_user_agent BEFORE loading!
        val prevUa = app.discourse.getUserAgent()
        val cleanUa = app.discourse.updateUserAgent(rawUa)

        wv.settings.apply {
            userAgentString = cleanUa
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

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
                checkAndNotifyCookieSynced(view?.title.orEmpty(), url)
            }
        }

        // Restart gateway if UA changed so BoringSSL fingerprint & UA match WebView 1:1
        uiScope.launch {
            if (cleanUa != prevUa) {
                withContext(Dispatchers.IO) {
                    app.gateway.startOrRestart(cleanUa)
                }
            }
            applyProxyAndLoad(wv)

            // Periodically poll CookieManager in case Turnstile writes cf_clearance via JS without navigation
            while (isActive && !notifiedPassed) {
                delay(1500L)
                checkAndNotifyCookieSynced(webView?.title.orEmpty(), webView?.url)
            }
        }
    }

    private fun applyProxyAndLoad(wv: WebView) {
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

    private fun checkAndNotifyCookieSynced(title: String, url: String?) {
        val synced = syncCookiesFromWebView()
        val currentCf = app.cookieStore.getCookieValue("cf_clearance")
        val cfUpdated = !currentCf.isNullOrBlank() && currentCf != initialCfClearance
        val pageClean = !title.contains("Just a moment", ignoreCase = true) &&
            !title.contains("请稍候") &&
            url?.contains("linux.do") == true
        if (!notifiedPassed && (cfUpdated || (synced && pageClean))) {
            notifiedPassed = true
            Toast.makeText(this, "验证完成，已同步凭证", Toast.LENGTH_SHORT).show()
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

    override fun onResume() {
        super.onResume()
        RotaryHelper.ensureFocus(webView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            RotaryHelper.ensureFocus(webView)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (RotaryHelper.handleActivityGenericMotion(webView, event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        uiScope.cancel()
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
