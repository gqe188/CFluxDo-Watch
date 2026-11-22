package com.cfluxdo.watch.net

import android.os.Build

/**
 * Exact Chromium GREASE Client Hints logic ported from CFluxDo lib/services/network/client_hints.dart
 */
object ClientHints {
    private val chromeMajorRegex = Regex("""Chrome/(\d+)""")
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

    fun sanitizeUserAgent(rawUa: String): String {
        return rawUa
            .replace(Regex(""";\s*wv\)"""), ")")
            .replace(Regex("""Version/\d+\.\d+\s*"""), "")
            .trim()
            .ifEmpty { defaultUserAgent() }
    }

    fun headersFor(userAgent: String): Map<String, String> {
        val match = chromeMajorRegex.find(userAgent) ?: return emptyMap()
        val major = match.groupValues[1].toIntOrNull() ?: return emptyMap()
        val greaseBrand = "Not${greaseyChars[major % greaseyChars.size]}A" +
            "${greaseyChars[(major + 1) % greaseyChars.size]}Brand"
        val order = orders[major % orders.size]
        val slots = arrayOf("", "", "")
        slots[order[0]] = "\"$greaseBrand\";v=\"${greasedVersions[major % greasedVersions.size]}\""
        slots[order[1]] = "\"Chromium\";v=\"$major\""
        slots[order[2]] = "\"Android WebView\";v=\"$major\""
        return mapOf(
            "sec-ch-ua" to slots.joinToString(", "),
            "sec-ch-ua-mobile" to if (userAgent.contains(" Mobile")) "?1" else "?0",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }
}
