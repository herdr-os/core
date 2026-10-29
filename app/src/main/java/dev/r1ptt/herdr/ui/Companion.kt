package dev.r1ptt.herdr.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.r1ptt.R
import dev.r1ptt.ui.DeviceUi
import dev.r1ptt.ui.Fonts

/** A next step the companion offers: a label and what it does. */
data class Step(val label: String, val go: () -> Unit)

/** "Plain words. Bold words." in one run, as the companion stresses the part that matters. */
fun boldTail(c: Context, plain: String, bold: String): CharSequence = SpannableStringBuilder("$plain ").apply {
    val start = length
    append(bold)
    setSpan(TypefaceSpan(Fonts.display(c, 700)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** The design's page: a 64 px round back button beside the title and a mono line, then a scrolling body. */
fun designPage(c: Context, d: Design, title: String, sub: String?, onBack: () -> Unit): DeviceUi.Page {
    val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.getColor(R.color.bg)) }
    val header = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d.px(22), d.px(22), d.px(22), d.px(12))
        addView(ImageView(c).apply {
            setImageDrawable(c.getDrawable(R.drawable.ic_back)!!.mutate().apply { setTint(c.getColor(R.color.cream)) })
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.getColor(R.color.raised)) }
            contentDescription = "Back"
            isClickable = true
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(d.px(64), d.px(64)))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(title, 28, R.color.cream, 700).apply { letterSpacing = -.02f; maxLines = 2 })
            sub?.let { addView(d.text(it.uppercase(), 13, R.color.dim, 400, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0) }) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(16) })
    }
    root.addView(header)
    val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d.px(22), 0, d.px(22), d.px(22)) }
    val scroll = ScrollView(c).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    return DeviceUi.Page(root, body, scroll, header)
}

/** The companion's cream bubble. Its one square corner points down at the companion below it. */
fun speech(c: Context, d: Design, headline: CharSequence?, line: CharSequence?) = LinearLayout(c).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        setColor(c.getColor(R.color.cream))
        val r = d.px(26).toFloat(); val corner = d.px(6).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, r, r, corner, corner)
    }
    setPadding(d.px(22), d.px(18), d.px(22), d.px(18))
    headline?.let { addView(d.text(it, 22, R.color.on_accent, 700).apply { setLineSpacing(0f, 1.1f) }) }
    line?.let {
        addView(d.text(it, 18, R.color.on_accent, 500).apply {
            if (headline != null) { alpha = .78f; setPadding(0, d.px(6), 0, 0) }
            setLineSpacing(0f, 1.15f)
        })
    }
}

/** A secondary next step: a dark pill in cream type. Still 56 px tall, so it is easy to hit. */
fun quietPill(d: Design, label: String, go: () -> Unit) = d.pill(label, R.color.raised, R.color.cream, height = 56, action = go).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, d.px(16).toFloat())
    setPadding(d.px(20), 0, d.px(20), 0)
}

/**
 * The companion speaking at the foot of the screen. With [withSheep] the companion stands at the
 * left under its bubble and the next steps stack at the right; without it (the companion is already
 * on the field) the steps sit in one row, the first one widest.
 */
fun companionScene(c: Context, d: Design, headline: CharSequence?, line: CharSequence?, primary: Step?, secondary: Step? = null, withSheep: Boolean = true) =
    LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(speech(c, d, headline, line), LinearLayout.LayoutParams(-1, -2))
        if (withSheep) addView(LinearLayout(c).apply {
            gravity = Gravity.BOTTOM
            addView(ImageView(c).apply { setImageResource(R.drawable.sheep_plain); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(d.px(110), d.px(110)).apply { marginStart = -d.px(6) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                primary?.let { addView(d.pill(it.label, height = 56) { it.go() }, LinearLayout.LayoutParams(-2, -2)) }
                secondary?.let { addView(quietPill(d, it.label, it.go), LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(8) }) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = d.px(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(6) })
        else addView(LinearLayout(c).apply {
            primary?.let { addView(d.pill(it.label, height = 58) { it.go() }, LinearLayout.LayoutParams(0, -2, 1f)) }
            secondary?.let { addView(quietPill(d, it.label, it.go).apply { minHeight = d.px(58) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(10) }) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(12) })
    }

/** Pins [view] to the bottom of a full-screen frame with the design's 26 px side and 22 px bottom margins. */
fun FrameLayout.addAtFoot(d: Design, view: View) =
    addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d.px(26), 0, d.px(26), d.px(22)) })

/** A settings row with its own on/off switch: cream track when on. The whole row toggles. */
fun toggleRow(c: Context, d: Design, title: String, sub: String?, on: Boolean, changed: (Boolean) -> Unit): LinearLayout = LinearLayout(c).apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = d.px(64)
    setPadding(d.px(6), d.px(12), d.px(6), d.px(12))
    addView(LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(d.text(title, 18, R.color.cream, 700))
        sub?.let { addView(d.text(it, 13, R.color.dim, 400, mono = true).apply { setPadding(0, d.px(3), 0, 0) }) }
    }, LinearLayout.LayoutParams(0, -2, 1f))
    val track = FrameLayout(c).apply {
        background = d.fill(if (on) R.color.cream else R.color.raised, 16)
        addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.getColor(if (on) R.color.on_accent else R.color.faint)) } },
            FrameLayout.LayoutParams(d.px(24), d.px(24), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply { setMargins(d.px(4), 0, d.px(4), 0) })
    }
    addView(track, LinearLayout.LayoutParams(d.px(48), d.px(32)))
    contentDescription = title
    stateDescription = if (on) "On" else "Off"
    isClickable = true
    setOnClickListener { changed(!on) }
}
