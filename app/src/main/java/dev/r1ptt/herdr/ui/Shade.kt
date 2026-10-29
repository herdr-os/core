package dev.r1ptt.herdr.ui

import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.BatteryManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import dev.r1ptt.App
import dev.r1ptt.R
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.herdr.Link
import dev.r1ptt.power.DeviceSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Herdr OS's own pull-down shade, in place of Android's: the clock and battery, the herd at a
 * glance, the one thing waiting on you, four shortcuts and the device toggles. A drag down from the
 * top band of any Herdr screen pulls it with the finger; past halfway it opens. The wheel moves
 * between its controls, a tap acts, and a drag up or Back closes it.
 */
class Shade(private val screen: HerdrScreen) {
    private val d = Design(screen)
    private val app get() = screen.application as App
    private var overlay: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var focusable = listOf<View>()
    private var focus = -1
    private var startY = -1f
    private var dragging = false
    private var pulled = 0f
    private val slop = ViewConfiguration.get(screen).scaledTouchSlop * 2

    val isOpen get() = overlay?.parent != null

    /** Watches every touch on the screen; returns true while a pull from the top belongs to the shade. */
    fun intercept(e: MotionEvent): Boolean {
        if (isOpen && !dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startY = if (e.y < d.px(110)) e.y else -1f; dragging = false }
            MotionEvent.ACTION_MOVE -> if (startY >= 0) {
                val dy = e.y - startY
                if (!dragging && dy > slop) {
                    dragging = true
                    // The screen underneath stops seeing this gesture.
                    screen.superDispatchTouch(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
                    attach()
                }
                if (dragging) { pulled = dy; follow(dy); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false; startY = -1f
                // About half the panel's height: past it the shade opens, short of it it goes back.
                if (pulled > d.px(160) && e.actionMasked == MotionEvent.ACTION_UP) settleOpen() else close()
                return true
            }
        }
        return dragging
    }

    fun open() { attach(); panel?.post { settleOpen() } }

    fun close() {
        val o = overlay ?: return
        val p = panel ?: return
        p.animate().translationY(-p.height.toFloat()).setDuration(200).withEndAction { (o.parent as? FrameLayout)?.removeView(o) }.start()
        o.animate().alpha(0f).setDuration(200).start()
    }

    /** Rebuilds the contents when the herd changes while the shade is down. */
    fun refresh() { if (isOpen && !dragging) build() }

    fun wheel(down: Boolean) {
        if (focusable.isEmpty()) return
        focus = (focus + if (down) 1 else -1).coerceIn(0, focusable.size - 1)
        highlight()
    }

    private fun attach() {
        if (isOpen) return
        val content = screen.findViewById<FrameLayout>(android.R.id.content) ?: return
        val o = FrameLayout(screen).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnTouchListener(PanelDrag())
        }
        overlay = o
        content.addView(o, FrameLayout.LayoutParams(-1, -1))
        build()
        panel?.let { p -> p.post { if (dragging) p.translationY = -p.height.toFloat() } }
    }

    private fun follow(dy: Float) {
        val p = panel ?: return
        p.translationY = (dy - p.height).coerceIn(-p.height.toFloat(), 0f)
        overlay?.alpha = (1 + p.translationY / p.height).coerceIn(0f, 1f)
    }

    private fun settleOpen() {
        panel?.animate()?.translationY(0f)?.setDuration(220)?.setInterpolator(android.view.animation.DecelerateInterpolator(2f))?.start()
        overlay?.animate()?.alpha(1f)?.setDuration(220)?.start()
    }

