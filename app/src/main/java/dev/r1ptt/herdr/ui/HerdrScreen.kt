package dev.r1ptt.herdr.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.DictationTarget
import dev.r1ptt.Phase
import dev.r1ptt.R
import dev.r1ptt.herdr.Herdr
import dev.r1ptt.herdr.HerdrClient
import dev.r1ptt.herdr.Link
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.ui.DeviceActivity
import dev.r1ptt.ui.WheelPolicy
import dev.r1ptt.net.friendly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Every Herdr screen: keeps the bridge stream attached while visible, redraws when the herd
 * changes, and turns wheel detents into [onWheel] steps where a screen selects rather than scrolls.
 */
abstract class HerdrScreen : DeviceActivity(), DictationTarget {
    protected val app get() = application as App
    protected val herdr: Herdr get() = app.herdr
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    /** Draws the screen from [herdr]'s state; called on start and on every herd change. */
    protected abstract fun render()

    /** Herdr OS's pull-down shade; off where it would show the herd to whoever holds the R1. */
    private val shade by lazy { Shade(this) }
    protected open val shadeAllowed = true

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean =
        (shadeAllowed && herdr.paired && shade.intercept(ev)) || super.dispatchTouchEvent(ev)

    internal fun superDispatchTouch(ev: android.view.MotionEvent) = super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android's own shade has no place in Herdr OS: if a swipe at the very edge opens it, put it away.
        if (!hasFocus && resumedNow && !hasOpenDialog) dev.r1ptt.sys.Root.async("cmd statusbar collapse")
    }

    /** One wheel detent; true if this screen used it. */
    protected open fun onWheel(down: Boolean): Boolean = false

    override fun onStart() {
        super.onStart()
        herdr.attach()
        herdr.screensShowing++
        watch = scope.launch {
            launch { herdr.version.collect {
                // A revoked or unpaired R1 keeps nothing on screen: every page that shows host content closes.
                shade.refresh()
                if (!herdr.paired && this@HerdrScreen !is HerdActivity && this@HerdrScreen !is HerdrSettingsActivity && this@HerdrScreen !is SetupActivity && this@HerdrScreen !is MenuActivity) finish() else render()
            } }
            launch { herdr.notices.collect { showToast(it.text) } }
            launch { app.turns.state.collect { t ->
                when (t.phase) {
                    Phase.DICTATING -> listening(heard.ifBlank { t.heard })
                    Phase.TRANSCRIBING -> listening(heard.ifBlank { "Transcribing…" })
                    Phase.ERROR -> { hideListening(); if (t.note.isNotBlank()) showToast(t.note) }
                    else -> { if (heard.isBlank()) hideListening(); if (t.note == dev.r1ptt.TurnController.MISSED && resumedNow) missed() }
                }
            } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        app.turns.attachTarget(this)
        // The side-button reader lives in PttService, which may only start from the foreground.
        // Herdr is home now, so its screens start it as HomeActivity used to.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) dev.r1ptt.PttService.start(this)
    }

    override fun onPause() {
        resumedNow = false
        // The deck is home: it keeps the button while paused (screen off, pocket), and every other
        // Herdr screen hands it back, so a hold talks to the herd instead of falling through to Hermes.
        if (this !is HerdActivity) {
            app.turns.detachTarget(this)
            HerdActivity.live?.let(app.turns::attachTarget)
        }
        super.onPause()
    }

    // ---- push to talk: speech is only ever a draft here ----

    private var resumedNow = false
    private var heard = ""
    private var stage: View? = null
    private var stageText: TextView? = null

    /** What a hold on this screen asks; shown until words arrive. */
    protected open val listenPrompt = "Ask your herd anything."

    /** A finished transcript. Default: open it as a draft task for review; nothing is sent. */
    protected open fun onDraft(text: String) {
        startActivity(Intent(this, DelegateActivity::class.java).putExtra(DelegateActivity.EXTRA_TEXT, text).apply {
            sessionId?.let { putExtra(EXTRA_SESSION, it) }
        })
    }

    override fun isActive() = herdr.paired && (resumedNow || (this is HerdActivity && !isDestroyed))
    override fun showPartial(text: String) { heard = text; listening(text) }
    override fun insert(text: String) { heard = ""; hideListening(); if (text.isNotBlank()) onDraft(text.trim()) }
    override fun clearPartial() { heard = ""; hideListening() }
    override fun keepPartial() { val words = heard; clearPartial(); if (words.isNotBlank()) onDraft(words.trim()) }
    override fun sendTyped() = false
    override fun hideKeyboard() {}

    /**
     * Who a hold talks to on screens where releasing sends at once (thread, walkie). The listening
     * stage then turns orange, since it is your voice going out, and names the destination.
     */
    protected open val sendsTo: Addressee? = null

    data class Addressee(val kicker: String, val name: String, val detail: String, val status: String = "Listening", val release: String = "Release to send")

    private var stageAddressee: List<TextView> = emptyList()

    private fun listening(words: String) {
        // A hold from the pocket lands on the paused deck: bring it forward so the words are seen.
        if (!resumedNow && this is HerdActivity) startActivity(Intent(this, HerdActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        val to = sendsTo
        val view = stage ?: (if (to != null) sendingStage() else LinearLayout(this).also { col ->
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.BOTTOM
            col.setBackgroundColor(getColor(R.color.bg))
            col.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(48))
            col.isClickable = true
            col.addView(ui.mono("Listening", R.color.accent, 13f))
            stageText = ui.title("", 30f).apply { setPadding(0, ui.dp(10), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            col.addView(stageText)
            col.addView(ui.note("Release to review. Nothing is sent until you confirm."))
        }).also { stage = it }
        if (to != null) stageAddressee.zip(listOf(to.status.uppercase(), to.kicker.uppercase(), to.name, to.detail, to.release.uppercase())).forEach { (v, t) -> v.text = t }
        missedView?.let { content.removeView(it); missedView = null }
        if (view.parent !== content) { (view.parent as? ViewGroup)?.removeView(view); content.addView(view, FrameLayout.LayoutParams(-1, -1)) }
        view.bringToFront()
        view.visibility = View.VISIBLE
        stageText?.text = words.ifBlank { listenPrompt }
    }

    /** Paper "Message a subagent" / "Walkie · you talk": orange, the addressee on top, your words large. */
    private fun sendingStage(): View {
        val d = Design(this)
        val ink = R.color.on_accent
        val status = d.text("", 17, ink, 700, mono = true).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(ink)); setSize(d.px(12), d.px(12)) }, null, null, null)
            compoundDrawablePadding = d.px(10)
        }
        val kicker = d.text("", 13, ink, 600, mono = true)
        val name = d.text("", 28, ink, 700).apply { setPadding(0, d.px(3), 0, d.px(3)) }
        val detail = d.text("", 15, ink, 500)
        val release = d.text("", 17, ink, 700, mono = true)
        stageAddressee = listOf(status, kicker, name, detail, release)
        stageText = d.text("", 40, ink, 500).apply { setLineSpacing(0f, 1.1f); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.accent))
            setPadding(d.px(32), d.px(28), d.px(32), d.px(30))
            isClickable = true
            addView(status)
            addView(kicker, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(36) })
            addView(name)
            addView(detail)
            addView(stageText, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d.px(48) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(release)
                addView(View(context).apply { setBackgroundColor(getColor(ink)) }, LinearLayout.LayoutParams(0, d.px(3), 1f).apply { marginStart = d.px(18) })
            })
        }
    }

    private fun hideListening() { stage?.visibility = View.GONE; stageText?.text = "" }

    private var missedView: View? = null

    /** A hold that brought back no words: say so, and offer the keyboard. Nothing was sent. */
    private fun missed() {
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        missedView?.let(content::removeView)
        val d = Design(this)
        val view = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            isClickable = true
            setOnClickListener { content.removeView(this); missedView = null }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(d.text("NOTHING SENT", 14, R.color.dim, 600, mono = true).apply { letterSpacing = .1f })
                addView(d.text("I didn’t catch that.", 28, R.color.fg, 700).apply { setPadding(0, d.px(8), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            }, FrameLayout.LayoutParams(-1, -2).apply { setMargins(d.px(26), d.px(26), d.px(26), 0) })
            addAtFoot(d, companionScene(context, d, null, boldTail(context, "Nothing came through.", "Hold the button while you talk."),
                null, Step("Type instead") { content.removeView(this); missedView = null; onDraft("") }))
        }
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        missedView = view
    }

    override fun onStop() {
        watch?.cancel()
        herdr.screensShowing--
        herdr.detach()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shade.isOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) shade.close(); return true }
            if (WheelPolicy.isWheel(event.keyCode, event.scanCode, event.device?.name)) {
                if (event.action == KeyEvent.ACTION_DOWN) shade.wheel(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                return true
            }
        }
        if (!app.workspace.blocked && WheelPolicy.isWheel(event.keyCode, event.scanCode, event.device?.name) && !hasOpenDialog) {
            val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            if (event.action == KeyEvent.ACTION_DOWN && onWheel(down)) return true
            if (event.action == KeyEvent.ACTION_UP && wheelSteps) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Set by screens whose wheel selects; their key-ups are consumed with the downs. */
    protected open val wheelSteps = false

    /**
     * Runs [work] against the bridge off the main thread and hands the result to [done]. Failures
     * become a toast; nothing is retried.
     */
    protected fun <T> io(work: (HerdrClient) -> T, done: (T) -> Unit) {
        val client = herdr.client() ?: return showToast("Pair a Herdr bridge first")
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work(client) } }
            result.onSuccess(done).onFailure { showToast(friendly(it)) }
        }
    }

    /** Reports a receipt in plain words; accepted is the only status that claims success. */
    protected fun receiptText(r: Receipt): String = when (r.status) {
        ReceiptStatus.ACCEPTED -> "Delivered. The host acknowledged it."
        ReceiptStatus.PENDING -> "Sent. Waiting for the host to acknowledge it."
        ReceiptStatus.REJECTED -> "Not sent. ${r.detail?.substringAfter(": ") ?: "The bridge refused it."}"
        ReceiptStatus.UNKNOWN -> "Delivery uncertain. It was not re-sent; check status before trying again."
    }

    protected fun open(type: Class<*>, sessionId: String? = null) =
        startActivity(Intent(this, type).apply { sessionId?.let { putExtra(EXTRA_SESSION, it) } })

    protected val sessionId: String? get() = intent.getStringExtra(EXTRA_SESSION)
    protected val agent: Agent? get() = sessionId?.let(herdr.state::agent)

    /** "Live", or how old the cards are when they are not. */
    protected fun linkLabel(): String {
        val s = herdr.state
        if (s.link == Link.LIVE || s.link == Link.UNPAIRED) return s.link.label
        val age = s.syncedAt?.let { runCatching { Duration.between(Instant.parse(it), Instant.now()) }.getOrNull() }
        return "${s.link.label}${age?.let { " · synced ${ago(it)}" } ?: ""}"
    }

    protected fun linkView(): TextView = ui.mono(linkLabel(), if (herdr.state.link == Link.LIVE) R.color.ok else R.color.dim, 11f).apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(ui.dot(if (herdr.state.link == Link.LIVE) R.color.ok else R.color.faint, 7), null, null, null)
        compoundDrawablePadding = ui.dp(6)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** A rounded swatch in [agent]'s harness color. */
    protected fun cardFill(agent: Agent, radius: Int = 26) = GradientDrawable().apply {
        setColor(getColor(Harness.of(agent.kind).color))
        cornerRadius = ui.dp(radius).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(getColor(R.color.bg))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        const val EXTRA_SESSION = "herdr.session"

        /** The last two folders of a host path: enough to recognize, short enough for the R1. */
        fun shortPath(p: String?): String? = p?.trimEnd('/')?.split('/')?.takeLast(2)?.joinToString("/")?.let { if (p.count { it == '/' } > 2) "…/$it" else it }

        fun ago(d: Duration): String = when {
            d.seconds < 60 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }

        /** An agent's Herdr name when someone gave it one, else its harness ("Claude Code"). */
        fun who(agent: Agent): String = agent.name?.takeIf { it.isNotBlank() && !it.equals(agent.kind, true) } ?: Harness.of(agent.kind).label

        // The terminal title (summary) is a topic the harness names, not what it is doing, so it never
        // stands in for activity; what an agent is doing comes from its session file (Insight.step).
        fun stateLine(agent: Agent): String = if (agent.stale) "Last known: ${agent.state.label}" else agent.state.label
    }
}

/** A bridge timestamp (UTC ISO-8601) as the R1's local wall-clock time. */
fun clock(iso: String, seconds: Boolean = false): String = runCatching {
    Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm"))
}.getOrDefault("")
