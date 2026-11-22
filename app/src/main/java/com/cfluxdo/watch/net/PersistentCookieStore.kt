package com.cfluxdo.watch.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight SharedPreferences-backed Cookie store for linux.do.
 * Keeps memory tiny and survives watch process restarts.
 */
class PersistentCookieStore(context: Context) {
    private val sp = context.getSharedPreferences("cfluxdo_watch_cookies", Context.MODE_PRIVATE)
    private val cookies = ConcurrentHashMap<String, StoredCookie>()

    data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean
    )

    init {
        loadFromDisk()
    }

    private fun keyOf(domain: String, path: String, name: String): String =
        "${domain.lowercase()}|$path|$name"

    fun saveFromHeaders(url: HttpUrl, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        var changed = false
        val now = System.currentTimeMillis()
        for (raw in setCookieHeaders) {
            val parsed = Cookie.parse(url, raw) ?: continue
            val key = keyOf(parsed.domain, parsed.path, parsed.name)
            if (parsed.expiresAt <= now || parsed.value.isEmpty()) {
                if (cookies.remove(key) != null) changed = true
            } else {
                cookies[key] = StoredCookie(
                    name = parsed.name,
                    value = parsed.value,
                    domain = parsed.domain.lowercase(),
                    path = parsed.path,
                    expiresAt = parsed.expiresAt,
                    secure = parsed.secure,
                    httpOnly = parsed.httpOnly
                )
                changed = true
            }
        }
        if (changed) persistToDisk()
    }

    fun setCookie(name: String, value: String, domain: String = "linux.do") {
        val cleanDomain = domain.lowercase().removePrefix(".")
        val key = keyOf(cleanDomain, "/", name)
        if (value.isBlank()) {
            cookies.remove(key)
        } else {
            cookies[key] = StoredCookie(
                name = name,
                value = value.trim(),
                domain = cleanDomain,
                path = "/",
                expiresAt = System.currentTimeMillis() + 365L * 86400_000L,
                secure = true,
                httpOnly = true
            )
        }
        persistToDisk()
    }

    fun importCookieHeaderString(cookieHeader: String, domain: String = "linux.do") {
        if (cookieHeader.isBlank()) return
        val parts = cookieHeader.split(';')
        var changed = false
        val cleanDomain = domain.lowercase().removePrefix(".")
        val exp = System.currentTimeMillis() + 365L * 86400_000L
        for (part in parts) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isEmpty()) continue
            val key = keyOf(cleanDomain, "/", name)
            cookies[key] = StoredCookie(name, value, cleanDomain, "/", exp, secure = true, httpOnly = false)
            changed = true
        }
        if (changed) persistToDisk()
    }

    fun buildCookieHeader(url: HttpUrl): String? {
        val now = System.currentTimeMillis()
        val host = url.host.lowercase()
        val path = url.encodedPath
        val matched = mutableListOf<StoredCookie>()
        var expiredAny = false
        for ((k, c) in cookies) {
            if (c.expiresAt <= now) {
                cookies.remove(k)
                expiredAny = true
                continue
            }
            val domainOk = host == c.domain || host.endsWith(".${c.domain}")
            val pathOk = path.startsWith(c.path)
            if (domainOk && pathOk) {
                matched.add(c)
            }
        }
        if (expiredAny) persistToDisk()
        if (matched.isEmpty()) return null
        return matched.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun getCookieValue(name: String): String? {
        val now = System.currentTimeMillis()
        return cookies.values
            .firstOrNull { it.name == name && it.expiresAt > now }
            ?.value
    }

    fun getTToken(): String? = getCookieValue("_t")

    fun hasLoginSession(): Boolean = !getTToken().isNullOrBlank()

    fun clearSessionCookies() {
        val toRemove = cookies.keys.filter { k ->
            val name = k.substringAfterLast('|')
            name == "_t" || name == "_forum_session"
        }
        toRemove.forEach { cookies.remove(it) }
        persistToDisk()
    }

    fun clearAll() {
        cookies.clear()
        sp.edit().remove("cookies_v1").apply()
    }

    fun exportForWebView(url: String = "https://linux.do/"): List<String> {
        val httpUrl = url.toHttpUrl()
        val now = System.currentTimeMillis()
        return cookies.values
            .filter { it.expiresAt > now && (httpUrl.host == it.domain || httpUrl.host.endsWith(".${it.domain}")) }
            .map { "${it.name}=${it.value}; Domain=.${it.domain}; Path=${it.path}; Secure" }
    }

    private fun loadFromDisk() {
        val raw = sp.getString("cookies_v1", null) ?: return
        try {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val exp = obj.optLong("exp", 0L)
                if (exp <= now) continue
                val c = StoredCookie(
                    name = obj.getString("n"),
                    value = obj.getString("v"),
                    domain = obj.getString("d").lowercase(),
                    path = obj.optString("p", "/"),
                    expiresAt = exp,
                    secure = obj.optBoolean("s", true),
                    httpOnly = obj.optBoolean("h", false)
                )
                cookies[keyOf(c.domain, c.path, c.name)] = c
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persistToDisk() {
        val arr = JSONArray()
        for (c in cookies.values) {
            arr.put(JSONObject().apply {
                put("n", c.name)
                put("v", c.value)
                put("d", c.domain)
                put("p", c.path)
                put("exp", c.expiresAt)
                put("s", c.secure)
                put("h", c.httpOnly)
            })
        }
        sp.edit().putString("cookies_v1", arr.toString()).apply()
    }
}
