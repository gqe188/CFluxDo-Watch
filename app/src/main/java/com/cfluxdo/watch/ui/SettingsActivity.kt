package com.cfluxdo.watch.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = WatchApp.instance

    private lateinit var scrollView: ScrollView
    private lateinit var tvGatewayStatus: TextView
    private lateinit var tvWinnerInfo: TextView
    private lateinit var btnRerace: TextView
    private lateinit var btnRefreshStatus: TextView
    private lateinit var btnToggleDoh: TextView
    private lateinit var btnCycleIpv6: TextView
    private lateinit var btnToggleBrowserTls: TextView
    private lateinit var btnCustomNode: TextView
    private lateinit var btnCycleFontSize: TextView
    private lateinit var btnToggleAutoImage: TextView
    private lateinit var btnToggleRotaryHaptics: TextView
    private lateinit var btnToggleRotaryReverse: TextView
    private lateinit var btnManualCf: TextView
    private lateinit var btnClearCache: TextView
    private lateinit var tvVersionInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        scrollView = findViewById(R.id.scrollView)
        tvGatewayStatus = findViewById(R.id.tvGatewayStatus)
        tvWinnerInfo = findViewById(R.id.tvWinnerInfo)
        btnRerace = findViewById(R.id.btnRerace)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        btnToggleDoh = findViewById(R.id.btnToggleDoh)
        btnCycleIpv6 = findViewById(R.id.btnCycleIpv6)
        btnToggleBrowserTls = findViewById(R.id.btnToggleBrowserTls)
        btnCustomNode = findViewById(R.id.btnCustomNode)
        btnCycleFontSize = findViewById(R.id.btnCycleFontSize)
        btnToggleAutoImage = findViewById(R.id.btnToggleAutoImage)
        btnToggleRotaryHaptics = findViewById(R.id.btnToggleRotaryHaptics)
        btnToggleRotaryReverse = findViewById(R.id.btnToggleRotaryReverse)
        btnManualCf = findViewById(R.id.btnManualCf)
        btnClearCache = findViewById(R.id.btnClearCache)
        tvVersionInfo = findViewById(R.id.tvVersionInfo)

        RotaryHelper.attachToScrollView(scrollView)

        btnRerace.setOnClickListener {
            uiScope.launch {
                val ok = withContext(Dispatchers.IO) { app.gateway.rerace() }
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "已开始重新优选节点…" else "网关冷却中或未开启优选",
                    Toast.LENGTH_SHORT
                ).show()
                delay(1200)
                refreshUi()
            }
        }

        btnRefreshStatus.setOnClickListener { refreshUi() }

        btnToggleDoh.setOnClickListener {
            app.prefs.dohEnabled = !app.prefs.dohEnabled
            restartGatewayAsync()
        }

        btnCycleIpv6.setOnClickListener {
            app.prefs.ipv6Mode = when (app.prefs.ipv6Mode) {
                "auto" -> "v6"
                "v6" -> "v4"
                else -> "auto"
            }
            restartGatewayAsync()
        }

        btnToggleBrowserTls.setOnClickListener {
            app.prefs.browserTls = !app.prefs.browserTls
            restartGatewayAsync()
        }

        btnCustomNode.setOnClickListener { showCustomNodeDialog() }

        btnCycleFontSize.setOnClickListener {
            app.prefs.fontSizeSp = when (app.prefs.fontSizeSp) {
                in 0..13 -> 15
                in 14..15 -> 17
                in 16..17 -> 19
                else -> 13
            }
            refreshUi()
        }

        btnToggleAutoImage.setOnClickListener {
            app.prefs.autoLoadImages = !app.prefs.autoLoadImages
            refreshUi()
        }

        btnToggleRotaryHaptics.setOnClickListener {
            app.prefs.rotaryHaptics = !app.prefs.rotaryHaptics
            refreshUi()
        }

        btnToggleRotaryReverse.setOnClickListener {
            app.prefs.rotaryReverse = !app.prefs.rotaryReverse
            refreshUi()
        }

        btnManualCf.setOnClickListener {
            startActivity(Intent(this, CfChallengeActivity::class.java))
        }

        btnClearCache.setOnClickListener {
            uiScope.launch {
                app.imageLoader.clear()
                app.prefs.categoriesCacheJson = null
                withContext(Dispatchers.IO) { app.gateway.clearDnsCache() }
                Toast.makeText(this@SettingsActivity, "已清理图片与 DNS 缓存", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }

        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.1-watch"
        } catch (_: Exception) {
            "1.0.1-watch"
        }
        tvVersionInfo.text = "CFluxDo Watch $ver · 原生低功耗版\n核心网络引擎来自 1molchuan/CFluxDo"
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        RotaryHelper.ensureFocus(scrollView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            RotaryHelper.ensureFocus(scrollView)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (RotaryHelper.handleActivityGenericMotion(scrollView, event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun restartGatewayAsync() {
        tvGatewayStatus.text = "正在应用网络设置…"
        uiScope.launch {
            withContext(Dispatchers.IO) {
                app.gateway.startOrRestart(app.discourse.getUserAgent())
            }
            refreshUi()
        }
    }

    private fun showCustomNodeDialog() {
        val input = EditText(this).apply {
            hint = "如 104.16.x.x（逗号分隔，留空自动）"
            setText(app.prefs.customNodesRaw)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            textSize = 13f
        }
        AlertDialog.Builder(this)
            .setTitle("自定义 CF 优选节点")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                app.prefs.customNodesRaw = input.text.toString()
                restartGatewayAsync()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshUi() {
        uiScope.launch {
            val st = withContext(Dispatchers.IO) {
                app.gateway.drainAccelUpdates()
                app.gateway.getSelectStatus()
            }

            if (st.running) {
                tvGatewayStatus.text = "本地网关运行中 :${st.port}"
                tvGatewayStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_ok))
            } else {
                tvGatewayStatus.text = "本地网关未运行"
                tvGatewayStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_err))
            }

            val phaseLabel = when (st.phase) {
                "ready" -> "已优选"
                "racing" -> "竞速中"
                "verifying" -> "校验中"
                "failed" -> "优选失败"
                else -> st.phase
            }
            val winnerStr = if (st.winnerIp != null) {
                val rtt = st.latencyMs?.let { "${it}ms" } ?: "-"
                val src = st.source ?: ""
                "节点: ${st.winnerIp} ($rtt $src)"
            } else {
                st.lastError ?: "状态: $phaseLabel"
            }
            tvWinnerInfo.text = winnerStr

            btnToggleDoh.text = "本地优选 + ECH 加速：${if (app.prefs.dohEnabled) "开启" else "关闭"}"
            val ipLabel = when (app.prefs.ipv6Mode) {
                "v6" -> "IPv6 优先"
                "v4" -> "IPv4 优先"
                else -> "自动（推荐）"
            }
            btnCycleIpv6.text = "IP 协议偏好：$ipLabel"
            btnToggleBrowserTls.text = "浏览器 TLS 指纹：${if (app.prefs.browserTls) "开启" else "关闭"}"

            val customCount = app.prefs.customNodesList().size
            btnCustomNode.text = if (customCount > 0) {
                "自定义 CF 节点：已设 $customCount 个"
            } else {
                "自定义 CF 节点：自动优选"
            }

            btnCycleFontSize.text = "阅读字号：${app.prefs.fontSizeSp}sp"
            btnToggleAutoImage.text = "图片加载：${if (app.prefs.autoLoadImages) "自动小图" else "点击加载（最省电）"}"
            btnToggleRotaryHaptics.text = "表冠震动反馈：${if (app.prefs.rotaryHaptics) "开启" else "关闭"}"
            btnToggleRotaryReverse.text = "表冠滚动方向：${if (app.prefs.rotaryReverse) "反向" else "默认"}"
        }
    }
}
