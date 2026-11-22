package com.cfluxdo.watch.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.ScrollView
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import androidx.recyclerview.widget.RecyclerView
import com.cfluxdo.watch.WatchApp
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Pure rotary math helpers (Android-independent for JVM unit tests).
 */
internal object RotaryScrollMath {
    /**
     * Extracts vertical scroll axis according to Wear OS / Android input specs:
     * - SOURCE_ROTARY_ENCODER -> AXIS_SCROLL (fallback AXIS_VSCROLL for OEM quirks)
     * - SOURCE_CLASS_POINTER / SOURCE_MOUSE -> AXIS_VSCROLL (fallback AXIS_SCROLL)
     */
    fun extractScrollAxis(
        isRotaryEncoder: Boolean,
        axisScroll: Float,
        axisVScroll: Float
    ): Float {
        return if (isRotaryEncoder) {
            if (axisScroll != 0f) axisScroll else axisVScroll
        } else {
            if (axisVScroll != 0f) axisVScroll else axisScroll
        }
    }

    /**
     * Resolves vertical scroll factor in pixels per axis unit, guarding against 0/negative OEM values.
     */
    fun resolveScrollFactor(systemFactor: Float, density: Float): Float {
        return if (systemFactor > 0f) systemFactor else 64f * density.coerceAtLeast(1f)
    }
}

/**
 * Per-view rotary state:
 * - Preserves sub-pixel fractional deltas so high-resolution optical crowns (OPPO Watch X2)
 *   never drop slow micro-rotations.
 * - Throttles mechanical detent haptics by accumulated scroll distance & cooldown.
 * - Emits a single boundary tick when hitting top/bottom overscroll limits.
 */
internal class RotaryScrollAccumulator(
    private val tickStepPx: Float,
    private val minTickIntervalMs: Long = 35L,
    private val idleResetMs: Long = 300L
) {
    private var subPixelRemainder: Float = 0f
    private var hapticDistancePx: Float = 0f
    private var lastEventTimeMs: Long = -1L
    private var lastTickTimeMs: Long = -1L
    private var lastDirection: Int = 0
    private var edgeHitDirection: Int = 0

    data class StepResult(
        val scrollPixels: Int,
        val shouldTickHaptic: Boolean,
        val shouldEdgeHaptic: Boolean
    )

    fun isDuplicate(eventTimeMs: Long): Boolean {
        return eventTimeMs > 0L && eventTimeMs == lastEventTimeMs
    }

    fun consume(
        rawPixelDelta: Float,
        eventTimeMs: Long,
        canScrollInDirection: Boolean
    ): StepResult {
        if (rawPixelDelta == 0f) {
            return StepResult(0, shouldTickHaptic = false, shouldEdgeHaptic = false)
        }

        val dir = rawPixelDelta.sign.toInt()
        val idle = lastEventTimeMs > 0L && (eventTimeMs - lastEventTimeMs) > idleResetMs
        if (idle || (lastDirection != 0 && dir != lastDirection)) {
            subPixelRemainder = 0f
            hapticDistancePx = 0f
            edgeHitDirection = 0
        }
        lastEventTimeMs = eventTimeMs
        lastDirection = dir

        if (!canScrollInDirection) {
            subPixelRemainder = 0f
            val emitEdge = edgeHitDirection != dir &&
                (lastTickTimeMs < 0L || (eventTimeMs - lastTickTimeMs) >= minTickIntervalMs)
            if (emitEdge) {
                edgeHitDirection = dir
                lastTickTimeMs = eventTimeMs
            }
            return StepResult(0, shouldTickHaptic = false, shouldEdgeHaptic = emitEdge)
        }

        edgeHitDirection = 0
        subPixelRemainder += rawPixelDelta
        val wholePx = subPixelRemainder.toInt()
        subPixelRemainder -= wholePx

        var emitTick = false
        if (wholePx != 0) {
            hapticDistancePx += abs(wholePx)
            if (hapticDistancePx >= tickStepPx &&
                (lastTickTimeMs < 0L || (eventTimeMs - lastTickTimeMs) >= minTickIntervalMs)
            ) {
                emitTick = true
                hapticDistancePx = 0f
                lastTickTimeMs = eventTimeMs
            }
        }
        return StepResult(wholePx, shouldTickHaptic = emitTick, shouldEdgeHaptic = false)
    }
}

/**
 * Official Wear OS / OPPO Watch X2 rotary crown (旋转表冠 / 滚轮) adapter.
 *
 * Follows Android Wear OS rotary input guidelines:
 * 1. Captures MotionEvent.ACTION_SCROLL from InputDeviceCompat.SOURCE_ROTARY_ENCODER
 *    (MotionEventCompat.AXIS_SCROLL) and pointer/mouse fallbacks (AXIS_VSCROLL).
 * 2. Scales by ViewConfigurationCompat.getScaledVerticalScrollFactor with fractional accumulation.
 * 3. Guarantees focus retention AND Activity-level onGenericMotionEvent fallback so focus loss
 *    on buttons, dialogs, or SwipeRefreshLayout never breaks crown scrolling.
 * 4. Triggers Android 14 / Wear OS 5 linear-motor haptic detents (SEGMENT_FREQUENT_TICK /
 *    GESTURE_END, falling back to CLOCK_TICK) with distance + cooldown throttling.
 */
