package dev.r1ptt.herdr.ui

import android.view.Gravity
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.Insight
import dev.r1ptt.herdr.model.ReceiptStatus

/**
 * Choose an agent's model by typing the harness's own command into its session: Claude Code
 * `/model <id>` and `/effort <level>`, Pi `/model <provider/id>` and `/thinking <level>`. Codex
 * and OpenCode only pick models in their own menus, so for them this opens that menu and the interactive terminal.
 * The current model comes from the session file; the wheel chooses, a tap on the button sends.
 */
class ModelActivity : HerdrScreen() {
    /** One model a harness accepts, by the name its command takes. */
    data class Choice(val id: String, val label: String, val note: String? = null)

    /** How each harness switches: its command, its models, its effort command and levels. */
    data class Switcher(val choices: List<Choice>, val model: ((String) -> String)?, val effort: ((String) -> String)? = null, val levels: List<String> = emptyList(), val menu: String = "/model")

    private var insight: Insight? = null
    private var loaded = false
    private var picked: Int? = null
    private var level: String? = null
    private var sending = false
    override val wheelSteps = true
    private val d by lazy { Design(this) }

    override fun onResume() {
        super.onResume()
        val id = sessionId ?: return render()
        io({ runCatching { it.insight(id) }.getOrNull() }) { insight = it; loaded = true; render() }
    }

    private fun switcher(a: Agent) = SWITCHERS[Harness.of(a.kind)] ?: Switcher(emptyList(), null)

    /** The table's models, with the one in use added first if the table doesn't know it. */
    private fun choices(a: Agent): List<Choice> {
        val known = switcher(a).choices
        val now = insight?.model ?: return known
        return if (known.any { inUse(it) }) known else listOf(Choice(now, now)) + known
    }

    private fun inUse(c: Choice) = insight?.model?.let { it == c.id || it == c.id.substringAfter('/') } == true

    override fun onWheel(down: Boolean): Boolean {
        val a = agent ?: return false
        val n = choices(a).size
        if (n == 0) return false
        picked = ((current(a) + if (down) 1 else -1).coerceIn(0, n - 1))
        render()
        return true
    }

    private fun current(a: Agent) = picked ?: choices(a).indexOfFirst(::inUse).coerceAtLeast(0)

