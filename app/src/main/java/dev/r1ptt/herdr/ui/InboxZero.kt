package dev.r1ptt.herdr.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R

/**
 * The inbox with nothing open: the companion resting on its shadow and one line about the herd.
 * [working] is how many agents are at work right now. Fills its parent; the caller keeps its own
 * header and wheel hint.
 */
fun inboxZero(c: Context, d: Design, working: Int): View = LinearLayout(c).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    addView(FrameLayout(c).apply {
        addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.getColor(R.color.field_day)) } },
            FrameLayout.LayoutParams(d.px(130), d.px(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        addView(ImageView(c).apply { setImageResource(R.drawable.sheep_plain); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(d.px(120), d.px(120), Gravity.CENTER_HORIZONTAL))
    }, LinearLayout.LayoutParams(d.px(140), d.px(132)))
    addView(d.text("Nothing needs you.", 28, R.color.fg, 700).apply { gravity = Gravity.CENTER; setPadding(0, d.px(24), 0, d.px(6)) })
    val line = when (working) {
        0 -> "The herd is resting. I’ll call you."
        1 -> "1 sheep is working. I’ll call you."
        else -> "$working sheep are working. I’ll call you."
    }
    addView(d.text(line, 18, R.color.dim, 500).apply { gravity = Gravity.CENTER })
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
}
