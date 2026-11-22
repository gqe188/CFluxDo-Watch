package com.cfluxdo.watch.util

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("cfluxdo_watch_prefs", Context.MODE_PRIVATE)

    var dohEnabled: Boolean
        get() = sp.getBoolean("doh_enabled", true)
        set(v) = sp.edit().putBoolean("doh_enabled", v).apply()

    // "auto", "v6", "v4"
    var ipv6Mode: String
        get() = sp.getString("ipv6_mode", "auto") ?: "auto"
        set(v) = sp.edit().putString("ipv6_mode", v).apply()

    var browserTls: Boolean
        get() = sp.getBoolean("browser_tls", true)
        set(v) = sp.edit().putBoolean("browser_tls", v).apply()

    // Comma or newline separated custom Cloudflare IPs/hosts
    var customNodesRaw: String
        get() = sp.getString("custom_nodes", "") ?: ""
        set(v) = sp.edit().putString("custom_nodes", v.trim()).apply()

    fun customNodesList(): List<String> =
        customNodesRaw.split(',', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)

    var selectStateJson: String?
        get() = sp.getString("select_state_json", null)
        set(v) = sp.edit().putString("select_state_json", v).apply()

    var accelRouteTableJson: String?
        get() = sp.getString("accel_route_table_json", null)
        set(v) = sp.edit().putString("accel_route_table_json", v).apply()

    var accelNodesEnvelopeJson: String?
        get() = sp.getString("accel_nodes_envelope_json", null)
        set(v) = sp.edit().putString("accel_nodes_envelope_json", v).apply()

    // Reading body font size in sp: 13, 15, 17, 19
    var fontSizeSp: Int
        get() = sp.getInt("font_size_sp", 15).coerceIn(12, 21)
        set(v) = sp.edit().putInt("font_size_sp", v.coerceIn(12, 21)).apply()

    // Default false = tap-to-load images (ultra low RAM / low power / zero heat)
    var autoLoadImages: Boolean
        get() = sp.getBoolean("auto_load_images", false)
        set(v) = sp.edit().putBoolean("auto_load_images", v).apply()

    // Rotary crown preferences for OPPO Watch / Wear OS
    var rotaryHaptics: Boolean
        get() = sp.getBoolean("rotary_haptics", true)
        set(v) = sp.edit().putBoolean("rotary_haptics", v).apply()

    var rotaryReverse: Boolean
        get() = sp.getBoolean("rotary_reverse", false)
        set(v) = sp.edit().putBoolean("rotary_reverse", v).apply()

    var cachedUsername: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()

    var cachedTrustLevel: Int
        get() = sp.getInt("trust_level", 0)
        set(v) = sp.edit().putInt("trust_level", v).apply()

    var cachedUserAgent: String?
        get() = sp.getString("cached_ua", null)
        set(v) = sp.edit().putString("cached_ua", v).apply()

    var qrClientId: String?
        get() = sp.getString("qr_client_id", null)
        set(v) = sp.edit().putString("qr_client_id", v).apply()

    var categoriesCacheJson: String?
        get() = sp.getString("categories_cache_json", null)
        set(v) = sp.edit().putString("categories_cache_json", v).apply()
}