    /** A drag up anywhere on the open shade closes it, following the finger. */
    private inner class PanelDrag : View.OnTouchListener {
        private var y0 = 0f
        private var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = panel ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - y0; if (dy < -slop) moved = true; if (moved) p.translationY = dy.coerceAtMost(0f) }
                // Untouched space on the panel lands here too; a tap below the panel closes.
                MotionEvent.ACTION_UP -> if (moved) { if (-p.translationY > p.height * 0.25f) close() else settleOpen() } else if (e.y > p.height) close()
            }
            return true
        }
    }

    private fun build() {
        val o = overlay ?: return
        val herd = app.herdr.state
        val p = LinearLayout(screen).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF0A0A0A.toInt()); cornerRadii = FloatArray(8) { i -> if (i < 4) 0f else d.px(34).toFloat() } }
            setPadding(d.px(22), d.px(26), d.px(22), d.px(14))
            elevation = d.px(24).toFloat()
        }
        val items = mutableListOf<View>()

        p.addView(clockRow())
        p.addView(status(), lp(top = 14))
        val waiting = herd.deck.firstOrNull { it.state.needsYou && !it.stale && it.pendingApprovalId != null }
        waiting?.let { a ->
            val h = Harness.of(a.kind)
            val q = herd.approval(a.pendingApprovalId)?.question ?: a.state.label
            val card = LinearLayout(screen).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = d.fill(h.color, 24)
                setPadding(d.px(12), d.px(12), d.px(12), d.px(12))
                addView(ImageView(context).apply { setImageResource(h.sheep); background = d.fill(h.color, 18) }, LinearLayout.LayoutParams(d.px(56), d.px(56)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(d.text("${h.label.substringBefore(' ').uppercase()} · ${a.machineLabel.uppercase()}", 13, h.ink, 700, mono = true))
                    addView(d.text(q, 18, h.ink, 700).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(12); marginEnd = d.px(10) })
                addView(d.pill("Answer", fill = R.color.accent, height = 56) { go(DecisionActivity::class.java, a.sessionId) })
            }
            items += card.getChildAt(2)
            p.addView(card, lp(top = 14))
        }

        val shortcuts = LinearLayout(screen).apply { setPadding(d.px(4), 0, d.px(4), 0) }
        val open = herd.deck.count { it.state.needsYou && !it.stale }
        listOf(
            Triple(R.drawable.ic_inbox, "Inbox", HerdActivityFeed::class.java),
            Triple(R.drawable.ic_mic, "Walkie", WalkieActivity::class.java),
            Triple(R.drawable.ic_plus, "New task", DelegateActivity::class.java),
            Triple(R.drawable.sheep_plain, "Briefing", BriefingActivity::class.java),
        ).forEachIndexed { i, (icon, label, target) ->
            val tile = shortcut(icon, label, badge = if (i == 0 && open > 0) "$open" else null, tinted = icon != R.drawable.sheep_plain) { go(target) }
            items += tile
            shortcuts.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
        }
        p.addView(shortcuts, lp(top = 16))

        val cfg = app.store.value
        val voice = cfg.tts.enabled && !cfg.missingSpeechKey
        val online = app.radio.isOnline()
        val hosts = herd.machines.count { it.connected }
        val row1 = pair(
            toggle(R.drawable.ic_wifi, "Wi-Fi", if (online) "Connected" else "Off", online) { go(dev.r1ptt.QuickControlsActivity::class.java) },
            toggle(R.drawable.ic_reach, "Tailscale", if (herd.link == Link.LIVE) "$hosts ${if (hosts == 1) "host" else "hosts"}" else herd.link.label, herd.link == Link.LIVE) {
                screen.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")?.let(screen::startActivity)
            },
        )
        val row2 = pair(
            toggle(R.drawable.ic_speaker, "Speak alerts", if (!voice) "No voice" else if (app.herdr.speakAlerts) "On" else "Off", voice && app.herdr.speakAlerts) {
                if (voice) { app.herdr.speakAlerts = !app.herdr.speakAlerts; build() } else screen.showToast("Set up a voice in Settings first")
            },
            toggle(R.drawable.ic_quiet, "Quiet", if (cfg.quiet) "On" else "Off", cfg.quiet) {
                runCatching { app.store.update { it.copy(quiet = !cfg.quiet) } }.onSuccess { if (it.quiet) app.turns.stopSpeech(); build() }
            },
        )
        items += (0 until 2).map { row1.getChildAt(it) } + (0 until 2).map { row2.getChildAt(it) }
        p.addView(row1, lp(top = 14))
        p.addView(row2, lp(top = 8))
        val bright = brightness()
        p.addView(bright, lp(top = 10))
        p.addView(d.text(SpannableStringBuilder("WHEEL · MOVE").apply { setSpan(ForegroundColorSpan(screen.getColor(R.color.accent)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }, 13, R.color.dim, 600, mono = true), lp(top = 14))
        p.addView(View(screen).apply { background = d.fill(R.color.dim, 3) }, LinearLayout.LayoutParams(d.px(56), d.px(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = d.px(8) })

        val keepY = panel?.translationY ?: 0f
        panel?.let(o::removeView)
        panel = p
        o.addView(p, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        p.translationY = keepY
        focusable = items
        if (focus >= items.size) focus = items.size - 1
        highlight()
    }

    private fun highlight() = focusable.forEachIndexed { i, v -> v.foreground = if (i == focus) d.fill(R.color.focus_wash, 20) else null }

    private fun go(target: Class<*>, sessionId: String? = null) {
        overlay?.let { (it.parent as? FrameLayout)?.removeView(it) }
        screen.startActivity(Intent(screen, target).apply { sessionId?.let { putExtra(HerdrScreen.EXTRA_SESSION, it) } })
    }

    private fun clockRow() = LinearLayout(screen).apply {
        gravity = Gravity.BOTTOM
        setPadding(d.px(6), 0, d.px(6), 0)
        val now = LocalDateTime.now()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(now.format(DateTimeFormatter.ofPattern("HH:mm")), 44, R.color.fg, 700).apply { letterSpacing = -.04f; includeFontPadding = false })
            addView(d.text(now.format(DateTimeFormatter.ofPattern("EEE · MMM d")).uppercase(), 13, R.color.dim, 600, mono = true).apply { letterSpacing = .12f; setPadding(0, d.px(6), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val battery = screen.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        if (level in 0..100) addView(d.text("$level%${if (charging) " · CHARGING" else ""}", 13, R.color.fg, 600, mono = true).apply {
            background = d.fill(R.color.raised, 999)
            setPadding(d.px(14), d.px(8), d.px(14), d.px(8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply { setColor(screen.getColor(if (level <= 15 && !charging) R.color.error else R.color.ok)); cornerRadius = d.px(2).toFloat(); setSize(d.px(16), d.px(8)) }, null, null, null)
            compoundDrawablePadding = d.px(8)
        })
    }

    private fun status(): View {
        val herd = app.herdr.state
        val open = herd.deck.count { it.state.needsYou && !it.stale }
        val live = herd.link == Link.LIVE
        val text = SpannableStringBuilder(if (!app.herdr.paired) "NOT PAIRED" else if (live) "${herd.machines.count { it.connected }} HOSTS · ${herd.deck.size} SHEEP" else herd.link.label.uppercase())
        if (open > 0) text.append(" · ").append("$open NEEDS YOU").apply { setSpan(ForegroundColorSpan(screen.getColor(R.color.accent)), length - "$open NEEDS YOU".length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return d.text(text, 13, if (live) R.color.ok else R.color.dim, 600, mono = true).apply {
            letterSpacing = .1f
            setPadding(d.px(6), 0, d.px(6), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(screen.getColor(if (live) R.color.ok else R.color.dim)); setSize(d.px(8), d.px(8)) }, null, null, null)
            compoundDrawablePadding = d.px(8)
        }
    }

    private fun shortcut(icon: Int, label: String, badge: String?, tinted: Boolean, tap: () -> Unit) = LinearLayout(screen).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, d.px(4), 0, d.px(4))
        addView(FrameLayout(context).apply {
            background = d.fill(R.color.raised, 22)
            addView(ImageView(context).apply {
                setImageDrawable(screen.getDrawable(icon)!!.mutate().apply { if (tinted) setTint(screen.getColor(R.color.fg)) })
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(d.px(if (tinted) 26 else 42), d.px(if (tinted) 26 else 42), Gravity.CENTER))
            badge?.let { addView(d.text(it, 13, R.color.on_accent, 700, mono = true).apply { gravity = Gravity.CENTER; background = d.fill(R.color.accent, 11); minWidth = d.px(22) }, FrameLayout.LayoutParams(-2, d.px(22), Gravity.TOP or Gravity.END)) }
        }, LinearLayout.LayoutParams(d.px(64), d.px(64)))
        addView(d.text(label, 14, R.color.fg, 600).apply { gravity = Gravity.CENTER; setPadding(0, d.px(8), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        contentDescription = label + (badge?.let { ", $it waiting" } ?: "")
        setOnClickListener { tap() }
    }

    private fun toggle(icon: Int, title: String, state: String, on: Boolean, tap: () -> Unit) = LinearLayout(screen).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = d.px(64)
        background = d.fill(if (on) R.color.cream else R.color.raised, 20)
        setPadding(d.px(16), d.px(10), d.px(12), d.px(10))
        val ink = if (on) R.color.on_accent else R.color.fg
        addView(ImageView(context).apply { setImageDrawable(screen.getDrawable(icon)!!.mutate().apply { setTint(screen.getColor(ink)) }) }, LinearLayout.LayoutParams(d.px(22), d.px(22)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(title, 16, ink, 700).apply { maxLines = 1 })
            addView(d.text(state, 13, if (on) R.color.on_accent else R.color.dim, 500, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; alpha = if (on) .7f else 1f })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(12) })
        contentDescription = "$title, $state"
        setOnClickListener { tap() }
    }

    private fun pair(a: View, b: View) = LinearLayout(screen).apply {
        addView(a, LinearLayout.LayoutParams(0, -2, 1f))
        addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(8) })
    }

    /** Brightness previews on the window while you drag and is saved, like Quick controls, on release. */
    private fun brightness(): View {
        val cfg = app.store.value
        val seek = SeekBar(screen).apply {
            max = 99
            progress = dev.r1ptt.SettingsActivity.brightnessPercent(cfg.power.brightness) - 1
            progressDrawable = LayerDrawable(arrayOf(d.fill(R.color.raised_hi, 4), ClipDrawable(d.fill(R.color.cream, 4), Gravity.START, ClipDrawable.HORIZONTAL)))
                .apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(screen.getColor(R.color.cream)); setSize(d.px(20), d.px(20)) }
            splitTrack = false
            minHeight = d.px(8); maxHeight = d.px(8)
            background = null
            contentDescription = "Brightness"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) screen.window.attributes = screen.window.attributes.apply { screenBrightness = ((p * 254 / 99) + 1) / 255f }
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val value = (bar.progress * 254 / 99) + 1
                    try { DeviceSettings.apply(app.store.update { it.copy(power = it.power.copy(brightness = value)) }) }
                    catch (e: ConfigPersistenceException) { screen.showToast(e.message ?: "Brightness could not be saved") }
                }
            })
        }
        return LinearLayout(screen).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = d.px(56)
            background = d.fill(R.color.field, 20)
            setPadding(d.px(16), 0, d.px(10), 0)
            addView(ImageView(context).apply { setImageDrawable(screen.getDrawable(R.drawable.ic_sun)!!.mutate().apply { setTint(screen.getColor(R.color.fg)) }) }, LinearLayout.LayoutParams(d.px(20), d.px(20)))
            addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(6) })
        }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(top) }
}
