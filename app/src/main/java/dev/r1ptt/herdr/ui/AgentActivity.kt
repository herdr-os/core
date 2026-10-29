package dev.r1ptt.herdr.ui

import android.app.AlertDialog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.HerdEvent
import dev.r1ptt.herdr.model.Insight
import dev.r1ptt.herdr.ui.HerdrScreen.Companion.EXTRA_SESSION
import dev.r1ptt.herdr.ui.HerdrScreen.Companion.shortPath
import dev.r1ptt.herdr.ui.HerdrScreen.Companion.stateLine
import dev.r1ptt.herdr.ui.HerdrScreen.Companion.who

class AgentActivity : PageTrampoline()

/**
 * One agent at a glance: its card, what it last did, and the ways in (decision, message,
 * terminal, changes). The pasture holds this sheep's place while it is open.
 */
class AgentPage(host: HerdActivity, sessionId: String?) : Page(host, sessionId) {
    private var events: List<HerdEvent>? = null
    private var insight: Insight? = null
    private var shownKey = ""

    override val listenPrompt get() = "Tell ${agent?.displayName ?: "this agent"} what to do."

    override fun onShow() {
        herdr.state.held = sessionId
        val id = sessionId ?: return
        // Reading the session is what tells the bridge a finished card has been seen.
        io({ it.session(id); it.activity(id) }) { events = it; shownKey = ""; render(); herdr.refresh() }
        // A bridge that predates the insight route, or a harness it can't read, just shows less.
        io({ runCatching { it.insight(id) }.getOrNull() }) { insight = it; shownKey = ""; render() }
    }

    override fun onHide() {
        if (herdr.state.held == sessionId) herdr.state.held = null
    }

