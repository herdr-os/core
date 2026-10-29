package dev.r1ptt.herdr.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.R
import dev.r1ptt.ui.Fonts
import kotlin.math.roundToInt

/**
 * The Paper designs are drawn in the R1 panel's own 480×640 pixels. This maps those numbers onto
 * the screen at hand, so a 13 px label in Paper is 13 px on the R1 whatever the density.
 * Type scale: 44 / 28 / 22 headings, 18 / 16 body, 14 / 13 labels. Nothing smaller than 13.
 */
class Design(val context: Context) {
    private val k = context.resources.displayMetrics.widthPixels / 480f

    fun px(v: Number): Int = (v.toFloat() * k).roundToInt()

    fun text(value: CharSequence, size: Int, color: Int = R.color.fg, weight: Int = 400, mono: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size.coerceAtLeast(13) * k)
        typeface = if (mono) Fonts.mono(context, weight) else Fonts.display(context, weight)
        if (mono) letterSpacing = .08f
        setTextColor(context.getColor(color))
        includeFontPadding = false
    }

    /** "Main sentence. Muted aside." in one run, the way the companion speaks. */
    fun twoTone(main: String, aside: String, size: Int, mainColor: Int = R.color.fg, asideColor: Int = R.color.dim) =
        text(SpannableStringBuilder("$main ").apply {
            val start = length
            append(aside)
            setSpan(ForegroundColorSpan(context.getColor(asideColor)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, size, mainColor, weight = 700).apply { setLineSpacing(0f, 1.08f) }

    fun fill(color: Int, radius: Int) = GradientDrawable().apply { setColor(context.getColor(color)); cornerRadius = px(radius).toFloat() }

    /** A full-round button. Orange only when it answers something waiting on you; plain steps are cream. */
    fun pill(label: String, fill: Int = R.color.cream, ink: Int = R.color.on_accent, height: Int = 60, action: () -> Unit) =
        text(label, 18, ink, weight = 700).apply {
            gravity = Gravity.CENTER
            minHeight = px(height)
            setPadding(px(22), 0, px(22), 0)
            background = fill(fill, height / 2)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    /** A harness's logo on a rounded square of its brand color. */
    fun logoChip(h: Harness, size: Int, logo: Int = size * 11 / 20) = android.widget.FrameLayout(context).apply {
        background = fill(h.color, size * 5 / 16)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(android.widget.ImageView(context).apply {
            setImageResource(h.logo)
            setColorFilter(context.getColor(h.ink))
        }, android.widget.FrameLayout.LayoutParams(px(logo), px(logo), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(px(size), px(size))
    }

    /** A detail screen's top: the 64 px back button, the title, and a mono line under it. */
    fun header(title: String, sub: CharSequence, back: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(android.widget.ImageButton(context).apply {
            setImageDrawable(context.getDrawable(R.drawable.ic_back)!!.mutate().apply { setTint(context.getColor(R.color.cream)) })
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.raised)) }
            contentDescription = "Back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(px(64), px(64)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 28, R.color.cream, 700))
            addView(text(sub, 14, R.color.dim, 500, mono = true).apply { setPadding(0, px(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(16) })
    }

    /** What a sheep or the companion says: a rounded bubble in the speaker's color. */
    fun bubble(color: Int, padding: Int = 18) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = fill(color, 24)
        setPadding(px(padding + 2), px(padding - 4), px(padding + 2), px(padding - 2))
    }
}
