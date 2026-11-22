package com.cfluxdo.watch.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ultra-lightweight image loader for smartwatch.
 * Caps memory cache to 4MB, decodes RGB_565 (50% RAM), and downsamples to watch resolution.
 */
class ImageLoader(private val client: DiscourseClient) {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(rawUrl: String, maxWidthPx: Int = 380): Bitmap? = withContext(Dispatchers.IO) {
        val url = client.resolveUrl(rawUrl)
        val cacheKey = "$url@$maxWidthPx"
        cache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }

        val bytes = try {
            client.downloadBytes(url)
        } catch (_: Exception) {
            return@withContext null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / sample > maxWidthPx) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return@withContext null

        cache.put(cacheKey, bmp)
        bmp
    }

    fun clear() {
        cache.evictAll()
    }
}
