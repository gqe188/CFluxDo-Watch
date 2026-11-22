package com.cfluxdo.watch

import android.app.Application
import android.content.ComponentCallbacks2
import com.cfluxdo.watch.auth.QrLoginProtocol
import com.cfluxdo.watch.net.DiscourseClient
import com.cfluxdo.watch.net.DohGatewayManager
import com.cfluxdo.watch.net.ImageLoader
import com.cfluxdo.watch.net.PersistentCookieStore
import com.cfluxdo.watch.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WatchApp : Application() {
    companion object {
        lateinit var instance: WatchApp
            private set
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
        private set
    lateinit var cookieStore: PersistentCookieStore
        private set
    lateinit var gateway: DohGatewayManager
        private set
    lateinit var discourse: DiscourseClient
        private set
    lateinit var imageLoader: ImageLoader
        private set
    lateinit var qrProtocol: QrLoginProtocol
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        cookieStore = PersistentCookieStore(this)
        gateway = DohGatewayManager(this, prefs)
        discourse = DiscourseClient(gateway, cookieStore, prefs)
        imageLoader = ImageLoader(discourse)
        qrProtocol = QrLoginProtocol(discourse, prefs)

        // Start CFluxDo's Rust local ECH + Cloudflare edge gateway immediately on background thread
        appScope.launch(Dispatchers.IO) {
            gateway.startOrRestart(discourse.getUserAgent())
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            imageLoader.clear()
        }
    }
}
