package com.cfluxdo.watch

import com.cfluxdo.watch.net.CfChallengeDetector
import com.cfluxdo.watch.net.ClientHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test
    fun clientHintsMatchChromiumGrease() {
        val ua = "Mozilla/5.0 (Linux; Android 14; OPPO Watch X2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val hints = ClientHints.headersFor(ua)
        assertEquals("?1", hints["sec-ch-ua-mobile"])
        assertEquals("\"Android\"", hints["sec-ch-ua-platform"])
        assertTrue(hints["sec-ch-ua"]!!.contains("\"Chromium\";v=\"131\""))
        assertTrue(hints["sec-ch-ua"]!!.contains("\"Android WebView\";v=\"131\""))
    }

    @Test
    fun sanitizeRemovesWebViewToken() {
        val raw = "Mozilla/5.0 (Linux; Android 14; Watch; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"
        val clean = ClientHints.sanitizeUserAgent(raw)
        assertFalse(clean.contains("; wv)"))
        assertFalse(clean.contains("Version/4.0"))
        assertTrue(clean.contains("Chrome/124.0.0.0"))
    }

    @Test
    fun cfChallengeDetectionMatchesUpstream() {
        assertTrue(CfChallengeDetector.isChallenge(403, "challenge", "text/plain", ""))
        assertTrue(CfChallengeDetector.isChallenge(403, null, "text/html; charset=utf-8", "<script>window._cf_chl_opt={}</script>"))
        assertFalse(CfChallengeDetector.isChallenge(403, null, "application/json", "{\"errors\":[\"BAD CSRF\"]}"))
        assertFalse(CfChallengeDetector.isChallenge(404, null, "text/html", "cf_chl_opt"))
    }
}
