package dev.r1ptt.herdr.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import dev.r1ptt.R
import dev.r1ptt.herdr.Link
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.ui.Fonts

/**
 * The asleep screen: started as the screen turns off and shown over the lock screen when it wakes.
 * Counts only, never names or questions, since anyone can wake it. A long press wakes the R1: to
 * the briefing if the herd changed while it slept, else back to what was open.
 */
class AsleepActivity : HerdrScreen() {
    override val shadeAllowed = false
    private val d by lazy { Design(this) }
    private var before: Map<String, AgentState> = emptyMap()
    private var shownKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        before = herdr.state.deck.associate { it.sessionId to it.state }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        before = herdr.state.deck.associate { it.sessionId to it.state }
    }

    override fun render() {
        val s = herdr.state
        val (quiet, news) = Briefing.asleep(s.deck)
        val hosts = s.machines.count { it.connected }
        val key = "$quiet$news$hosts${s.link}${herdr.mode}"
        if (key == shownKey) return
        shownKey = key
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(28), d.px(28), d.px(28), d.px(24))
            isLongClickable = true
            setOnLongClickListener { wake(); true }
            contentDescription = "Asleep. $quiet $news Long press to wake."
        }
        root.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            typeface = Fonts.display(context, 700)
            setTextColor(getColor(R.color.cream))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d.px(96).toFloat())
            letterSpacing = -.05f
            includeFontPadding = false
        })
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        root.addView(LinearLayout(this).apply {
            addView(TextClock(context).apply {
                format24Hour = "EEE · MMM d"; format12Hour = "EEE · MMM d"
                isAllCaps = true
                typeface = Fonts.mono(context, 600)
                letterSpacing = .12f
                setTextColor(getColor(R.color.dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d.px(13).toFloat())
                includeFontPadding = false
            })
            if (battery in 0..100) addView(d.text(" · $battery%", 13, R.color.dim, 600, mono = true).apply { letterSpacing = .12f })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(4) })

        root.addView(FrameLayout(this).apply {
            addView(View(context).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(getColor(R.color.field)) } },
                FrameLayout.LayoutParams(d.px(150), d.px(14), Gravity.CENTER_HORIZONTAL).apply { topMargin = d.px(180) })
            addView(ImageView(context).apply { setImageResource(R.drawable.sheep_plain_sleep); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(d.px(190), d.px(190), Gravity.CENTER_HORIZONTAL))
            addView(d.text("z z z", 30, R.color.faint, 700).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { marginEnd = d.px(96 - 28); topMargin = -d.px(6) })
        }, LinearLayout.LayoutParams(-1, d.px(196)).apply { topMargin = d.px(60) })
        val promise = if (herdr.mode == "Pocket") "I’ll catch up when you wake me." else "I’ll ping you if anyone needs you."
        root.addView(d.text(SpannableStringBuilder("$quiet ").apply {
            val start = length
            append(news)
            setSpan(ForegroundColorSpan(getColor(R.color.cream)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n$promise")
        }, 22, R.color.dim, 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(16) })

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val live = s.link == Link.LIVE
            addView(d.text(if (live) "$hosts ${if (hosts == 1) "HOST" else "HOSTS"}" else s.link.label.uppercase(), 14, if (live) R.color.ok else R.color.dim, 600, mono = true).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(ui.dot(if (live) R.color.ok else R.color.faint, 8), null, null, null)
                compoundDrawablePadding = d.px(8)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.text(SpannableStringBuilder("HOLD TO WAKE").apply {
                setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 14, R.color.dim, 600, mono = true))
        })
        setContentView(root)
    }

    private fun wake() {
        val next = { if (Briefing.changed(before, herdr.state.deck)) open(BriefingActivity::class.java); finish() }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return next()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = next()
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = wake()

    companion object {
        /** Called as the screen turns off: the next wake lands here. Paired R1s only; there is no herd otherwise. */
        fun show(context: Context) {
            val app = context.applicationContext as? dev.r1ptt.App ?: return
            if (!app.herdr.paired) return
            runCatching {
                context.startActivity(Intent(context, AsleepActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
    }
}
