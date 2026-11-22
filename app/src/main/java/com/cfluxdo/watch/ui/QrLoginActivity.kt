package com.cfluxdo.watch.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import com.cfluxdo.watch.auth.LanQrLoginServer
import com.cfluxdo.watch.auth.QrLoginProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dual-mode Smartwatch QR Login screen:
 * 1) Not logged in -> Watch starts a temporary LAN HTTP server & shows a crisp white-card QR code.
 *    Phone scans it -> uploads CFluxDo's login QR screenshot (or pastes fluxdo:// / _t cookie)
 *    -> Watch runs exact CFluxDo v2 OTP redeem + key revoke protocol!
 * 2) Logged in -> Shows current account + option to display a CFluxDo v2 share QR code
 *    (with 10-minute OTP countdown, 3s polling, and auto-revoke on close).
 */
class QrLoginActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = WatchApp.instance

    private lateinit var scrollView: ScrollView
    private lateinit var tvQrTitle: TextView
    private lateinit var tvQrSubtitle: TextView
    private lateinit var qrCard: FrameLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrStatus: TextView
    private lateinit var tvQrUrlHint: TextView
    private lateinit var loggedInPanel: LinearLayout
    private lateinit var tvLoggedInUser: TextView
    private lateinit var btnShareQr: TextView
    private lateinit var btnLogout: TextView
    private lateinit var btnQrAction: TextView

    private var lanServer: LanQrLoginServer? = null
    private var activeSharedPayload: QrLoginProtocol.QrPayload? = null
    private var shareConsumed: Boolean = false
    private var sharePollJob: Job? = null
    private var shareCountdownJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots while displaying sensitive QR credentials
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_qr_login)

        scrollView = findViewById(R.id.scrollView)
        tvQrTitle = findViewById(R.id.tvQrTitle)
        tvQrSubtitle = findViewById(R.id.tvQrSubtitle)
        qrCard = findViewById(R.id.qrCard)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvQrStatus = findViewById(R.id.tvQrStatus)
        tvQrUrlHint = findViewById(R.id.tvQrUrlHint)
        loggedInPanel = findViewById(R.id.loggedInPanel)
        tvLoggedInUser = findViewById(R.id.tvLoggedInUser)
        btnShareQr = findViewById(R.id.btnShareQr)
        btnLogout = findViewById(R.id.btnLogout)
        btnQrAction = findViewById(R.id.btnQrAction)

        RotaryHelper.attachToScrollView(scrollView)

        btnShareQr.setOnClickListener { generateShareQr() }
        btnLogout.setOnClickListener { performLogout() }

        if (app.cookieStore.hasLoginSession()) {
            showLoggedInState()
        } else {
            startPhoneScanWatchMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lanServer?.stop()
        lanServer = null
        sharePollJob?.cancel()
        shareCountdownJob?.cancel()

        // Revoke unconsumed shared API key cleanly on exit (matches CFluxDo QrLoginSheet)
        val leftover = activeSharedPayload?.apiKey
        if (!shareConsumed && !leftover.isNullOrBlank()) {
            app.appScope.launch(Dispatchers.IO) {
                app.qrProtocol.revokeKeyQuietly(leftover)
            }
        }
        uiScope.cancel()
    }

    private fun startPhoneScanWatchMode() {
        loggedInPanel.visibility = View.GONE
        tvQrTitle.text = "手机扫码登录手表"
        tvQrSubtitle.text = "手机与手表连同一 Wi-Fi\n扫此码上传 CFluxDo 二维码"
        btnQrAction.visibility = View.VISIBLE
        btnQrAction.text = "刷新二维码"
        btnQrAction.setOnClickListener { startPhoneScanWatchMode() }

        lanServer?.stop()
        val server = LanQrLoginServer(
            protocol = app.qrProtocol,
            onStatusChange = { msg ->
                runOnUiThread { tvQrStatus.text = msg }
            },
            onLoginSuccess = { username ->
                runOnUiThread {
                    lanServer?.stop()
                    Toast.makeText(this, "登录成功：@$username", Toast.LENGTH_SHORT).show()
                    showLoggedInState()
                }
            }
        )
        lanServer = server

        uiScope.launch {
            val url = withContext(Dispatchers.IO) { server.start(uiScope) }
            if (url == null) {
                qrCard.visibility = View.GONE
                tvQrStatus.text = "未检测到手表 Wi-Fi 局域网 IP\n请先让手表连接 Wi-Fi"
                tvQrUrlHint.text = ""
                return@launch
            }
            val bmp = withContext(Dispatchers.Default) {
                app.qrProtocol.generateQrBitmap(url, 320)
            }
            ivQrCode.setImageBitmap(bmp)
            qrCard.visibility = View.VISIBLE
            tvQrStatus.text = "等待手机扫码…"
            tvQrUrlHint.text = url
        }
    }

    private fun showLoggedInState() {
        lanServer?.stop()
        qrCard.visibility = View.GONE
        loggedInPanel.visibility = View.VISIBLE
        btnQrAction.visibility = View.GONE
        tvQrTitle.text = "已登录 Linux.do"
        tvQrSubtitle.text = "支持生成原版 CFluxDo v2 登录码分享给其他设备"
        tvQrStatus.text = ""
        tvQrUrlHint.text = ""

        val cachedName = app.prefs.cachedUsername ?: "user"
        val tl = app.prefs.cachedTrustLevel
        tvLoggedInUser.text = "@$cachedName (TL$tl)"

        uiScope.launch {
            try {
                val u = app.discourse.fetchCurrentUser()
                if (u != null) {
                    tvLoggedInUser.text = "@${u.username} (TL${u.trustLevel})"
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun generateShareQr() {
        sharePollJob?.cancel()
        shareCountdownJob?.cancel()
        shareConsumed = false

        tvQrStatus.text = "正在生成 RSA 密钥与一次性登录码…"
        qrCard.visibility = View.GONE
        btnQrAction.visibility = View.VISIBLE
        btnQrAction.text = "重新生成"
        btnQrAction.setOnClickListener { generateShareQr() }

        uiScope.launch {
            try {
                val res = app.qrProtocol.createShareQrPayload()
                activeSharedPayload = res.payload
                val bmp = withContext(Dispatchers.Default) {
                    app.qrProtocol.generateQrBitmap(res.rawUri, 320)
                }
                ivQrCode.setImageBitmap(bmp)
                qrCard.visibility = View.VISIBLE
                tvQrSubtitle.text = "用另一台 CFluxDo 扫描此码（用完即焚）"
                tvQrUrlHint.text = "账号 @${res.payload.username}"

                // 10-minute OTP countdown
                val deadline = System.currentTimeMillis() + 10 * 60_000L
                shareCountdownJob = launch {
                    while (isActive && !shareConsumed) {
                        val leftSec = ((deadline - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                        if (leftSec == 0L) {
                            tvQrStatus.text = "二维码已过期，请重新生成"
                            sharePollJob?.cancel()
                            break
                        }
                        val m = leftSec / 60
                        val s = leftSec % 60
                        tvQrStatus.text = String.format("有效倒计时 %02d:%02d", m, s)
                        delay(1000L)
                    }
                }

                // Poll every 3s for key revocation (= remote device logged in!)
                sharePollJob = launch {
                    val uname = res.payload.username
                    while (isActive && !shareConsumed) {
                        delay(3000L)
                        val alive = app.qrProtocol.isSharedKeyAlive(uname)
                        if (alive == false) {
                            shareConsumed = true
                            shareCountdownJob?.cancel()
                            tvQrStatus.text = "对方设备已扫码登录成功！"
                            qrCard.alpha = 0.35f
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                tvQrStatus.text = "生成失败：${e.message ?: "请检查网络或先过盾"}"
            }
        }
    }

    private fun performLogout() {
        app.cookieStore.clearSessionCookies()
        app.prefs.cachedUsername = null
        app.prefs.cachedTrustLevel = 0
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        startPhoneScanWatchMode()
    }
}
