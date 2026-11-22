package com.cfluxdo.watch.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import androidx.core.view.ViewConfigurationCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Enables smooth rotating crown (表冠旋钮) scrolling on OPPO Watch X2 / Wear OS.
 */
object RotaryHelper {
    fun attachToRecyclerView(rv: RecyclerView) {
        rv.isFocusable = true
        rv.isFocusableInTouchMode = true
        rv.requestFocus()
        val scrollFactor = ViewConfigurationCompat.getScaledVerticalScrollFactor(
            ViewConfiguration.get(rv.context),
            rv.context
        )
        rv.setOnGenericMotionListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL &&
                (ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) ||
                    ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER))
            ) {
                val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor * 1.35f
                (v as RecyclerView).scrollBy(0, delta.roundToInt())
                true
            } else {
                false
            }
        }
    }

    fun attachToScrollView(sv: ScrollView) {
        sv.isFocusable = true
        sv.isFocusableInTouchMode = true
        sv.requestFocus()
        val scrollFactor = ViewConfigurationCompat.getScaledVerticalScrollFactor(
            ViewConfiguration.get(sv.context),
            sv.context
        )
        sv.setOnGenericMotionListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL &&
                (ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) ||
                    ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER))
            ) {
                val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor * 1.35f
                (v as ScrollView).scrollBy(0, delta.roundToInt())
                true
            } else {
                false
            }
        }
    }

    fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).roundToInt()
}