object RotaryHelper {
    private const val SCROLL_MULTIPLIER = 1.15f
    private const val TICK_STEP_DP = 28f

    private val accumulators = WeakHashMap<View, RotaryScrollAccumulator>()

    fun attachToRecyclerView(rv: RecyclerView) {
        prepareFocusable(rv)
        rv.setOnGenericMotionListener { v, ev ->
            handleGenericMotion(v, ev)
        }
    }

    fun attachToScrollView(sv: ScrollView) {
        prepareFocusable(sv)
        sv.setOnGenericMotionListener { v, ev ->
            handleGenericMotion(v, ev)
        }
    }

    fun attachToWebView(wv: WebView) {
        prepareFocusable(wv)
        wv.setOnGenericMotionListener { v, ev ->
            handleGenericMotion(v, ev)
        }
    }

    /**
     * Restores focus to the primary scrollable view on onResume / onWindowFocusChanged.
     */
    fun ensureFocus(view: View?) {
        if (view == null) return
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.post {
            if (view.isAttachedToWindow && !view.hasFocus()) {
                view.requestFocus()
            }
        }
    }

    /**
     * Activity-level fallback called from Activity.onGenericMotionEvent(event).
     * Handles rotary events even if a button, SwipeRefreshLayout, or dismissed dialog stole focus.
     */
    fun handleActivityGenericMotion(targetView: View?, event: MotionEvent?): Boolean {
        if (targetView == null || event == null) return false
        val handled = handleGenericMotion(targetView, event)
        if (handled && !targetView.hasFocus()) {
            targetView.requestFocus()
        }
        return handled
    }

    private fun prepareFocusable(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
    }

    private fun handleGenericMotion(view: View, ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_SCROLL) return false

        val isRotary = ev.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)
        val isPointer = ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) ||
            ev.isFromSource(InputDevice.SOURCE_MOUSE)
        if (!isRotary && !isPointer) return false

        val rawAxis = RotaryScrollMath.extractScrollAxis(
            isRotaryEncoder = isRotary,
            axisScroll = ev.getAxisValue(MotionEventCompat.AXIS_SCROLL),
            axisVScroll = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
        )
        if (rawAxis == 0f) return false

        val acc = accumulators.getOrPut(view) {
            val density = view.resources.displayMetrics.density
            RotaryScrollAccumulator(tickStepPx = TICK_STEP_DP * density)
        }
        if (acc.isDuplicate(ev.eventTime)) {
            return true
        }

        val density = view.resources.displayMetrics.density
        val sysFactor = ViewConfigurationCompat.getScaledVerticalScrollFactor(
            ViewConfiguration.get(view.context),
            view.context
        )
        val scrollFactor = RotaryScrollMath.resolveScrollFactor(sysFactor, density)
        val prefs = try {
            WatchApp.instance.prefs
        } catch (_: Exception) {
            null
        }
        val sign = if (prefs?.rotaryReverse == true) 1f else -1f
        val rawPixelDelta = sign * rawAxis * scrollFactor * SCROLL_MULTIPLIER
        val direction = if (rawPixelDelta > 0f) 1 else -1

        val canScroll = canScrollVertically(view, direction)
        val step = acc.consume(
            rawPixelDelta = rawPixelDelta,
            eventTimeMs = ev.eventTime,
            canScrollInDirection = canScroll
        )

        if (step.scrollPixels != 0) {
            performScroll(view, step.scrollPixels)
        }

        if (prefs?.rotaryHaptics != false) {
            if (step.shouldEdgeHaptic) {
                performEdgeHaptic(view)
            } else if (step.shouldTickHaptic) {
                performTickHaptic(view)
            }
        }
        return true
    }

    private fun canScrollVertically(view: View, direction: Int): Boolean {
        return when (view) {
            is RecyclerView -> view.canScrollVertically(direction)
            is ScrollView -> view.canScrollVertically(direction)
            is WebView -> view.canScrollVertically(direction)
            else -> view.canScrollVertically(direction)
        }
    }

    private fun performScroll(view: View, dy: Int) {
        when (view) {
            is RecyclerView -> view.scrollBy(0, dy)
            is ScrollView -> view.scrollBy(0, dy)
            is WebView -> {
                val targetY = (view.scrollY + dy).coerceAtLeast(0)
                view.scrollTo(view.scrollX, targetY)
            }
            else -> view.scrollBy(0, dy)
        }
    }

    private fun performTickHaptic(view: View) {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(flag)
    }

    private fun performEdgeHaptic(view: View) {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(flag)
    }

    fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).roundToInt()
}
