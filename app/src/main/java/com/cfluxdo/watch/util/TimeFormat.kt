package com.cfluxdo.watch.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TimeFormat {
    private val formats = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )

    fun relative(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val millis = parseIsoUtc(iso) ?: return ""
        val diffSec = ((System.currentTimeMillis() - millis) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60 -> "刚刚"
            diffSec < 3600 -> "${diffSec / 60}分"
            diffSec < 86400 -> "${diffSec / 3600}时"
            diffSec < 86400 * 30 -> "${diffSec / 86400}天"
            else -> "${diffSec / (86400 * 30)}月"
        }
    }

    private fun parseIsoUtc(iso: String): Long? {
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(iso)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }
}
