package dev.r1ptt.herdr.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.audio.Speaker
import java.time.LocalTime

/**
 * The companion's briefing: the herd in one breath, read aloud when Herdr may speak, with the one
 * agent worth looking at. Opened after a wake when something changed, or from the pasture menu.
 */
class BriefingActivity : HerdrScreen() {
    private val d by lazy { Design(this) }
    private var speaker: Speaker? = null
    private var spoken = false
    private var shownKey = ""

    override fun onResume() {
        super.onResume()
        // Spoken once per opening, and only when Herdr may speak aloud; without a voice it is a quiet card.
        if (spoken) return
        spoken = true
        if (!herdr.speakAlerts) return
        speaker = herdr.speak(Briefing.of(herdr.state.deck, LocalTime.now()).text) { runOnUiThread { speaker = null; shownKey = ""; render() } }
        shownKey = ""
        render()
    }

    override fun onPause() {
        hush()
        super.onPause()
    }

    private fun hush() { speaker?.stop(); speaker = null; shownKey = ""; render() }

    override fun render() {
        val b = Briefing.of(herdr.state.deck, LocalTime.now())
        val key = "${b.text}${b.about?.sessionId}${speaker != null}"
        if (key == shownKey) return
        shownKey = key
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(26), d.px(26), d.px(26), d.px(22))
            isClickable = true
            setOnClickListener { hush() }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (speaker != null) View.VISIBLE else View.INVISIBLE
            listOf(8, 16, 11, 14, 6).forEach { h -> addView(View(context).apply { background = d.fill(R.color.accent, 2) }, LinearLayout.LayoutParams(d.px(3), d.px(h)).apply { marginEnd = d.px(3) }) }
            addView(d.text("SPEAKING", 14, R.color.accent, 700, mono = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(7) })
            addView(d.text("TAP TO HUSH", 14, R.color.dim, 600, mono = true))
        })
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(getColor(R.color.cream))
                val r = d.px(28).toFloat(); val tail = d.px(8).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, r, r, tail, tail)
            }
            setPadding(d.px(24), d.px(22), d.px(24), d.px(22))
            addView(d.text(SpannableStringBuilder("${b.lead} ").apply {
                val start = length
                append(b.point)
                setSpan(android.text.style.TypefaceSpan(dev.r1ptt.ui.Fonts.display(this@BriefingActivity, 700)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 28, R.color.on_accent, 500).apply { setLineSpacing(0f, 1.08f) })
            if (b.harnesses.isNotEmpty()) addView(LinearLayout(context).apply {
                b.harnesses.forEach { addView(d.logoChip(it, 34), LinearLayout.LayoutParams(d.px(34), d.px(34)).apply { marginEnd = d.px(8) }) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(14) })
        })
        root.addView(FrameLayout(this).apply {
            addView(ImageView(context).apply { setImageResource(R.drawable.sheep_plain); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(d.px(150), d.px(150), Gravity.BOTTOM).apply { marginStart = -d.px(8) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                b.about?.let { a ->
                    addView(d.pill("Show me ${Briefing.short(a)}", fill = if (b.waiting) R.color.accent else R.color.cream, height = 56) {
                        hush(); finish()
                        open(if (a.pendingApprovalId != null && !a.stale) DecisionActivity::class.java else AgentActivity::class.java, a.sessionId)
                    }, LinearLayout.LayoutParams(-2, -2))
                }
                addView(d.pill("Later", fill = R.color.raised, ink = R.color.cream, height = 56) { hush(); finish() },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(10) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = d.px(10) })
        }, LinearLayout.LayoutParams(-1, d.px(150)).apply { topMargin = -d.px(6) })
        setContentView(root)
    }
}
