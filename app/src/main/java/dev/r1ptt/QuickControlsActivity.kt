package dev.r1ptt

import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import dev.r1ptt.data.Config
import dev.r1ptt.data.ConfigPersistenceException
import dev.r1ptt.herdr.Link
import dev.r1ptt.herdr.ui.Design
import dev.r1ptt.herdr.ui.HerdrSettingsActivity
import dev.r1ptt.herdr.ui.SetupActivity
import dev.r1ptt.power.DeviceSettings
import dev.r1ptt.ui.DeviceActivity
import dev.r1ptt.ui.Fonts
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-only controls: network, voice, power mode, brightness and volume. Each tile shows
 * its state in words and does what it says; brightness commits once after a gesture.
 */
class QuickControlsActivity : DeviceActivity() {
    private val app get() = application as App
    private val d by lazy { Design(this) }
    private val main = Handler(Looper.getMainLooper())
    private var pendingBrightness: Int? = null
    private val commitBrightness = Runnable { flushBrightness() }
    private var scope = MainScope()
    private var shownKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onStart() {
        super.onStart()
        // The Tailscale tile counts hosts the bridge reaches; keep the herd live while this is open.
        app.herdr.attach()
        scope = MainScope().apply { launch { app.herdr.version.collect { render() } } }
    }

    override fun onStop() {
        scope.cancel()
        app.herdr.detach()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val cfg = app.store.value
        val herd = app.herdr.state
        // Redrawn only when a tile would read differently, so a slider mid-drag is never rebuilt.
        val key = listOf(cfg.quiet, cfg.tts, app.herdr.speakAlerts, app.herdr.mode, app.herdr.paired, herd.link, herd.machines, app.radio.isOnline(), SetupActivity.tailscale(this)).joinToString()
        if (key == shownKey) return
        shownKey = key
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
        }
        root.addView(top(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(6) })

