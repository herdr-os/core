package dev.r1ptt.herdr.ui

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R

/** A v2 screen's top: a 64 px round back (or close) button, the title, and a mono line under it. */
fun Design.header(title: String, subtitle: String?, icon: Int = R.drawable.ic_back, onBack: () -> Unit) = LinearLayout(context).apply {
    gravity = Gravity.CENTER_VERTICAL
    addView(round(icon, onBack))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 28, R.color.cream, 700).apply { letterSpacing = -.02f; maxLines = 1 })
        subtitle?.let { addView(text(it.uppercase(), 14, R.color.dim, 500, mono = true).apply { maxLines = 1; setPadding(0, px(3), 0, 0) }) }
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(16) })
}

/** The 64 px round back or close button. */
fun Design.round(icon: Int = R.drawable.ic_back, onClick: () -> Unit) = ImageView(context).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(context.getColor(R.color.cream))
    scaleType = ImageView.ScaleType.CENTER
    background = fill(R.color.field, 32)
    contentDescription = if (icon == R.drawable.ic_close) "Close" else "Back"
    isClickable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(px(64), px(64))
}

/** "WHEEL  pick a subagent": the control in orange, what it does in grey. */
fun Design.hint(control: String, does: String) = LinearLayout(context).apply {
    addView(text(control.uppercase(), 13, R.color.accent, 700, mono = true), LinearLayout.LayoutParams(px(80), -2))
    addView(text(does.uppercase(), 13, R.color.dim, 400, mono = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(18) })
}

/** A small filled mark: a square for who, a dot for state. */
fun Design.mark(color: Int, size: Int, round: Boolean = false) = View(context).apply {
    background = fill(color, if (round) size / 2 else 3)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(px(size), px(size))
}

/** Elapsed time the way a stopwatch shows it: 0:09, 4:51, 1h 02. */
fun elapsed(seconds: Long): String = when {
    seconds < 0 -> "0:00"
    seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
    else -> "%dh %02d".format(seconds / 3600, seconds % 3600 / 60)
}
