package dev.r1ptt.herdr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.Insight

/** Something the agent screen can send on the user's behalf: what the confirmation says, then the messages in order. */
data class Remedy(val confirm: String, val messages: List<String>)

/**
 * What went sideways for one agent, as the top of its screen: it stopped unexpectedly, it ran out
 * of usage, or its context is nearly full. Each offers only steps the bridge can really take.
 * Null when nothing did.
 */
fun trouble(c: Context, d: Design, a: Agent, i: Insight?, send: (Remedy) -> Unit, terminal: () -> Unit, wait: () -> Unit): View? = when {
    a.state == AgentState.FAILED && !a.stale -> crashed(c, d, a, i, terminal)
    i?.limit != null -> limited(c, d, a, i.limit, send, wait)
    else -> contextShare(a, i)?.takeIf { it >= CONTEXT_FULL }?.let { full(c, d, a, it, send) }
}

private const val CONTEXT_FULL = .85f

/**
 * How full the context is, 0..1, only where the harness records its window (Codex). Claude Code
 * doesn't, and a guessed window raised "100% full" on a healthy 1M-context Opus session, so it
 * gives no share rather than a wrong one.
 */
fun contextShare(a: Agent, i: Insight?): Float? {
    val used = i?.contextUsed ?: return null
    val window = i.contextWindow ?: return null
    return used.toFloat() / window
}

private fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

private fun crashed(c: Context, d: Design, a: Agent, i: Insight?, terminal: () -> Unit) = column(c).apply {
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(0, d.px(20), 0, d.px(16))
    addView(ImageView(c).apply { setImageResource(Harness.of(a.kind).sheep); rotation = -8f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
        LinearLayout.LayoutParams(d.px(116), d.px(116)))
    addView(d.text("STOPPED UNEXPECTEDLY", 14, R.color.error, 700, mono = true).apply { letterSpacing = .1f; setPadding(0, d.px(18), 0, d.px(8)) })
    addView(d.text("${HerdrScreen.who(a)} quit mid-task.", 28, R.color.fg, 700).apply { gravity = Gravity.CENTER })
    val failed = i?.let { it.subagents + it.tasks }?.lastOrNull { it.state == "failed" }
    val facts = listOfNotNull(failed?.let { "failed: ${it.label}" }, "last: ${a.summary.ifBlank { a.title }}").joinToString("\n")
    addView(d.text(facts, 14, R.color.dim, 400, mono = true).apply {
        background = d.fill(R.color.field, 14)
        setPadding(d.px(14), d.px(12), d.px(14), d.px(12))
        setLineSpacing(0f, 1.2f)
        maxLines = 4; ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
    // Restarting a harness that exited isn't something the bridge can do; the terminal shows what happened.
    addView(d.pill("Open terminal") { terminal() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
}

private val RESET = Regex("resets?\\s+(?:at\\s+|in\\s+)?([^.·|(\\n]+)", RegexOption.IGNORE_CASE)

private fun limited(c: Context, d: Design, a: Agent, message: String, send: (Remedy) -> Unit, wait: () -> Unit) = column(c).apply {
    setPadding(0, d.px(8), 0, d.px(16))
    addView(d.text("OUT OF USAGE · PAUSED MID-TASK", 14, R.color.error, 700, mono = true).apply { letterSpacing = .08f })
    val reset = RESET.find(message)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    addView(d.text(reset?.let { "Resets $it." } ?: "Out of usage.", 28, R.color.fg, 700).apply { setPadding(0, d.px(8), 0, d.px(6)) })
    addView(d.text(message, 16, R.color.dim, 500).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) })
    addView(d.pill("Wait for the reset") { wait() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
    // Only Claude Code takes "/model sonnet". Handing the task to another harness needs a summary
    // the bridge doesn't have, so that step isn't offered.
    if (Harness.of(a.kind) == Harness.CLAUDE) addView(column(c).apply {
        background = d.fill(R.color.field, 20)
        setPadding(d.px(16), d.px(12), d.px(16), d.px(12))
        minimumHeight = d.px(64)
        addView(d.text("Finish on Sonnet", 18, R.color.fg, 600))
        addView(d.text("sends /model sonnet, then continue", 13, R.color.dim, 400, mono = true).apply { setPadding(0, d.px(3), 0, 0) })
        isClickable = true
        contentDescription = "Finish on Sonnet"
        setOnClickListener {
            send(Remedy("Sends “/model sonnet”, then “continue”, to ${a.displayName} on ${a.machineLabel}. Sonnet may have its own limit.", listOf("/model sonnet", "continue")))
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) })
}

private fun full(c: Context, d: Design, a: Agent, share: Float, send: (Remedy) -> Unit) = column(c).apply {
    setPadding(0, d.px(16), 0, d.px(16))
    val pct = (share * 100).toInt().coerceAtMost(100)
    addView(LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(FrameLayout(c).apply {
            addView(Ring(c, share, d.px(10).toFloat()), FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(c).apply { setImageResource(Harness.of(a.kind).sheep); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(d.px(62), d.px(62), Gravity.CENTER))
        }, LinearLayout.LayoutParams(d.px(110), d.px(110)))
        addView(column(c).apply {
            addView(d.text("$pct%", 44, R.color.fg, 700).apply { letterSpacing = -.02f })
            addView(d.text("MEMORY ALMOST FULL", 14, R.color.accent, 700, mono = true).apply { letterSpacing = .08f; setPadding(0, d.px(6), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(16) })
        contentDescription = "Context $pct percent full"
    })
    addView(d.text(boldTail(c, "${HerdrScreen.who(a)} will start forgetting how this session began.", "Compacting keeps what matters."), 18, R.color.dim, 500).apply {
        setLineSpacing(0f, 1.15f); setPadding(0, d.px(18), 0, 0)
    })
    // Claude Code's /compact is the one verified compaction command; other harnesses get no button.
    if (Harness.of(a.kind) == Harness.CLAUDE) addView(d.pill("Compact and keep going") {
        send(Remedy("Sends “/compact” to ${a.displayName} on ${a.machineLabel}. It summarizes the session so far and keeps working from the summary.", listOf("/compact")))
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(20) })
}

/** How full something is, as an orange arc over a dark ring. */
private class Ring(c: Context, private val share: Float, stroke: Float) : View(c) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = c.getColor(R.color.raised) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = c.getColor(R.color.accent) }
    private val box = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val inset = track.strokeWidth / 2
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(box, track)
        canvas.drawArc(box, -90f, 360f * share.coerceIn(0f, 1f), false, arc)
    }
}