        val online = app.radio.isOnline()
        val ssid = runCatching { @Suppress("DEPRECATION") getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.ssid?.trim('"') }.getOrNull()?.takeUnless { it.isBlank() || it.startsWith("<") }
        val wifi = tile(R.drawable.ic_wifi, "Wi-Fi", if (online) ssid ?: "Connected" else "Not connected", online) {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.onFailure { showToast("Wi-Fi settings unavailable") }
        }
        val hosts = when {
            !app.herdr.paired -> "No bridge paired"
            herd.link == Link.LIVE -> herd.machines.count { it.connected }.let { "$it ${if (it == 1) "host" else "hosts"} reachable" }
            else -> "Bridge ${herd.link.label.lowercase()}"
        }
        val ts = SetupActivity.tailscale(this)
        val net = if (ts == SetupActivity.Tailscale.MISSING) tile(R.drawable.ic_server, "Hosts", hosts, herd.link == Link.LIVE) {
            startActivity(Intent(this, HerdrSettingsActivity::class.java))
        } else tile(R.drawable.ic_reach, "Tailscale", if (ts == SetupActivity.Tailscale.CONNECTED) hosts else "Off · tap to open", ts == SetupActivity.Tailscale.CONNECTED) {
            packageManager.getLaunchIntentForPackage("com.tailscale.ipn")?.let(::startActivity)
        }
        root.addView(pair(wifi, net))
        val voice = cfg.tts.enabled && !cfg.missingSpeechKey
        val speak = tile(R.drawable.ic_speaker, "Speak alerts", when { !voice -> "No voice set up"; app.herdr.speakAlerts -> "On · names who needs you"; else -> "Off" }, app.herdr.speakAlerts && voice) {
            if (!voice) showToast("Set up a speech voice in Settings first") else { app.herdr.speakAlerts = !app.herdr.speakAlerts; render() }
        }
        val quiet = tile(R.drawable.ic_quiet, "Quiet", if (cfg.quiet) "On · nothing is spoken" else "Off", cfg.quiet) {
            val on = !cfg.quiet
            if (save { it.copy(quiet = on) }) { if (on) app.turns.stopSpeech(); render() }
        }
        root.addView(pair(speak, quiet), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) })
        root.addView(power(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(12) })

        val brightness = slider(R.drawable.ic_sun, "Brightness", dev.r1ptt.SettingsActivity.brightnessPercent(cfg.power.brightness) - 1, 99, { "${it + 1}" }, { percent ->
            window.attributes = window.attributes.apply { screenBrightness = ((percent * 254 / 99) + 1) / 255f }
        }) { percent ->
            pendingBrightness = (percent * 254 / 99) + 1
            main.removeCallbacks(commitBrightness)
            main.postDelayed(commitBrightness, 180)
        }
        root.addView(brightness.first, LinearLayout.LayoutParams(-1, d.px(56)).apply { topMargin = d.px(4) })
        val audio = getSystemService(AudioManager::class.java)
        val volume = slider(R.drawable.ic_speaker, "Speech volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), { "$it" },
            { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        root.addView(volume.first, LinearLayout.LayoutParams(-1, d.px(56)))
        wheelVolumeTarget = volume.second

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply {
            addView(d.pill("Settings", fill = R.color.raised, ink = R.color.cream, height = 56) { startActivity(Intent(this@QuickControlsActivity, SettingsActivity::class.java)) }.apply {
                setCompoundDrawablesRelative(icon(R.drawable.ic_settings).apply { setBounds(0, 0, d.px(20), d.px(20)) }, null, null, null)
                compoundDrawablePadding = d.px(10)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageDrawable(icon(R.drawable.ic_moon))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(d.px(18), d.px(18), d.px(18), d.px(18))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.raised)) }
                contentDescription = "Sleep"
                setOnClickListener { flushBrightness(); app.screen.sleepNow() }
            }, LinearLayout.LayoutParams(d.px(56), d.px(56)).apply { marginStart = d.px(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(12) })
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(root) }
        wheelScrollTarget = scroll
        setContentView(scroll)
    }

    private fun icon(res: Int, tint: Int = R.color.cream) = getDrawable(res)!!.mutate().apply { setTint(getColor(tint)) }

    /** The clock and date, and the battery read once as the screen opens. */
    private fun top() = LinearLayout(this).apply {
        gravity = Gravity.BOTTOM
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(4), 0, 0, 0)
            addView(TextClock(context).apply {
                format24Hour = "HH:mm"; format12Hour = "h:mm"
                typeface = Fonts.display(context, 700); letterSpacing = -.03f
                setTextColor(getColor(R.color.cream)); includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_PX, d.px(40).toFloat())
            })
            addView(TextClock(context).apply {
                format24Hour = "EEE · MMM d"; format12Hour = "EEE · MMM d"; isAllCaps = true
                typeface = Fonts.mono(context, 600); letterSpacing = .1f
                setTextColor(getColor(R.color.dim))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, d.px(14).toFloat())
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val battery = getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) addView(d.text("$level%${if (battery.isCharging) " · charging" else ""}", 13, if (level <= 15) R.color.error else R.color.cream, 600, mono = true).apply {
            setCompoundDrawablesRelative(icon(R.drawable.ic_battery, if (level <= 15) R.color.error else R.color.ok).apply { setBounds(0, 0, d.px(20), d.px(20)) }, null, null, null)
            compoundDrawablePadding = d.px(8)
            gravity = Gravity.CENTER_VERTICAL
            background = d.fill(R.color.field, 999)
            setPadding(d.px(12), d.px(8), d.px(12), d.px(8))
        })
    }

    private fun pair(a: View, b: View) = LinearLayout(this).apply {
        addView(a, LinearLayout.LayoutParams(0, -1, 1f))
        addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = d.px(10) })
    }

    /** On tiles are cream; off tiles are dark. The words under the title always say which. */
    private fun tile(glyph: Int, title: String, state: String, on: Boolean, tap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = d.fill(if (on) R.color.cream else R.color.raised, 24)
        setPadding(d.px(16), d.px(16), d.px(16), d.px(16))
        addView(ImageView(context).apply { setImageDrawable(icon(glyph, if (on) R.color.on_accent else R.color.cream)) }, LinearLayout.LayoutParams(d.px(24), d.px(24)))
        addView(d.text(title, 18, if (on) R.color.on_accent else R.color.cream, 700), LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(14) })
        addView(d.text(state, 13, if (on) R.color.raised else R.color.dim, mono = true).apply { letterSpacing = 0f; setPadding(0, d.px(2), 0, 0) })
        isClickable = true
        contentDescription = "$title, $state"
        setOnClickListener { tap() }
    }

    /** Pocket and Connected are choices; Desk is what plugging in does, so it is shown, not picked. */
    private fun power() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = d.fill(R.color.field, 24)
        setPadding(d.px(16), d.px(14), d.px(16), d.px(14))
        val mode = app.herdr.mode
        addView(LinearLayout(context).apply {
            addView(d.text("POWER MODE", 13, R.color.dim, 600, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (mode == "Desk") addView(d.text("auto: plugged in", 13, R.color.dim, mono = true).apply { letterSpacing = .06f })
        })
        addView(LinearLayout(context).apply {
            background = d.fill(R.color.bg, 999)
            setPadding(d.px(4), d.px(4), d.px(4), d.px(4))
            listOf("Pocket" to false, "Desk" to null, "Connected" to true).forEach { (label, connected) ->
                val on = label == mode
                addView(d.text(label, 16, if (on) R.color.on_accent else R.color.dim, if (on) 700 else 600).apply {
                    gravity = Gravity.CENTER
                    minHeight = d.px(48)
                    if (on) background = d.fill(R.color.cream, 999)
                    if (connected != null) { isClickable = true; setOnClickListener { app.herdr.connectedOnBattery = connected; render() } }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) })
    }

    private fun slider(glyph: Int, label: String, value: Int, max: Int, format: (Int) -> String, preview: (Int) -> Unit, commit: (Int) -> Unit): Pair<View, SeekBar> {
        val readout = d.text(format(value), 13, R.color.dim, mono = true).apply { gravity = Gravity.END }
        var dragging = false
        val seek = SeekBar(this).apply {
            this.max = max
            progress = value
            contentDescription = label
            isFocusableInTouchMode = true
            progressDrawable = LayerDrawable(arrayOf(d.fill(R.color.raised, 5), ClipDrawable(d.fill(R.color.cream, 5), Gravity.START, ClipDrawable.HORIZONTAL)))
                .apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.cream)); setSize(d.px(22), d.px(22)) }
            splitTrack = false
            minHeight = d.px(10); maxHeight = d.px(10)
            background = null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; bar.requestFocus() }
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    readout.text = format(p)
                    if (fromUser) { preview(p); if (!dragging) commit(p) }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; commit(bar.progress) }
            })
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d.px(6), 0, d.px(6), 0)
            addView(ImageView(context).apply { setImageDrawable(icon(glyph)) }, LinearLayout.LayoutParams(d.px(22), d.px(22)))
            addView(seek, LinearLayout.LayoutParams(0, -1, 1f))
            addView(readout, LinearLayout.LayoutParams(d.px(40), -2))
        }
        return row to seek
    }

    override fun onPause() {
        main.removeCallbacks(commitBrightness)
        flushBrightness()
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        super.onPause()
    }

    private fun flushBrightness() {
        val value = pendingBrightness ?: return
        pendingBrightness = null
        if (!save(device = true) { it.copy(power = it.power.copy(brightness = value)) }) window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    private fun save(device: Boolean = false, update: (Config) -> Config): Boolean = try {
        val next = app.store.update(update)
        if (device) DeviceSettings.apply(next)
        true
    } catch (e: ConfigPersistenceException) { showToast(e.message ?: "Settings could not be saved"); false }
}
