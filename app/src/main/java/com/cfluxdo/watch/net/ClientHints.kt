package com.cfluxdo.watch.net

import android.os.Build

/**
 * Chromium GREASE Client Hints & UA/Cookie helpers.
 * Supports Android WebView, Google Chrome, and Microsoft Edge across Android/Windows/macOS/Linux
 * so synced cf_clearance + User-Agent from PC/phone browsers stay 100% consistent.
 */
object ClientHints {
    private val chromeMajorRegex = Regex("""Chrome/(\d+)""")
    private val edgeMajorRegex = Regex("""EdgA?/(\d+)""")
    private val greaseyChars = charArrayOf(' ', '(', ':', '-', '.', '/', ')', ';', '=', '?', '_')
    private val greasedVersions = arrayOf("8", "99", "24")
    private val orders = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(0, 2, 1),
        intArrayOf(1, 0, 2), intArrayOf(1, 2, 0),
        intArrayOf(2, 0, 1), intArrayOf(2, 1, 0)
    )

    fun defaultUserAgent(): String {
        val release = Build.VERSION.RELEASE.ifBlank { "14" }
        val model = Build.MODEL.ifBlank { "OPPO Watch X2" }
        return "Mozilla/5.0 (Linux; Android $release; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    fun extractChromeMajor(userAgent: String): Int? {
        val match = chromeMajorRegex.find(userAgent) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun sanitizeUserAgent(rawUa: String): String {
        return rawUa
            .replace(Regex(""";\s*wv\)"""), ")")
            .replace(Regex("""Version/\d+\.\d+\s*"""), "")
            .trim()
            .ifEmpty { defaultUserAgent() }
    }

    /**
     * Extracts a clean cf_clearance token from either:
     * - a bare value ("abc.def-173...")
     * - "cf_clearance=abc.def-173..."
     * - a full Cookie header ("_t=xxx; cf_clearance=abc.def-173...; _forum_session=yyy")
     */
    fun extractCfClearanceValue(rawInput: String): String? {
        val trimmed = rawInput.trim().removePrefix("Cookie:").trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('=')) {
            val parts = trimmed.split(';')
            for (part in parts) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim().trim('"')
                if (name.equals("cf_clearance", ignoreCase = true) && value.isNotEmpty()) {
                    return value
                }
            }
            return null
        }
        return trimmed.trim('"').takeIf { it.isNotEmpty() && !it.contains(' ') }
    }

    fun headersFor(userAgent: String): Map<String, String> {
        val major = extractChromeMajor(userAgent) ?: return emptyMap()
        val edgeMatch = edgeMajorRegex.find(userAgent)
        val isWebView = userAgent.contains("; wv)") || Regex("""Version/\d+\.\d+""").containsMatchIn(userAgent)
        val brandName = when {
            edgeMatch != null -> "Microsoft Edge"
            isWebView -> "Android WebView"
            else -> "Google Chrome"
        }
        val brandMajor = edgeMatch?.groupValues?.get(1)?.toIntOrNull() ?: major

        val greaseBrand = "Not${greaseyChars[major % greaseyChars.size]}A" +
            "${greaseyChars[(major + 1) % greaseyChars.size]}Brand"
        val order = orders[major % orders.size]
        val slots = arrayOf("", "", "")
        slots[order[0]] = "\"$greaseBrand\";v=\"${greasedVersions[major % greasedVersions.size]}\""
        slots[order[1]] = "\"Chromium\";v=\"$major\""
        slots[order[2]] = "\"$brandName\";v=\"$brandMajor\""

        val platform = when {
            userAgent.contains("Windows", ignoreCase = true) -> "\"Windows\""
            userAgent.contains("Android", ignoreCase = true) -> "\"Android\""
            userAgent.contains("Macintosh", ignoreCase = true) ||
                userAgent.contains("Mac OS X", ignoreCase = true) -> "\"macOS\""
            userAgent.contains("iPhone", ignoreCase = true) ||
                userAgent.contains("iPad", ignoreCase = true) -> "\"iOS\""
            userAgent.contains("Linux", ignoreCase = true) -> "\"Linux\""
            else -> "\"Android\""
        }

        return mapOf(
            "sec-ch-ua" to slots.joinToString(", "),
            "sec-ch-ua-mobile" to if (userAgent.contains("Mobile")) "?1" else "?0",
            "sec-ch-ua-platform" to platform
        )
    }
}
