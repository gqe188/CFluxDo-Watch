package com.cfluxdo.watch.auth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.cfluxdo.watch.net.DiscourseClient
import com.cfluxdo.watch.util.Prefs
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.UUID
import javax.crypto.Cipher

/**
 * 100% compatible with upstream CFluxDo QR Login v2 protocol:
 *   fluxdo://qr-login?v=2&k=<api_key>&o=<otp>&u=<username>&exp=<unix_ms|0>
 *
 * Supports BOTH:
 * 1) Watch receiving a CFluxDo QR code (via Phone LAN QR upload/paste) -> redeems OTP for _t -> revokes key.
 * 2) Watch (when logged in) creating a CFluxDo QR code to share login to another device.
 */
class QrLoginProtocol(
    private val client: DiscourseClient,
    private val prefs: Prefs
) {
    companion object {
        const val SCHEME = "fluxdo"
        const val HOST = "qr-login"
        const val VERSION = 2
        const val QR_APP_NAME = "CFluxDo QR Login"
        const val AUTH_REDIRECT = "discourse://auth_redirect"
        const val SCOPES = "one_time_password"
        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    }

    data class QrPayload(
        val version: Int,
        val apiKey: String,
        val otp: String,
        val username: String,
        val expiresAtMs: Long
    ) {
        fun isExpired(): Boolean = expiresAtMs > 0L && System.currentTimeMillis() > expiresAtMs
    }

    data class ShareQrResult(
        val rawUri: String,
        val payload: QrPayload
    )

    fun parsePayload(raw: String): QrPayload? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            Uri.parse(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != SCHEME) return null
        val hostOrFirstPath = uri.host?.takeIf { it.isNotEmpty() }
            ?: uri.pathSegments.firstOrNull().orEmpty()
        if (hostOrFirstPath != HOST) return null

        val v = uri.getQueryParameter("v")?.toIntOrNull() ?: return null
        val k = uri.getQueryParameter("k")?.trim().orEmpty()
        val o = uri.getQueryParameter("o")?.trim().orEmpty()
        val u = uri.getQueryParameter("u")?.trim().orEmpty()
        val exp = uri.getQueryParameter("exp")?.trim()?.toLongOrNull() ?: 0L
        if (k.isEmpty() || o.isEmpty() || exp < 0L) return null
        return QrPayload(v, k, o, u, exp)
    }

    fun encodePayload(payload: QrPayload): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("v", payload.version.toString())
            .appendQueryParameter("k", payload.apiKey)
            .appendQueryParameter("o", payload.otp)
            .appendQueryParameter("u", payload.username)
            .appendQueryParameter("exp", payload.expiresAtMs.toString())
            .build()
            .toString()
    }

    /**
     * Waiting watch device redeems a CFluxDo v2 QR payload to log in.
     */
    suspend fun loginWithQrContent(raw: String): String {
        val payload = parsePayload(raw)
            ?: throw IllegalArgumentException("不是有效的 CFluxDo 登录二维码")
        if (payload.version != VERSION) {
            throw IllegalArgumentException("不支持的二维码版本 v${payload.version}")
        }
        if (payload.isExpired()) {
            throw IllegalArgumentException("登录二维码已过期，请重新生成")
        }
        if (!HEX_REGEX.matches(payload.otp)) {
            throw IllegalArgumentException("OTP 格式异常")
        }

        val beforeT = client.cookieStore.getTToken()
        // 1. Fetch fresh CSRF token
        val csrf = client.fetchCsrfToken()
        // 2. Redeem OTP -> Set-Cookie _t
        client.executePostForm(
            path = "/session/otp/${payload.otp}",
            params = emptyMap(),
            extraHeaders = mapOf("X-CSRF-Token" to csrf),
            useCsrf = false
        )
        val afterT = client.cookieStore.getTToken()
        if (afterT.isNullOrBlank() || afterT == beforeT) {
            throw IllegalStateException("登录令牌兑换失败，二维码可能已被使用或已过期")
        }

        // 3. Burn the one-time API key immediately (also signals phone CFluxDo that login succeeded)
        revokeKeyQuietly(payload.apiKey)

        // 4. Verify & cache current user
        val user = client.fetchCurrentUser()
        val username = user?.username ?: payload.username.ifBlank { "user" }
        prefs.cachedUsername = username
        return username
    }

    /**
     * Direct login via _t cookie (and optional cf_clearance) pushed from phone over LAN.
     */
    suspend fun loginWithCookie(tCookie: String, cfClearance: String? = null): String {
        val cleanT = tCookie.trim()
        if (cleanT.isEmpty()) throw IllegalArgumentException("Cookie 不能为空")

        if (cleanT.contains("=")) {
            client.cookieStore.importCookieHeaderString(cleanT)
        } else {
            client.cookieStore.setCookie("_t", cleanT)
        }
        if (!cfClearance.isNullOrBlank()) {
            client.cookieStore.setCookie("cf_clearance", cfClearance.trim())
        }

        val user = client.fetchCurrentUser()
            ?: throw IllegalStateException("登录态验证失败，请检查 _t 是否有效")
        prefs.cachedUsername = user.username
        prefs.cachedTrustLevel = user.trustLevel
        return user.username
    }

    /**
     * Logged-in watch generates a CFluxDo v2 share QR code for another device to scan.
     */
    suspend fun createShareQrPayload(): ShareQrResult = withContext(Dispatchers.IO) {
        val username = prefs.cachedUsername?.takeIf { it.isNotBlank() }
            ?: client.fetchCurrentUser()?.username
            ?: throw IllegalStateException("当前未登录")

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val pubPem = encodePublicKeyPem(keyPair.public.encoded)

        var clientId = prefs.qrClientId
        if (clientId.isNullOrBlank()) {
            clientId = UUID.randomUUID().toString()
            prefs.qrClientId = clientId
        }
        val nonce = UUID.randomUUID().toString()

        val res = client.executePostForm(
            path = "/user-api-key",
            params = mapOf(
                "application_name" to QR_APP_NAME,
                "client_id" to clientId,
                "scopes" to SCOPES,
                "public_key" to pubPem,
                "nonce" to nonce,
                "auth_redirect" to AUTH_REDIRECT
            )
        )

        var redirectUrl: String? = null
        try {
            val json = JSONObject(res.body)
            redirectUrl = json.optString("redirect_url").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
        }
        if (redirectUrl.isNullOrBlank()) {
            redirectUrl = res.location
        }
        if (redirectUrl.isNullOrBlank()) {
            throw IllegalStateException("服务端未返回授权跳转地址")
        }

        val uri = Uri.parse(redirectUrl)
        val encPayload = uri.getQueryParameter("payload")
            ?: throw IllegalStateException("缺少加密 payload")
        val encOtp = uri.getQueryParameter("oneTimePassword")
            ?: throw IllegalStateException("缺少一次性登录令牌")

        val decryptedJson = decryptRsaPkcs1(encPayload, keyPair.private)
        val payloadObj = JSONObject(decryptedJson)
        if (payloadObj.optString("nonce") != nonce) {
            throw IllegalStateException("Nonce 校验不匹配")
        }
        val apiKey = payloadObj.optString("key")
        if (apiKey.isBlank()) throw IllegalStateException("未返回 API Key")
        val otp = decryptRsaPkcs1(encOtp, keyPair.private)

        val payload = QrPayload(
            version = VERSION,
            apiKey = apiKey,
            otp = otp,
            username = username,
            expiresAtMs = 0L
        )
        ShareQrResult(encodePayload(payload), payload)
    }

    suspend fun isSharedKeyAlive(username: String): Boolean? {
        return try {
            val root = client.executeGetJson("/u/$username.json")
            val user = root.optJSONObject("user") ?: return null
            val keys = user.optJSONArray("user_api_keys") ?: return false
            for (i in 0 until keys.length()) {
                val item = keys.optJSONObject(i) ?: continue
                if (item.optString("application_name") == QR_APP_NAME) return true
            }
            false
        } catch (_: Exception) {
            null
        }
    }

    suspend fun revokeKeyQuietly(apiKey: String) {
        if (apiKey.isBlank()) return
        try {
            client.executePostForm(
                path = "/user-api-key/revoke",
                params = emptyMap(),
                extraHeaders = mapOf("User-Api-Key" to apiKey),
                useCsrf = false
            )
        } catch (_: Exception) {
        }
    }

    private fun encodePublicKeyPem(spkiDer: ByteArray): String {
        val b64 = Base64.encodeToString(spkiDer, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN PUBLIC KEY-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64.substring(i, minOf(i + 64, b64.length))).append('\n')
            i += 64
        }
        sb.append("-----END PUBLIC KEY-----")
        return sb.toString()
    }

    private fun decryptRsaPkcs1(base64Cipher: String, privateKey: PrivateKey): String {
        val clean = base64Cipher.replace(Regex("\\s+"), "")
        val cipherBytes = Base64.decode(clean, Base64.DEFAULT)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val plain = cipher.doFinal(cipherBytes)
        return String(plain, Charsets.UTF_8)
    }

    fun generateQrBitmap(content: String, sizePx: Int = 360): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }

    fun decodeQrFromImageBytes(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 1000 || bounds.outHeight / sample > 1000) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val reader = QRCodeReader()
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: Exception) {
                reader.reset()
                return try {
                    reader.decode(BinaryBitmap(HybridBinarizer(InvertedLuminanceSource(source)))).text
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            bmp.recycle()
        }
    }
}