    override fun render() {
        val a = agent
        val key = "${a?.state}${a?.stale}${a?.summary}${a?.pendingApprovalId}${a?.generation}${events?.size}${insight}${herdr.state.link}"
        if (key == shownKey) return
        shownKey = key
        val page = ui.page(a?.let(::who) ?: "Agent") { finish() }
        scrollTarget = page.scroll
        if (a == null) {
            page.body.addView(ui.text("This agent is no longer reported by its machine."))
            setContentView(page.root)
            return
        }
        page.body.addView(glance(a))
        trouble(context, d, a, insight, { remedy(a, it) }, { open(TerminalActivity::class.java, a.sessionId) }) { finish() }?.let(page.body::addView)
        page.body.addView(linkView().apply { setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(10)) })
        val approval = herdr.state.approval(a.pendingApprovalId)
        if (approval != null && !a.stale)
            page.body.addView(d.pill("Answer: ${approval.question.take(60)}", fill = R.color.accent) { open(DecisionActivity::class.java, a.sessionId) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(12) })
        page.body.addView(ui.grid(listOf(
            ui.navTile("Message", R.drawable.ic_send, "The conversation") { open(ThreadActivity::class.java, a.sessionId) },
            ui.navTile("Walkie", R.drawable.ic_wave, "Talk and hear back") { open(WalkieActivity::class.java, a.sessionId) },
            ui.navTile("Show it", R.drawable.ic_camera, "Send a photo") { open(CameraActivity::class.java, a.sessionId) },
            ui.navTile("Terminal", R.drawable.ic_keyboard, "Live output") { open(TerminalActivity::class.java, a.sessionId) },
            ui.navTile("Changes", R.drawable.ic_chip, "Review the diff") { open(ChangesActivity::class.java, a.sessionId) },
            ui.navTile("Files", R.drawable.ic_more, "Browse the repository") { open(FilesActivity::class.java, a.sessionId) },
            ui.navTile("Stop", R.drawable.ic_stop, "Interrupt the agent") { confirmStop(a) },
        ).onEach { Motion.press(it) }))
        page.body.work(insight)
        page.body.addView(ui.section("Activity"))
        val list = events
        when {
            list == null -> page.body.addView(ui.note("Loading…"))
            list.isEmpty() -> page.body.addView(ui.note("Nothing yet."))
            else -> list.takeLast(12).asReversed().forEach { page.body.addView(eventRow(it)) }
        }
        setContentView(page.root)
    }

    /** The agent's sheep beside who it is and what it is doing, in words. */
    private fun glance(a: Agent) = LinearLayout(context).apply {
        val h = Harness.of(a.kind)
        gravity = Gravity.CENTER_VERTICAL
        if (a.stale) alpha = .6f
        contentDescription = "${h.label}, ${a.project}, on ${a.machineLabel}. ${stateLine(a)}"
        addView(android.widget.ImageView(context).apply {
            setImageResource(if (a.state == dev.r1ptt.herdr.model.AgentState.IDLE) h.asleep else h.sheep)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(d.px(104), d.px(104)).apply { marginStart = -d.px(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(d.text(who(a), 22, R.color.fg, 700))
            addView(d.text(a.project.ifBlank { a.title }, 16, R.color.dim, 500).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(3), 0, 0) })
            addView(d.text(stateLine(a).uppercase(), 13, if (a.state.needsYou && !a.stale) R.color.accent else R.color.fg, 600, mono = true).apply { maxLines = 2; setPadding(0, d.px(8), 0, 0) })
            // What it is doing right now, from its own session file.
            insight?.step?.takeIf { a.state == dev.r1ptt.herdr.model.AgentState.WORKING && !a.stale }?.let { addView(d.text(it, 14, R.color.fg, 500).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0) }) }
            addView(d.text("${a.machineLabel.uppercase()} · ${shortPath(a.cwd) ?: a.sessionId}", 13, R.color.dim, 500, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0) })
            insight?.let { modelLine(it) }?.let { addView(d.text(it, 13, R.color.dim, 500, mono = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, d.px(4))
                contentDescription = "Model: $it. Change model"
                isClickable = true
                setOnClickListener { open(ModelActivity::class.java, a.sessionId) }
            }) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(6) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(4) }
    }

    /** Model, effort and how full the context is, e.g. "CLAUDE-OPUS-5-5 · MEDIUM · 110K CONTEXT". */
    private fun modelLine(i: Insight): String? {
        val ctx = i.contextUsed?.let { u -> i.contextWindow?.let { "${k(u)} OF ${k(it)} CONTEXT" } ?: "${k(u)} CONTEXT" }
        return listOfNotNull(i.model, i.effort, ctx).joinToString(" · ").uppercase().ifBlank { null }
    }

    private fun k(n: Int) = if (n >= 1000) "${n / 1000}K" else "$n"

    /** Subagents and background commands: everything still running, then the last few that ended. */
    private fun LinearLayout.work(i: Insight?) {
        val all = i?.let { it.subagents + it.tasks } ?: return
        if (all.isEmpty()) return
        val shown = all.filter { it.running } + all.filterNot { it.running }.takeLast(3).asReversed()
        addView(ui.section("Subagents & tasks"))
        shown.forEach { addView(workRow(it, it in i.subagents)) }
    }

    private fun workRow(w: Insight.Work, subagent: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = d.px(56)
        isClickable = true
        setOnClickListener { context.startActivity(android.content.Intent(context, WorkActivity::class.java).putExtra(EXTRA_SESSION, sessionId).putExtra(WorkActivity.EXTRA_TASKS, !subagent)) }
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        val tint = when { w.running -> R.color.fg; w.state == "failed" -> R.color.error; else -> R.color.dim }
        addView(d.text(w.state.uppercase(), 13, tint, 600, mono = true), LinearLayout.LayoutParams(d.px(96), -2))
        addView(d.text(w.label, 16, if (w.running) R.color.fg else R.color.dim, 500).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(d.text(if (subagent) w.kind ?: "agent" else "bash", 13, R.color.dim, 500, mono = true))
    }

    private fun eventRow(e: HerdEvent) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        val what = e.payload.optString("summary").ifBlank { e.payload.optString("kind").ifBlank { e.type.substringAfter('.') } }
        addView(ui.mono(e.type.substringAfter('.'), R.color.accent, 10f), LinearLayout.LayoutParams(ui.dp(84), -2))
        addView(ui.text(what, 14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.mono(clock(e.occurredAt), R.color.faint, 10f))
    }

    /** A fix from [trouble]: confirmed by touch, then its messages in order, stopping at the first that is refused or lost. */
    private fun remedy(a: Agent, r: Remedy) {
        showDeviceDialog(AlertDialog.Builder(context).setMessage(r.confirm).setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                io({ c -> r.messages.fold(null as dev.r1ptt.herdr.model.Receipt?) { last, text ->
                    if (last != null && (last.status == dev.r1ptt.herdr.model.ReceiptStatus.REJECTED || last.status == dev.r1ptt.herdr.model.ReceiptStatus.UNKNOWN)) last else if (text.startsWith("/model ")) c.claudeModel(a.sessionId, text.removePrefix("/model "), a.generation) else c.message(a.sessionId, text, a.generation)
                }!! }) { showToast(receiptText(it)); herdr.refresh() }
            })
    }

    private fun confirmStop(a: Agent) {
        showDeviceDialog(AlertDialog.Builder(context)
            .setTitle("Stop ${a.displayName}?")
            .setMessage("Sends Esc to ${a.displayName} on ${a.machineLabel} (${a.project}). The agent decides how to stop.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Stop") { _, _ -> io({ it.stop(a.sessionId, a.generation) }) { r -> showToast(receiptText(r)); herdr.refresh() } })
    }
}
