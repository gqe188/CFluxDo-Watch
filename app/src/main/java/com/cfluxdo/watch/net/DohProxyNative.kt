package com.cfluxdo.watch.net

import android.util.Log

/**
 * Direct JNI bridge to CFluxDo's Rust libdoh_proxy.so (ECH + local Cloudflare racer + BoringSSL TLS).
 */
object DohProxyNative {
    private const val TAG = "DohProxyNative"
    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (loaded) return true
            try {
                System.loadLibrary("doh_proxy")
                System.loadLibrary("doh_jni")
                loaded = nativeInit()
                loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load doh_proxy/doh_jni", t)
                false
            }
        }
    }

    @JvmStatic external fun nativeInit(): Boolean
    @JvmStatic external fun nativeStartWithConfigJson(configJson: String): Int
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeIsRunning(): Boolean
    @JvmStatic external fun nativeGetPort(): Int
    @JvmStatic external fun nativeSelectStatus(): String?
    @JvmStatic external fun nativeSelectRerace(): Int
    @JvmStatic external fun nativeNetworkChanged(sig: String): Int
    @JvmStatic external fun nativeTakeAccelUpdate(): String?
    @JvmStatic external fun nativeGetEmbeddedCaPem(): String?
    @JvmStatic external fun nativeClearDnsCache(): Boolean
}
