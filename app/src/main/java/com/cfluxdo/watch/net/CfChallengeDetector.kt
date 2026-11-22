package com.cfluxdo.watch.net

/**
 * Exact Cloudflare challenge detection ported from CFluxDo CfChallengeClassifier.
 */
object CfChallengeDetector {
    private val challengeCodes = setOf(403, 429, 503)

    fun isChallenge(
        statusCode: Int,
        cfMitigated: String?,
        contentType: String?,
        body: String?
    ): Boolean {
        if (cfMitigated != null && cfMitigated.lowercase().contains("challenge")) {
            return true
        }
        if (statusCode !in challengeCodes) return false
        val type = contentType?.lowercase().orEmpty()
        if (type.isNotEmpty() && !type.contains("text/html")) return false
        return hasChallengeMarkers(body)
    }

    fun hasChallengeMarkers(body: String?): Boolean {
        if (body.isNullOrEmpty()) return false
        if (body.contains("cf_chl_opt")) return true
        if (body.contains("challenge-platform") && body.contains("cloudflare")) return true
        if (body.contains("Just a moment") &&
            (body.contains("cloudflare") || body.contains("cf-challenge"))
        ) return true
        return false
    }
}

class CfChallengeException(message: String = "需要完成 Cloudflare 验证") : Exception(message)
class AuthRequiredException(message: String = "需要登录账号") : Exception(message)
