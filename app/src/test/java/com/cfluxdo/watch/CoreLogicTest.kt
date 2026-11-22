package com.cfluxdo.watch

import com.cfluxdo.watch.net.CfChallengeDetector
import com.cfluxdo.watch.net.ClientHints
import com.cfluxdo.watch.ui.RotaryScrollAccumulator
import com.cfluxdo.watch.ui.RotaryScrollMath
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

    @Test
    fun rotaryAxisExtractionSupportsEncoderAndPointerFallback() {
        assertEquals(0.5f, RotaryScrollMath.extractScrollAxis(true, 0.5f, 0f), 0.0001f)
        assertEquals(-0.25f, RotaryScrollMath.extractScrollAxis(true, 0f, -0.25f), 0.0001f)
        assertEquals(1.0f, RotaryScrollMath.extractScrollAxis(false, 0f, 1.0f), 0.0001f)
        assertEquals(-0.75f, RotaryScrollMath.extractScrollAxis(false, -0.75f, 0f), 0.0001f)
        assertEquals(128f, RotaryScrollMath.resolveScrollFactor(0f, 2f), 0.0001f)
        assertEquals(96f, RotaryScrollMath.resolveScrollFactor(96f, 2f), 0.0001f)
    }

    @Test
    fun rotaryAccumulatorPreservesSubPixelAndThrottlesHaptics() {
        val acc = RotaryScrollAccumulator(tickStepPx = 20f, minTickIntervalMs = 35L, idleResetMs = 300L)

        // Sub-pixel micro-rotations accumulate instead of dropping to 0
        val s1 = acc.consume(0.4f, eventTimeMs = 100L, canScrollInDirection = true)
        assertEquals(0, s1.scrollPixels)
        val s2 = acc.consume(0.4f, eventTimeMs = 110L, canScrollInDirection = true)
        assertEquals(0, s2.scrollPixels)
        val s3 = acc.consume(0.4f, eventTimeMs = 120L, canScrollInDirection = true)
        assertEquals(1, s3.scrollPixels)
        assertFalse(s3.shouldTickHaptic)

        // Reaching tickStepPx triggers haptic tick; immediate next event is throttled by cooldown
        val s4 = acc.consume(25f, eventTimeMs = 150L, canScrollInDirection = true)
        assertEquals(25, s4.scrollPixels)
        assertTrue(s4.shouldTickHaptic)

        val s5 = acc.consume(25f, eventTimeMs = 160L, canScrollInDirection = true)
        assertEquals(25, s5.scrollPixels)
        assertFalse(s5.shouldTickHaptic)

        // Hitting scroll edge triggers boundary haptic only once per edge
        val e1 = acc.consume(10f, eventTimeMs = 210L, canScrollInDirection = false)
        assertEquals(0, e1.scrollPixels)
        assertTrue(e1.shouldEdgeHaptic)

        val e2 = acc.consume(10f, eventTimeMs = 260L, canScrollInDirection = false)
        assertFalse(e2.shouldEdgeHaptic)
    }
}
