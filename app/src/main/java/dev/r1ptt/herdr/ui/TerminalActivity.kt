package dev.r1ptt.herdr.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.r1ptt.R
import dev.r1ptt.herdr.Link
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.herdr.model.TerminalSnapshot
import dev.r1ptt.ui.Fonts
import dev.r1ptt.net.friendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The agent's real terminal: the bridge's sanitized compact view, refreshed while this screen is
 * open and live, read-only until you tap Type. Typing mode presses keys in the pane (esc, tab,
 * ctrl+c, arrows, enter) and the wheel becomes ↑ ↓; words still go through a reviewed message.
 * The footer says Live or Offline, never live when it isn't.
 */
class TerminalActivity : HerdrScreen() {
    private var snapshot: TerminalSnapshot? = null
    private var poll: Job? = null
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private var error: String? = null
    private var interactive = false
    private val d by lazy { Design(this) }
    override val wheelSteps get() = interactive

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false)
    }

    override fun onStart() {
        super.onStart()
        build()
        poll = scope.launch {
            while (true) {
                if (herdr.state.link == Link.LIVE) fetch()
                delay(POLL_MS)
            }
        }
    }

    override fun onStop() {
        poll?.cancel()
        super.onStop()
    }

    override fun onWheel(down: Boolean): Boolean {
        if (!interactive) return false
        press(if (down) "down" else "up")
        return true
    }

    private fun build() {
        val a = agent
        val h = Harness.of(a?.kind ?: "")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(16), d.px(18), d.px(16), d.px(16))
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d.px(6), 0, d.px(6), 0)
            addView(d.round { finish() })
            addView(android.widget.ImageView(context).apply { setImageResource(h.sheep); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(d.px(30), d.px(30)).apply { marginStart = d.px(12) })
            addView(d.text(a?.let(::who) ?: "Agent", 22, R.color.cream, 700).apply { maxLines = 1; letterSpacing = -.01f },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(8) })
            addView(if (interactive) d.text("TYPING INTO PANE", 13, R.color.on_accent, 600, mono = true).apply {
                background = d.fill(R.color.accent, 20)
                setPadding(d.px(11), d.px(14), d.px(11), d.px(14))
                contentDescription = "Typing into the pane. Tap to go back to read-only"
                isClickable = true
                setOnClickListener { interactive = false; build() }
            } else d.text((shortPath(a?.cwd) ?: "terminal").uppercase(), 13, R.color.dim, 500, mono = true).apply {
                maxLines = 1
                maxWidth = d.px(170)
                ellipsize = android.text.TextUtils.TruncateAt.START
                background = d.fill(R.color.field, 20)
                setPadding(d.px(11), d.px(7), d.px(11), d.px(7))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(8) })
        })
        output = TextView(this).apply {
            typeface = Fonts.mono(context, 400)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d.px(14.5f).toFloat())
            setLineSpacing(0f, 1.15f)
            setTextColor(getColor(R.color.fg))
            setTextIsSelectable(!interactive)
            text = snapshot?.let(::styled) ?: "Loading…"
        }
        scroll = ScrollView(this).apply {
            background = d.fill(R.color.field, 18)
            setPadding(d.px(8), d.px(10), d.px(8), d.px(10))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(output)
        }
        toEnd()
        wheelScrollTarget = if (interactive) null else scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d.px(12); bottomMargin = d.px(12) })
        status = d.text("", 14, R.color.dim, 600, mono = true).apply { maxLines = 1 }
        if (interactive) {
            root.addView(LinearLayout(this).apply {
                KEYS.forEach { (label, key) ->
                    val enter = key == "enter"
                    addView(d.text(label, 15, when { enter -> R.color.on_accent; key == "ctrl+c" -> R.color.accent; else -> R.color.cream }, if (enter) 700 else 600, mono = true).apply {
                        letterSpacing = 0f
                        gravity = Gravity.CENTER
                        background = d.fill(if (enter) R.color.cream else R.color.field, 14)
                        contentDescription = KEY_NAMES[key]
                        isClickable = true
                        setOnClickListener { press(key) }
                    }, LinearLayout.LayoutParams(0, d.px(56), if (enter) 1.6f else 1f).apply { marginStart = if (key == "esc") 0 else d.px(6) })
                }
            })
            root.addView(LinearLayout(this).apply {
                setPadding(d.px(6), d.px(12), d.px(6), 0)
                addView(d.text(hintText("WHEEL", " · ↑ ↓ IN PANE"), 14, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(d.text(hintText("TAP", " · BACK TO READ-ONLY"), 14, mono = true))
            })
        } else {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d.px(6), 0, 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(status)
                    addView(d.text("WHEEL · SCROLLBACK", 14, R.color.dim, 500, mono = true).apply { setPadding(0, d.px(6), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.iconButton(R.drawable.ic_info, "Explain this output", fill = R.color.field) { explain() }, LinearLayout.LayoutParams(d.px(56), d.px(56)).apply { marginEnd = d.px(8) })
                addView(d.pill("Type", height = 60) { interactive = true; build() }.apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(ui.icon(R.drawable.ic_keyboard, R.color.on_accent), null, null, null)
                    compoundDrawablePadding = d.px(10)
                    isEnabled = a != null && !a.stale
                    alpha = if (isEnabled) 1f else .5f
                })
            })
        }
        setContentView(root)
        render()
    }

    private fun hintText(key: String, rest: String) = android.text.SpannableStringBuilder(key + rest).apply {
        setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, key.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(getColor(R.color.dim)), key.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Scrolls to the newest line once the new text has been laid out, just before it is drawn. A
     * plain post runs before layout and lands on the old bottom.
     */
    private fun toEnd() = scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            scroll.viewTreeObserver.removeOnPreDrawListener(this)
            scroll.scrollTo(0, output.height)
            return true
        }
    })

    /** One key into the pane; only a failure is worth a toast. */
    private fun press(key: String) {
        val a = agent ?: return
        io({ it.keys(a.sessionId, listOf(key), a.generation) }) { r -> if (r.status != ReceiptStatus.ACCEPTED) showToast(receiptText(r)) }
    }

    /** One request at a time: the poll loop waits for it. A failure shows in the footer, not as repeated toasts. */
    private suspend fun fetch() {
        val id = sessionId ?: return
        val client = herdr.client() ?: return
        runCatching { withContext(Dispatchers.IO) { client.terminal(id) } }
            .onSuccess { t ->
                // Following the stream means being near the end, not exactly at it: a line that lands
                // between polls must not stop the following for good.
                val atEnd = scroll.scrollY + scroll.height >= output.height + scroll.paddingTop + scroll.paddingBottom - d.px(48)
                snapshot = t
                error = null
                output.text = styled(t)
                if (atEnd) toEnd()
            }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = friendly(it) }
        render()
    }

    override fun render() {
        if (!::status.isInitialized) return
        val t = snapshot
        val mode = when {
            herdr.state.link != Link.LIVE || t?.stream == "offline" -> "Offline" + (t?.capturedAt?.let { " · ${clock(it, seconds = true)}" } ?: "")
            else -> "● Live"
        }
        val notes = listOfNotNull(error, t?.redactions?.takeIf { it > 0 }?.let { "$it redacted" })
        status.text = (listOf(mode, "read-only") + notes).joinToString(" · ").uppercase()
        status.setTextColor(getColor(if (mode.endsWith("Live")) R.color.ok else R.color.dim))
    }

    private fun styled(t: TerminalSnapshot): CharSequence {
        val out = SpannableStringBuilder()
        if (t.truncated) out.append("── earlier lines above ──\n").also { out.setSpan(ForegroundColorSpan(getColor(R.color.dim)), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        t.lines.forEach { line ->
            val start = out.length
            if (line.redacted) {
                out.append("[redacted: possible secret]")
                out.setSpan(ForegroundColorSpan(getColor(R.color.error)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(line.text)
                line.spans.forEach { s ->
                    val a = (start + s.start).coerceAtMost(out.length)
                    val b = (start + s.end).coerceAtMost(out.length)
                    if (a >= b) return@forEach
                    s.fg?.let { out.setSpan(ForegroundColorSpan(ansi(it)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    if (s.bold) out.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            out.append('\n')
        }
        return out
    }

    /** Selected text if any, else the last lines; sent only after the user agrees. */
    private fun explain() {
        val a = agent ?: return
        val selected = if (output.hasSelection()) output.text.subSequence(output.selectionStart, output.selectionEnd).toString() else ""
        val text = selected.ifBlank { snapshot?.lines?.takeLast(30)?.filterNot { it.redacted }?.joinToString("\n") { it.text }.orEmpty() }
        if (text.isBlank()) return showToast("Nothing to explain yet")
        showDeviceDialog(AlertDialog.Builder(this)
            .setTitle("Ask ${a.displayName} to explain?")
            .setMessage("Sends ${if (selected.isBlank()) "the last ${text.lines().size} lines" else "your selection"} (${text.length} characters, secrets redacted) to ${a.displayName} on ${a.machineLabel}, asking it not to change anything yet.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> io({ it.explain(a.sessionId, text, a.generation) }) { r -> showToast(receiptText(r)) } })
    }

    private fun ansi(code: Int): Int = PALETTE.getOrElse(code) { getColor(R.color.fg) }

    companion object {
        const val EXTRA_INTERACTIVE = "herdr.interactive"
        private const val POLL_MS = 2_000L
        private val KEYS = listOf("esc" to "esc", "tab" to "tab", "^C" to "ctrl+c", "↑" to "up", "↓" to "down", "⏎ enter" to "enter")
        private val KEY_NAMES = mapOf("esc" to "Escape", "tab" to "Tab", "ctrl+c" to "Control C", "up" to "Up arrow", "down" to "Down arrow", "enter" to "Enter")
        private val PALETTE = intArrayOf(
            0xFF6C6A65.toInt(), 0xFFFF6B6B.toInt(), 0xFF4BE38A.toInt(), 0xFFF2E94E.toInt(), 0xFF6CA8FF.toInt(), 0xFFC792EA.toInt(), 0xFF22D8EE.toInt(), 0xFFF6F4F0.toInt(),
            0xFFA3A09A.toInt(), 0xFFFF8A8A.toInt(), 0xFF7DF0A8.toInt(), 0xFFFFF27A.toInt(), 0xFF9CC4FF.toInt(), 0xFFDDB3F5.toInt(), 0xFF6EEBFA.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
