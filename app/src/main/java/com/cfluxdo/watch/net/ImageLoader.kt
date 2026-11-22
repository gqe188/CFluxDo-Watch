package com.cfluxdo.watch.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Ultra-lightweight image & emoji loader for smartwatch.
 * - Caps memory cache to 4MB total.
 * - Decodes large photos as RGB_565 (50% RAM) and downsamples to watch resolution.
 * - Decodes emojis/stickers as ARGB_8888 (preserving transparent backgrounds) at compact size.
 * - Coalesces concurrent requests for the same URL (important when many posts share emojis).
 */
class ImageLoader(private val client: DiscourseClient) {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    fun getCached(rawUrl: String, maxWidthPx: Int = 380, preserveAlpha: Boolean = false): Bitmap? {
        val key = cacheKey(rawUrl, maxWidthPx, preserveAlpha)
        return cache.get(key)?.takeIf { !it.isRecycled }
    }

    fun getCachedEmoji(rawUrl: String, maxSizePx: Int = 96): Bitmap? =
        getCached(rawUrl, maxWidthPx = maxSizePx, preserveAlpha = true)

    suspend fun loadEmoji(rawUrl: String, maxSizePx: Int = 96): Bitmap? =
        loadInternal(rawUrl, maxWidthPx = maxSizePx, preserveAlpha = true)

    suspend fun load(
        rawUrl: String,
        maxWidthPx: Int = 380,
        preserveAlpha: Boolean = false
    ): Bitmap? = loadInternal(rawUrl, maxWidthPx = maxWidthPx, preserveAlpha = preserveAlpha)

    private fun cacheKey(rawUrl: String, maxWidthPx: Int, preserveAlpha: Boolean): String {
        val url = client.resolveUrl(rawUrl)
        val mode = if (preserveAlpha) "a" else "o"
        return "$url@$maxWidthPx$mode"
    }

    private suspend fun loadInternal(
        rawUrl: String,
        maxWidthPx: Int,
        preserveAlpha: Boolean
    ): Bitmap? = withContext(Dispatchers.IO) {
        val url = client.resolveUrl(rawUrl)
        val key = cacheKey(rawUrl, maxWidthPx, preserveAlpha)
        cache.get(key)?.let { if (!it.isRecycled) return@withContext it }

        val existing = inFlight[key]
        if (existing != null) {
            return@withContext existing.await()
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val raced = inFlight.putIfAbsent(key, deferred)
        if (raced != null) {
            return@withContext raced.await()
        }

        try {
            val bytes = client.downloadBytes(url)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                deferred.complete(null)
                return@withContext null
            }

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxWidthPx * 2) {
                sample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (preserveAlpha) {
                    Bitmap.Config.ARGB_8888
                } else {
                    Bitmap.Config.RGB_565
                }
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            if (bmp != null) {
                cache.put(key, bmp)
            }
            deferred.complete(bmp)
            bmp
        } catch (_: Exception) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    fun clear() {
        cache.evictAll()
    }
}
