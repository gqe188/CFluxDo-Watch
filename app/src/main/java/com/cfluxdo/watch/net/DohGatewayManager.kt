package com.cfluxdo.watch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.cfluxdo.watch.model.GatewaySelectStatus
import com.cfluxdo.watch.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages CFluxDo's Rust local ECH + Cloudflare edge-racer + DoH + BoringSSL TLS gateway.
 * Designed for smartwatch efficiency: zero busy polling, persists winner IP + ECH state across restarts.
 */
class DohGatewayManager(
    private val context: Context,
    private val prefs: Prefs
) {
    companion object {
        private const val TAG = "DohGatewayManager"
        private const val ACCEL_PUBLIC_URL = "https://edge.1molchuan.top/linuxdo"
        private const val ACCEL_ROUTES_URL = "https://edge.1molchuan.top/v1/routes"
        private const val ATLAS_ROUTES_URL = "https://atlas.1molchuan.top/v1/routes"
        private val ACCEL_BOOTSTRAP_IPS = listOf(
            "156.239.232.8",
            "156.239.232.72",
            "203.168.131.145",
            "103.199.103.204"
        )
    }

    @Volatile
    var activePort: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var networkCallbackRegistered = false
    private var lastNetworkSig: String? = null

    @Synchronized
    fun startOrRestart(userAgent: String): Boolean {
        if (!DohProxyNative.ensureLoaded()) {
            lastError = "原生网络库加载失败"
            isRunning = false
            activePort = 0
            return false
        }

        if (DohProxyNative.nativeIsRunning()) {
            drainAccelUpdates()
            DohProxyNative.nativeStop()
        }

        val dohEnabled = prefs.dohEnabled
        val ipv6Mode = prefs.ipv6Mode
        val preferIpv6 = ipv6Mode == "v6"
        val ipv6Auto = ipv6Mode == "auto"
        val browserTls = prefs.browserTls
        val customNodes = prefs.customNodesList()

        val fpDir = File(context.filesDir, "net").apply { mkdirs() }
        val fpPath = File(fpDir, "fingerprint_template.json").absolutePath

        val config = JSONObject().apply {
            put("bind_addr", "127.0.0.1")
            put("bind_port", 0)
            put("enable_doh", dohEnabled)
            put("gateway_mode", true)
            put("mitm_connect", true)
            put("h2_mitm", true)
            put("doh_server", if (dohEnabled) ACCEL_PUBLIC_URL else "cloudflare")
            put("prefer_ipv6", preferIpv6)
            put("ipv6_auto", ipv6Auto)
            put("timeout_secs", 25)

            if (dohEnabled) {
                put("doh_bootstrap_ips", JSONArray(ACCEL_BOOTSTRAP_IPS))
                put("doh_routes_url", ACCEL_ROUTES_URL)
                prefs.accelRouteTableJson?.takeIf { it.isNotBlank() }?.let {
                    put("doh_route_table_json", it)
                }
                prefs.accelNodesEnvelopeJson?.takeIf { it.isNotBlank() }?.let {
                    put("doh_node_list_json", it)
                }
                put("select_mode", "local_atlas")
                prefs.selectStateJson?.takeIf { it.isNotBlank() }?.let {
                    put("select_state_json", it)
                }
                if (customNodes.isNotEmpty()) {
                    put("custom_nodes", JSONArray(customNodes))
                }
                put("share_measurements", false)
                put("atlas_routes_url", ATLAS_ROUTES_URL)
                put("public_doh", buildPublicDohSeeds())
                put("extra_covered_hosts", JSONArray(listOf("atlas.1molchuan.top", "ldstatic.com")))
                put("preconnect_hosts", JSONArray(listOf("linux.do")))
            } else {
                put("select_mode", "off")
            }

            currentNetworkSignature()?.let {
                lastNetworkSig = it
                put("network_sig", it)
            }
            if (browserTls) {
                put("browser_tls", true)
                put("fingerprint_cache_path", fpPath)
            }
            if (userAgent.isNotBlank()) {
                put("webview_user_agent", userAgent)
            }
            val appVer = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.2-watch"
            } catch (_: Exception) {
                "1.0.2-watch"
            }
            put("app_version", appVer)
        }

        val port = DohProxyNative.nativeStartWithConfigJson(config.toString())
        return if (port > 0) {
            activePort = port
            isRunning = true
            lastError = null
            registerNetworkCallbackOnce()
            Log.i(TAG, "Rust gateway started on 127.0.0.1:$port (doh=$dohEnabled, tls=$browserTls)")
            true
        } else {
            activePort = 0
            isRunning = false
            lastError = "本地网关启动失败 (code=$port)"
            Log.e(TAG, lastError!!)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (isRunning) {
            drainAccelUpdates()
            DohProxyNative.nativeStop()
        }
        isRunning = false
        activePort = 0
    }

    fun rerace(): Boolean {
        if (!isRunning) return false
        return DohProxyNative.nativeSelectRerace() == 1
    }

    fun clearDnsCache(): Boolean {
        if (!DohProxyNative.ensureLoaded()) return false
        return DohProxyNative.nativeClearDnsCache()
    }

    fun getSelectStatus(): GatewaySelectStatus {
        val running = isRunning && DohProxyNative.nativeIsRunning()
        val port = if (running) DohProxyNative.nativeGetPort() else 0
        if (!running) {
            return GatewaySelectStatus(
                running = false,
                port = 0,
                mode = if (prefs.dohEnabled) "local_atlas" else "off",
                phase = "stopped",
                winnerIp = null,
                latencyMs = null,
                source = null,
                lastError = lastError,
                busy = false
            )
        }
        val raw = DohProxyNative.nativeSelectStatus()
        if (raw.isNullOrBlank()) {
            return GatewaySelectStatus(
                running = true,
                port = port,
                mode = if (prefs.dohEnabled) "local_atlas" else "fingerprint",
                phase = "ready",
                winnerIp = null,
                latencyMs = null,
                source = null,
                lastError = null,
                busy = false
            )
        }
        return try {
            val obj = JSONObject(raw)
            val winner = obj.optJSONObject("winner")
            GatewaySelectStatus(
                running = true,
                port = port,
                mode = obj.optString("mode", "off"),
                phase = obj.optString("phase", "idle"),
                winnerIp = winner?.optString("ip")?.takeIf { it.isNotBlank() },
                latencyMs = if (winner != null && winner.has("latency_ms")) winner.optInt("latency_ms") else null,
                source = winner?.optString("source")?.takeIf { it.isNotBlank() },
                lastError = obj.optString("last_error").takeIf { it.isNotBlank() && it != "null" },
                busy = obj.optBoolean("busy", false)
            )
        } catch (_: Exception) {
            GatewaySelectStatus(true, port, "local_atlas", "unknown", null, null, null, null, false)
        }
    }

    /**
     * Pulls updated winner IP / ECH config / route table from Rust and persists to Prefs
     * so next cold launch connects immediately without waiting for a full race.
     */
    fun drainAccelUpdates() {
        if (!isRunning) return
        try {
            val raw = DohProxyNative.nativeTakeAccelUpdate() ?: return
            val obj = JSONObject(raw)
            if (!obj.optBoolean("ok", false)) return
            obj.optString("select_state").takeIf { it.isNotBlank() && it != "null" }?.let {
                prefs.selectStateJson = it
            }
            obj.optString("route_table").takeIf { it.isNotBlank() && it != "null" }?.let {
                prefs.accelRouteTableJson = it
            }
            obj.optJSONObject("nodes_envelope")?.let {
                prefs.accelNodesEnvelopeJson = it.toString()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildPublicDohSeeds(): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("url", "https://doh.pub/dns-query")
            put("bootstrap_ips", JSONArray(listOf("1.12.12.12", "120.53.53.53")))
        })
        arr.put(JSONObject().apply {
            put("url", "https://dns.alidns.com/dns-query")
            put("bootstrap_ips", JSONArray(listOf("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1")))
        })
        return arr
    }

    private fun currentNetworkSignature(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val active = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(active) ?: return "unknown"
            val parts = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts.add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts.add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) parts.add("bt")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts.add("vpn")
            if (parts.isEmpty()) "other" else parts.sorted().joinToString("+")
        } catch (_: Exception) {
            null
        }
    }

    private fun registerNetworkCallbackOnce() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = notifyNetChange()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = notifyNetChange()
            })
        } catch (_: Exception) {
        }
    }

    private fun notifyNetChange() {
        if (!isRunning) return
        val sig = currentNetworkSignature() ?: return
        if (sig == lastNetworkSig) return
        lastNetworkSig = sig
        try {
            DohProxyNative.nativeNetworkChanged(sig)
        } catch (_: Exception) {
        }
    }
}
