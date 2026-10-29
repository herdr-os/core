package dev.r1ptt.herdr.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.Insight
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * What an agent has handed off, read from its own session file: its subagents (the lead on top,
 * each helper's latest step) or, with [EXTRA_TASKS], its background commands and their last output.
 * Refreshed every few seconds while open. A hold messages the lead agent; nothing here sends.
 */
class WorkActivity : HerdrScreen() {
    private var insight: Insight? = null
    private var loaded = false
    private var poll: Job? = null
    private var scroll: ScrollView? = null
    private val tasks get() = intent.getBooleanExtra(EXTRA_TASKS, false)
    private val d by lazy { Design(this) }

    override val listenPrompt get() = "Tell ${agent?.let(::who) ?: "this agent"} what to do."

    override fun onStart() {
        super.onStart()
        val id = sessionId ?: return render()
        poll = scope.launch {
            while (true) {
                io({ runCatching { it.insight(id) }.getOrNull() }) { insight = it; loaded = true; render() }
                delay(POLL_MS)
            }
        }
    }

    override fun onStop() {
        poll?.cancel()
        super.onStop()
    }

    override fun render() {
        val a = agent
        val i = insight
        val all = (if (tasks) i?.tasks else i?.subagents).orEmpty()
        val shown = all.filter { it.running }.asReversed() + all.filterNot { it.running }.asReversed()
        val where = a?.let { "${Harness.of(it.kind).label} · ${it.machineLabel}" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
        }
        root.addView(d.header(if (tasks) "Running" else "Subagents",
            if (tasks) listOfNotNull("${all.size} ${if (all.size == 1) "task" else "tasks"}", where).joinToString(" · ") else where?.let { "of $it" }) { finish() })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d.px(18), 0, d.px(8)) }
        // Redrawn every poll; the reader keeps their place.
        val y = scroll?.scrollY ?: 0
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body); post { scrollTo(0, y) } }
        this.scroll = scroll
        wheelScrollTarget = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when {
            a == null -> body.addView(d.text("This agent is no longer reported by its machine.", 18, R.color.dim))
            !loaded -> body.addView(d.text("Reading the session…", 18, R.color.dim))
            i == null -> body.addView(d.text("${who(a)}'s session file can't be read from here, so its ${if (tasks) "commands" else "subagents"} aren't shown.", 18, R.color.dim))
            tasks -> taskList(body, a, shown)
            else -> subagentList(body, a, shown)
        }
        if (a != null) root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (tasks) addView(d.pill("Watch output", fill = R.color.field, ink = R.color.cream) { open(TerminalActivity::class.java, a.sessionId) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(12) })
            addView(d.hint("Wheel", "scroll"))
            addView(d.hint("Hold", "message ${who(a)}").apply { setPadding(0, d.px(8), 0, 0) })
        })
        setContentView(root)
    }

    private fun subagentList(body: LinearLayout, a: Agent, shown: List<Insight.Work>) {
        val h = Harness.of(a.kind)
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = d.fill(h.color, 24)
            setPadding(d.px(20), d.px(16), d.px(20), d.px(16))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(d.text(who(a), 22, h.ink, 700))
                addView(d.text(a.summary.ifBlank { a.title.ifBlank { a.project } }, 16, h.ink, 500).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.text("LEAD", 14, h.ink, 600, mono = true))
        })
        if (shown.isEmpty()) return body.addView(d.text("No subagents in this session.", 18, R.color.dim).apply { setPadding(0, d.px(18), 0, 0) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shown.forEach { rows.addView(subagentRow(it, h), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(8) }) }
        // The tree line ties every helper back to the lead card above.
        body.addView(FrameLayout(this).apply {
            addView(View(context).apply { setBackgroundColor(getColor(R.color.outline)) }, FrameLayout.LayoutParams(d.px(2), -1).apply { marginStart = d.px(18); bottomMargin = d.px(34) })
            addView(rows, FrameLayout.LayoutParams(-1, -2).apply { marginStart = d.px(34) })
        })
    }

    private fun subagentRow(w: Insight.Work, h: Harness) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d.px(18), d.px(if (w.running) 16 else 14), d.px(18), d.px(if (w.running) 16 else 14))
        if (w.running) background = d.fill(R.color.field, 20)
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        line.addView(d.mark(h.color, 12).apply { alpha = if (w.running) 1f else .55f })
        line.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(w.kind ?: w.label, if (w.running) 21 else 18, if (w.state == "done") R.color.dim else R.color.cream, if (w.running) 700 else 600).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val sub = listOfNotNull(w.label.takeIf { w.kind != null }, timing(w)).joinToString(" · ")
            if (sub.isNotBlank()) addView(d.text(sub, 14, R.color.dim, 400, mono = true).apply { letterSpacing = 0f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14); marginEnd = d.px(10) })
        line.addView(stateLabel(w, h))
        addView(line)
        if (w.running) {
            w.step?.let { addView(d.text("› $it", 14, R.color.dim, 400, mono = true).apply { letterSpacing = 0f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(12), 0, 0) }) }
            val about = listOfNotNull(w.model, w.contextUsed?.let { "${it / 1000}K context" }).joinToString(" · ")
            if (about.isNotBlank()) addView(d.text(about.uppercase(), 13, R.color.dim, 500, mono = true).apply { maxLines = 1; setPadding(0, d.px(6), 0, 0) })
        }
    }

    private fun stateLabel(w: Insight.Work, h: Harness) = when {
        w.running -> d.text(if (tasks) "RUNNING" else "WORKING", 14, h.color, 600, mono = true)
        w.state == "failed" -> d.text("FAILED", 14, R.color.error, 600, mono = true)
        w.state == "stopped" -> d.text("STOPPED", 14, R.color.dim, 600, mono = true)
        else -> d.text("DONE", 14, R.color.ok, 600, mono = true)
    }

    private fun taskList(body: LinearLayout, a: Agent, shown: List<Insight.Work>) {
        if (shown.isEmpty()) return body.addView(d.text("No background commands in this session.", 18, R.color.dim))
        val h = Harness.of(a.kind)
        val top = shown.first()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = d.fill(R.color.field, 24)
            setPadding(d.px(20), d.px(18), d.px(20), d.px(18))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(d.mark(h.color, 10))
                addView(d.text("${h.label} · ${a.machineLabel}".uppercase(), 14, R.color.dim, 600, mono = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(8) })
                addView(d.text(clockOf(top), 13, R.color.cream, 600, mono = true))
            })
            addView(d.text(top.label, 17, R.color.cream, 500, mono = true).apply { letterSpacing = 0f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(12), 0, 0) })
            val tail = top.tail.ifEmpty { listOfNotNull(outcome(top)) }
            tail.forEach { addView(d.text(it, 14, if (top.state == "failed") R.color.error else R.color.dim, 400, mono = true).apply { letterSpacing = 0f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(6), 0, 0) }) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(4) })
        shown.drop(1).forEach { w ->
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d.px(20), d.px(14), d.px(20), d.px(14))
                addView(d.mark(when { w.running -> R.color.ok; w.state == "failed" -> R.color.error; else -> R.color.dim }, 10, round = true))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(d.text(w.label, 15, R.color.cream, 500, mono = true).apply { letterSpacing = 0f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    val sub = listOfNotNull(outcome(w), w.tail.lastOrNull()).joinToString(" · ")
                    addView(d.text(sub, 14, if (w.state == "failed") R.color.error else R.color.dim, 400, mono = true).apply { letterSpacing = 0f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(3), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14); marginEnd = d.px(8) })
                addView(d.text(clockOf(w), 13, R.color.dim, 400, mono = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(d.px(56), -2))
            })
        }
    }

    /** "running", "exit 1", "done": what the harness recorded, nothing more. */
    private fun outcome(w: Insight.Work): String? = when {
        w.running -> "running"
        w.exitCode != null && w.exitCode != 0 -> "exit ${w.exitCode}"
        else -> w.state
    }

    private fun seconds(from: String?, to: String?): Long? = runCatching {
        Duration.between(Instant.parse(from), to?.let(Instant::parse) ?: Instant.now()).seconds
    }.getOrNull()

    private fun clockOf(w: Insight.Work) = seconds(w.startedAt, w.endedAt)?.let(::elapsed) ?: ""

    /** "38s" while running, "done 4m ago" after. */
    private fun timing(w: Insight.Work): String? = if (w.running) seconds(w.startedAt, null)?.let { if (it < 60) "${it}s" else elapsed(it) }
        else seconds(w.endedAt, null)?.let { "${w.state} ${ago(Duration.ofSeconds(it))}" }

    companion object {
        const val EXTRA_TASKS = "herdr.tasks"
        private const val POLL_MS = 5_000L
    }
}
