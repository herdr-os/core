package dev.r1ptt.herdr.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.update.UpdateActivity
import dev.r1ptt.update.UpdatePhase
import java.time.Duration
import java.time.Instant
import dev.r1ptt.net.friendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Herdr's settings: the hosts the bridge watches, the companion's voice, power, and updates, each
 * one row on the first page and its own page behind it. Unpaired, it is the pairing form (a
 * one-time code from `herdr-bridge enroll`). The device token is sealed on the R1 and never shown.
 */
class HerdrSettingsActivity : HerdrScreen() {
    private enum class Sub { ROOT, HOSTS, VOICE, POWER }

    private var shownKey = ""
    private var sub = Sub.ROOT
    private val d by lazy { Design(this) }

    override fun render() {
        val s = herdr.state
        val tts = app.store.value.tts
        val key = "${herdr.paired}${s.link}${s.machines}${s.deck.size}${herdr.problem}${herdr.mode}$sub${herdr.speakAlerts}${tts.enabled}${tts.voice}${app.updates.state.value.phase}"
        if (key == shownKey) return
        shownKey = key
        if (!herdr.paired) { sub = Sub.ROOT; return pairForm() }
        val page = when (sub) {
            Sub.ROOT -> root()
            Sub.HOSTS -> hosts()
            Sub.VOICE -> voice()
            Sub.POWER -> power()
        }
        wheelScrollTarget = page.scroll
        setContentView(page.root)
    }

    private fun go(to: Sub) { sub = to; shownKey = ""; render() }

