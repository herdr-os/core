package dev.r1ptt.herdr.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Herdr OS's micro-interactions, in one place so every screen moves the same way. Durations are
 * what you feel at the R1's 0.5 animator scale; the system scale (and "Remove animations") still
 * applies, and haptics follow the system haptics setting.
 */
object Motion {
    private val settle = DecelerateInterpolator(2f)
    private val spring = OvershootInterpolator(3f)

    /** A tappable surface sinks a little under the finger and springs back; a click is felt. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(view: View, depth: Float = 0.96f): View = view.apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(depth).scaleY(depth).setDuration(120).setInterpolator(settle).start()
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(spring).start()
                    if (v.isClickable) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(settle).start()
            }
            false // the click itself still goes to the view's own listener
        }
    }

    /** One wheel detent that moved something: a light tick under the thumb. */
    fun tick(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    /** Something went through (an answer, a message): a firmer confirmation. */
    fun confirm(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    /** The children of [group] rise into place one after another; for a screen's first build only. */
    fun arrive(group: ViewGroup, from: Int = 0, step: Long = 40) {
        val lift = 14 * group.resources.displayMetrics.density
        for (i in from until group.childCount) {
            val c = group.getChildAt(i)
            c.alpha = 0f; c.translationY = lift
            c.animate().alpha(1f).translationY(0f).setStartDelay((i - from) * step).setDuration(440).setInterpolator(settle).start()
        }
    }

    /** A new chat bubble slides in from its speaker's side. */
    fun bubbleIn(view: View, fromRight: Boolean) {
        val d = 28 * view.resources.displayMetrics.density
        view.alpha = 0f; view.translationX = if (fromRight) d else -d; view.scaleX = .96f; view.scaleY = .96f
        view.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(spring).start()
    }
}