    override fun render() {
        val a = agent
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(22), d.px(22), d.px(22), d.px(20))
        }
        root.addView(d.header("Model", a?.let { "${Harness.of(it.kind).label} · ${it.machineLabel}" }, R.drawable.ic_close) { finish() })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (a == null) {
            list.addView(d.text("This agent is no longer reported by its machine.", 18, R.color.dim))
            return setContentView(root)
        }
        val h = Harness.of(a.kind)
        val s = switcher(a)
        val all = choices(a)
        val at = current(a)
        if (!loaded) list.addView(d.text("Reading the session…", 18, R.color.dim))
        // Five rows fit; the window follows the wheel.
        val from = (at - 2).coerceIn(0, (all.size - 5).coerceAtLeast(0))
        all.drop(from).take(5).forEachIndexed { k, c ->
            val i = from + k
            list.addView(if (i == at) chosen(c, h, s.model != null) else row(c, h, i), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(4) })
        }
        if (s.levels.isNotEmpty()) root.addView(effort(s))
        val c = all.getOrNull(at)
        val newLevel = level?.takeIf { it != insight?.effort }
        val label = when {
            s.model == null -> "Open ${h.label}'s model menu"
            sending -> "Sending…"
            c != null && !inUse(c) -> "Switch to ${c.label}"
            newLevel != null -> "Set effort to ${newLevel.replaceFirstChar(Char::uppercase)}"
            else -> null
        }
        when {
            label != null -> root.addView(d.pill(label, height = 64) { send(a, s, c?.takeUnless(::inUse), newLevel) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(14) })
            s.model == null -> root.addView(d.text("Herdr doesn't know a model command for ${h.label}.", 16, R.color.dim))
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, d.px(10), 0, 0)
            addView(d.hint("Wheel", "choose").apply { layoutParams = LinearLayout.LayoutParams(-2, -2) })
        })
        setContentView(root)
    }

    private fun chosen(c: Choice, h: Harness, switchable: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = d.fill(h.color, 28)
        setPadding(d.px(22), d.px(20), d.px(22), d.px(20))
        addView(d.text(c.label, if (c.label.length > 12) 28 else 44, h.ink, 700).apply { letterSpacing = -.02f; maxLines = 1 })
        val about = listOfNotNull(c.note, if (inUse(c)) "in use" else if (switchable) "from the next turn" else "pick it in ${h.label}'s menu").joinToString(" · ")
        if (about.isNotBlank()) addView(d.text(about.uppercase(), 14, h.ink, 600, mono = true).apply { setPadding(0, d.px(8), 0, 0) })
    }

    private fun row(c: Choice, h: Harness, index: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = d.px(56)
        setPadding(d.px(22), 0, d.px(22), 0)
        isClickable = true
        setOnClickListener { picked = index; render() }
        addView(d.text(c.label, 22, R.color.dim, 500).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        if (inUse(c)) {
            addView(d.mark(h.color, 6, round = true))
            addView(d.text("IN USE", 13, h.color, 600, mono = true).apply { setPadding(d.px(6), 0, 0, 0) })
        } else c.note?.let { addView(d.text(it.uppercase(), 13, R.color.dim, 400, mono = true)) }
    }

    private fun effort(s: Switcher) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d.px(4), d.px(14), 0, 0)
        addView(d.text("EFFORT", 14, R.color.dim, 600, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
        val now = level ?: insight?.effort
        addView(LinearLayout(context).apply {
            background = d.fill(R.color.field, 28)
            setPadding(d.px(4), d.px(4), d.px(4), d.px(4))
            s.levels.forEach { l ->
                val on = l == now
                addView(d.text(l.replaceFirstChar(Char::uppercase), 16, if (on) R.color.on_accent else R.color.dim, 600).apply {
                    gravity = Gravity.CENTER
                    minHeight = d.px(48)
                    setPadding(d.px(14), 0, d.px(14), 0)
                    if (on) background = d.fill(R.color.cream, 24)
                    isClickable = true
                    setOnClickListener { level = l; render() }
                })
            }
        })
    }

    /** Types the harness's command(s) into the session; the effort follows only once the model was accepted. */
    private fun send(a: Agent, s: Switcher, model: Choice?, newLevel: String?) {
        if (sending) return
        if (s.model == null) {
            // Codex: its own menu takes over; the interactive terminal drives it with ↑ ↓ and enter.
            sending = true; render()
            return io({ it.message(a.sessionId, s.menu, a.generation) }) { r ->
                sending = false
                if (r.status == ReceiptStatus.ACCEPTED) { finish(); startActivity(android.content.Intent(this, TerminalActivity::class.java).putExtra(EXTRA_SESSION, a.sessionId).putExtra(TerminalActivity.EXTRA_INTERACTIVE, true)) }
                else { showToast(receiptText(r)); render() }
            }
        }
        val commands = listOfNotNull(model?.let { s.model.invoke(it.id) }, newLevel?.let { l -> s.effort?.invoke(l) })
        if (commands.isEmpty()) return
        sending = true; render()
        io({ c -> commands.map { if (Harness.of(a.kind) == Harness.CLAUDE && it.startsWith("/model ")) c.claudeModel(a.sessionId, it.removePrefix("/model "), a.generation) else c.message(a.sessionId, it, a.generation) }.let { rs -> rs.firstOrNull { it.status != ReceiptStatus.ACCEPTED } ?: rs.last() } }) { r ->
            sending = false
            showToast(if (r.status == ReceiptStatus.ACCEPTED) "Sent ${commands.joinToString(" then ")}" else receiptText(r))
            if (r.status == ReceiptStatus.ACCEPTED) finish() else render()
        }
    }

    companion object {
        /**
         * Claude Code's ids and effort levels are the ones its `/model` and `/effort` commands list
         * (v2.1.312); Pi's are what `pi --list-models` lists on cerebro; Pi takes a bare id, and an unknown one
         * opens its own chooser instead of switching.
         */
        val SWITCHERS = mapOf(
            Harness.CLAUDE to Switcher(listOf(
                Choice("claude-fable-5-1", "Fable 5.1", "newest"),
                Choice("claude-opus-5-5", "Opus 5.5"),
                Choice("claude-sonnet-5-5", "Sonnet 5.5", "balanced"),
                Choice("claude-haiku-5-5", "Haiku 5.5", "fastest"),
            ), { "/model $it" }, { "/effort $it" }, listOf("low", "medium", "high", "max")),
            Harness.PI to Switcher(listOf(
                Choice("gpt-6.1-sol", "GPT-6.1 Sol", "500k context"),
                Choice("gpt-6-luna", "GPT-6 Luna"),
                Choice("gpt-6-astra", "GPT-6 Astra"),
                Choice("gpt-5.5", "GPT-5.5"),
                Choice("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark", "fastest"),
            ), { "/model $it" }, { "/thinking $it" }, listOf("low", "medium", "high")),
            Harness.CODEX to Switcher(listOf(
                Choice("gpt-5.5", "GPT-5.5"),
                Choice("gpt-6-luna", "GPT-6 Luna"),
                Choice("gpt-6-astra", "GPT-6 Astra"),
                Choice("gpt-5.3-codex", "GPT-5.3 Codex"),
            ), null),
            // OpenCode's `/models` opens its own searchable chooser (checked on 1.19.3).
            Harness.OPENCODE to Switcher(emptyList(), null, menu = "/models"),
        )
    }
}