    private fun back() = if (sub == Sub.ROOT) finish() else go(Sub.ROOT)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    /** One settings row: title, a mono line under it, and a chevron or an orange dot when something waits on you. */
    private fun row(title: String, sub: CharSequence, waiting: Boolean = false, go: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = d.px(64)
        setPadding(d.px(16), d.px(12), d.px(16), d.px(12))
        background = ui.pressable(null, 20)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(title, 18, R.color.cream, 700))
            addView(d.text(sub, 13, R.color.dim, 400, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (waiting) addView(View(context).apply { background = ui.dot(R.color.accent, 10) }, LinearLayout.LayoutParams(d.px(10), d.px(10)))
        else addView(ui.iconView(R.drawable.ic_chevron_right, R.color.dim), LinearLayout.LayoutParams(d.px(18), d.px(18)))
        contentDescription = "$title. $sub"
        isClickable = true
        setOnClickListener { go() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(2) }
    }

    private fun root(): dev.r1ptt.ui.DeviceUi.Page {
        val page = designPage(this, d, "Settings", null) { back() }
        val s = herdr.state
        val hosts = SpannableStringBuilder()
        s.machines.forEachIndexed { i, m ->
            if (i > 0) hosts.append(" · ")
            val start = hosts.length
            hosts.append(if (m.connected) m.label else "${m.label} offline")
            if (!m.connected) hosts.setSpan(ForegroundColorSpan(getColor(R.color.error)), start, hosts.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        page.body.addView(row("Hosts", hosts.ifEmpty { "No hosts reported yet" }) { go(Sub.HOSTS) })
        val tts = app.store.value.tts
        page.body.addView(row("Voice & companion", if (tts.enabled) "${provider()} · ${tts.voice}" else "Speech is off") { go(Sub.VOICE) })
        page.body.addView(row("Power", "${herdr.mode} mode now · Desk when plugged") { go(Sub.POWER) })
        val u = app.updates.state.value
        val ready = u.release != null && (u.phase == UpdatePhase.AVAILABLE || u.phase == UpdatePhase.READY)
        page.body.addView(row("Updates", if (ready) "${u.release!!.versionName} is ready" else "Installed ${app.updates.installedVersion()} · checks when you ask", waiting = ready) {
            startActivity(Intent(this, UpdateActivity::class.java))
        })
        page.root.addView(LinearLayout(this).apply {
            setPadding(d.px(28), d.px(8), d.px(28), d.px(20))
            addView(d.text("HERDR OS ${app.updates.installedVersion().uppercase()} · R1 · ${herdr.deviceId.take(8).uppercase()}…", 13, R.color.dim, 500, mono = true).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.text(SpannableStringBuilder("WHEEL · SCROLL").apply { setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }, 13, R.color.dim, 700, mono = true))
        })
        return page
    }

    private fun provider(): String {
        val host = runCatching { java.net.URI(app.store.value.tts.endpoint.baseUrl).host.orEmpty() }.getOrDefault("")
        return when {
            "openai" in host -> "OpenAI"
            "elevenlabs" in host -> "ElevenLabs"
            else -> host.ifBlank { "Custom" }
        }
    }

    private fun hosts(): dev.r1ptt.ui.DeviceUi.Page {
        val bridge = runCatching { java.net.URI(herdr.bridgeUrl).host }.getOrNull() ?: herdr.bridgeUrl
        val page = designPage(this, d, "Hosts", "through $bridge · ${linkLabel()}") { back() }
        val s = herdr.state
        if (s.machines.isEmpty()) page.body.addView(d.text("No hosts reported yet.", 16, R.color.dim))
        s.machines.forEach { m ->
            val sheep = s.deck.filter { it.machineId == m.id }
            page.body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d.px(16), d.px(14), d.px(16), d.px(14))
                background = d.fill(if (m.connected) R.color.field else R.color.bg, 20)
                addView(ui.iconView(R.drawable.ic_server, if (m.connected) R.color.cream else R.color.faint).apply {
                    background = d.fill(R.color.raised, 14); setPadding(d.px(10), d.px(10), d.px(10), d.px(10))
                }, LinearLayout.LayoutParams(d.px(44), d.px(44)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(d.text(m.label, 18, if (m.connected) R.color.cream else R.color.dim, 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    val how = if (m.kind == "ssh") "over SSH" else "local"
                    val line = if (m.connected) listOfNotNull(how, m.herdrVersion?.let { "Herdr $it" }).joinToString(" · ")
                        else "offline${m.lastSeenAt?.let { runCatching { " " + ago(Duration.between(Instant.parse(it), Instant.now())).removeSuffix(" ago") }.getOrNull() } ?: ""}"
                    addView(d.text(line, 13, if (m.connected) R.color.dim else R.color.error, 400, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(3), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14) })
                if (m.connected) sheep.take(3).forEach { a ->
                    addView(ImageView(context).apply { setImageResource(Harness.of(a.kind).sheep); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                        LinearLayout.LayoutParams(d.px(28), d.px(28)).apply { marginStart = d.px(4) })
                } else addView(d.text("${sheep.size} SHEEP", 13, R.color.dim, 600, mono = true))
                contentDescription = "${m.label}, ${if (m.connected) "connected" else "offline"}, ${sheep.size} agents"
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(8) })
        }
        herdr.problem?.let { page.body.addView(d.text(it, 14, R.color.dim).apply { setPadding(d.px(6), d.px(8), 0, 0) }) }
        page.body.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        page.body.addView(d.pill("Add a host") { addHostHelp() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(16) })
        page.body.addView(quietPill(d, "Unpair this R1") { confirmUnpair() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) })
        return page
    }

    /** Hosts join on the bridge, not here: say how. */
    private fun addHostHelp() = showDeviceDialog(AlertDialog.Builder(this)
        .setTitle("Add a host")
        .setMessage("Hosts join through the bridge. On ${runCatching { java.net.URI(herdr.bridgeUrl).host }.getOrNull() ?: "the bridge machine"}, restart `herdr-bridge serve` with `--machine <ssh host>` for each computer that runs Herdr. It shows up here on its own.")
        .setPositiveButton("OK", null))

    private fun voice(): dev.r1ptt.ui.DeviceUi.Page {
        val page = designPage(this, d, "Voice & companion", null) { back() }
        val tts = app.store.value.tts
        page.body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = d.fill(R.color.field, 24)
            setPadding(d.px(16), d.px(14), d.px(16), d.px(14))
            addView(LinearLayout(context).apply {
                addView(d.text("ITS VOICE", 13, R.color.dim, 500, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(d.text(provider(), 13, R.color.dim, 400, mono = true))
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, d.px(10), 0, 0)
                addView(ui.iconView(R.drawable.ic_speaker, R.color.on_accent).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(getColor(R.color.cream)) }
                    setPadding(d.px(14), d.px(14), d.px(14), d.px(14))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = "Play a sample"
                    isClickable = true
                    setOnClickListener {
                        if (!tts.enabled) showToast("Speech is off in robotOS settings") else herdr.speak("Hi. I'll tell you when a sheep needs you.")
                    }
                }, LinearLayout.LayoutParams(d.px(56), d.px(56)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(d.text(tts.voice.replaceFirstChar { it.uppercase() }, 22, R.color.cream, 700))
                    addView(d.text(if (tts.enabled) "the R1’s speech voice" else "speech is off", 13, R.color.dim, 400, mono = true).apply { setPadding(0, d.px(3), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14) })
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(14) })
        page.body.addView(toggleRow(this, d, "Say who needs me", "never what they asked", herdr.speakAlerts) { herdr.speakAlerts = it; shownKey = ""; render() })
        page.body.addView(d.text("Change the voice itself in robotOS settings, under speech.", 14, R.color.dim).apply { setPadding(d.px(6), d.px(14), d.px(6), 0); setLineSpacing(0f, 1.15f) })
        return page
    }

    private fun power(): dev.r1ptt.ui.DeviceUi.Page {
        val page = designPage(this, d, "Power", "${herdr.mode} mode now") { back() }
        page.body.addView(toggleRow(this, d, "Stay connected on battery", "costs standby time", herdr.connectedOnBattery) { herdr.connectedOnBattery = it; shownKey = ""; render() })
        page.body.addView(d.text("Plugged in, the R1 always stays connected and notifies you (Desk). On battery it syncs only while a Herdr screen is on (Pocket) unless this is on (Connected), which keeps Wi-Fi up and shortens standby.", 16, R.color.dim).apply {
            setPadding(d.px(6), d.px(14), d.px(6), 0); setLineSpacing(0f, 1.15f)
        })
        return page
    }

    /** Unpaired, settings hands over to the setup flow's pairing step. */
    private fun pairForm() {
        startActivity(android.content.Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, SetupActivity.Step.NETWORK.name))
        finish()
    }

    private fun confirmUnpair() {
        showDeviceDialog(AlertDialog.Builder(this)
            .setTitle("Unpair this R1?")
            .setMessage("Deletes this R1's bridge token and every cached card. To also block the token on the bridge, run `herdr-bridge revoke ${herdr.deviceId}` there.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unpair") { _, _ -> herdr.unpair(); sub = Sub.ROOT; shownKey = ""; render() })
    }
}
