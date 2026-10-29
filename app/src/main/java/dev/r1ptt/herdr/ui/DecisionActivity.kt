package dev.r1ptt.herdr.ui

import android.view.Gravity
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Approval
import dev.r1ptt.herdr.model.Option
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.ui.Fonts

/**
 * A blocked agent's question, large, then its options. The wheel picks; a tap opens a
 * confirmation naming exactly what goes where. Only a touch on that confirmation answers; the side
 * button never does. The answer is bound to the question's evidence fingerprint and the agent's
 * generation, so a question that changed underneath is refused rather than answered.
 */
class DecisionActivity : HerdrScreen() {
    private sealed interface Step {
        data object Choose : Step
        data class Confirm(val option: Option) : Step
        data object Sending : Step
        data class Done(val receipt: Receipt, val option: Option? = null) : Step
    }

    private var step: Step = Step.Choose
    private var highlighted = 0
    /** The approval as first shown; a different one arriving later is a new question, not this one. */
    private var approval: Approval? = null
    private var shownKey = ""
    override val wheelSteps = true

    override fun onResume() {
        super.onResume()
        shownKey = "" // re-arms the side-button fence onPause lifted
        render()
    }

    override fun onPause() {
        app.turns.guardApprovalReview(null)
        super.onPause()
    }

    override fun render() {
        val a = agent
        val live = herdr.state.approval(a?.pendingApprovalId)
        if (approval == null) approval = live
        val shown = approval
        val changed = shown != null && step !is Step.Done && step != Step.Sending && live?.evidenceFingerprint != shown.evidenceFingerprint
        (step as? Step.Done)?.let { settleWhenBack(it) }
        val key = "$step$highlighted${shown?.id}$changed${a?.stale}"
        if (key == shownKey) return
        shownKey = key
        app.turns.guardApprovalReview(if (step is Step.Confirm) ({ step = Step.Choose; render() }) else null)

        val page = ui.page(a?.let(::who) ?: "Decision") { back() }
        wheelScrollTarget = page.scroll
        val body = page.body
        when {
            a == null || shown == null -> {
                body.addView(ui.title("Nothing to decide", 26f))
                body.addView(ui.note("This agent isn't waiting for a decision right now."))
                body.addView(ui.button("Back to the herd") { finish() })
            }
            changed && live == null -> alreadyAnswered(body, a, shown)
            changed -> {
                body.addView(ui.title("The question changed", 26f))
                body.addView(ui.note("${a.displayName} moved on or asked something new. Nothing was sent."))
                body.addView(ui.button("Review the new question", primary = true) { approval = null; step = Step.Choose; highlighted = 0; render() })
            }
            else -> when (val s = step) {
                Step.Choose -> choose(body, a.project, shown)
                is Step.Confirm -> confirm(body, a, shown, s.option)
                Step.Sending -> body.addView(ui.title("Sending…", 26f))
                is Step.Done -> done(body, a, s.receipt, s.option)
            }
        }
        setContentView(page.root)
    }

    private val d by lazy { Design(this) }

    /** The agent's sheep with what it says in a bubble of its harness color. */
    private fun sheepSays(body: LinearLayout, agent: dev.r1ptt.herdr.model.Agent, words: String, size: Int = 22) {
        val h = Harness.of(agent.kind)
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(android.widget.ImageView(context).apply { setImageResource(h.sheep); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(d.px(104), d.px(104)).apply { marginStart = -d.px(8) })
            addView(d.bubble(h.color).apply {
                addView(d.text(words, size, h.ink, 700).apply { setLineSpacing(0f, 1.05f) })
                contentDescription = "${h.label} says: $words"
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = d.px(52) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(14) })
    }

    private fun choose(body: LinearLayout, project: String, a: Approval) {
        agent?.let { sheepSays(body, it, a.question.ifBlank { "Needs your input" }) }
        body.addView(ui.mono(project, R.color.dim, 11f).apply { setPadding(0, 0, 0, ui.dp(10)) })
        if (a.options.isEmpty()) {
            body.addView(ui.note("This question needs a written answer. Open the terminal to see it in full; answering in free text isn't supported yet."))
            body.addView(ui.button("Open terminal") { open(TerminalActivity::class.java, a.sessionId) })
        }
        a.options.forEachIndexed { i, o ->
            val on = i == highlighted
            body.addView(d.pill(o.label, fill = if (on) R.color.accent else R.color.raised, ink = if (on) R.color.on_accent else R.color.fg) { highlighted = i; step = Step.Confirm(o); render() }.apply {
                contentDescription = "Option ${o.index}: ${o.label}"
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(10) })
        }
        if (a.evidence.isNotBlank()) {
            body.addView(ui.section("What the agent showed"))
            body.addView(ui.text(a.evidence.lines().takeLast(14).joinToString("\n"), 12f, muted = true).apply { typeface = Fonts.mono(context, 400) })
        }
    }

    private fun confirm(body: LinearLayout, agent: dev.r1ptt.herdr.model.Agent, a: Approval, o: Option) {
        body.addView(ui.mono("Confirm answer", R.color.accent, 12f))
        body.addView(d.bubble(R.color.accent).apply {
            addView(d.text("YOU", 13, R.color.on_accent, 700, mono = true))
            addView(d.text(o.label, 22, R.color.on_accent, 700).apply { setPadding(0, d.px(4), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10); bottomMargin = d.px(12) })
        body.addView(ui.card().apply {
            addView(ui.text("To ${agent.displayName} on ${agent.machineLabel}", 15f))
            addView(ui.text(agent.project, 14f, muted = true))
            addView(ui.text("Question: ${a.question}", 14f, muted = true).apply { setPadding(0, ui.dp(6), 0, 0) })
            addView(ui.mono("gen ${a.generation} · evidence ${a.evidenceFingerprint.take(10)}", R.color.faint, 10f).apply { setPadding(0, ui.dp(8), 0, 0) })
        })
        body.addView(ui.note("Sent only if the agent still shows this exact question. The side button can't confirm."))
        body.addView(d.pill("Send answer", fill = R.color.accent) { send(a, o) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(8); bottomMargin = d.px(10) })
        body.addView(ui.button("Choose again") { step = Step.Choose; render() })
    }

    private fun send(a: Approval, o: Option) {
        step = Step.Sending; render()
        io({ it.decide(a, o.index) }) { r ->
            step = Step.Done(r, o)
            render()
            herdr.refresh()
        }
    }

    private fun done(body: LinearLayout, agent: dev.r1ptt.herdr.model.Agent, r: Receipt, option: Option?) {
        val ok = r.status == ReceiptStatus.ACCEPTED
        if (r.code == "approval_closed" || r.code == "not_blocked") return alreadyAnswered(body, agent, approval)
        if (r.status == ReceiptStatus.UNKNOWN || r.status == ReceiptStatus.PENDING) return unconfirmed(body, agent, r, option)
        val headline = if (ok) "Got it." else "Not sent"
        if (ok) sheepSays(body, agent, headline, 28) else body.addView(ui.title(headline, 30f))
        body.addView(ui.text(if (ok) "${agent.displayName} is continuing." else receiptText(r), 16f, muted = true).apply { setPadding(0, ui.dp(6), 0, ui.dp(16)) })
        if (r.code == "approval_changed") body.addView(ui.button("See what changed", primary = true) { approval = null; step = Step.Choose; highlighted = 0; render() })
        body.addView(d.pill("Back to the herd") { finish() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(8) })
    }

    /** The answer left but no receipt came back: show what was sent, never send it again, and settle it by key. */
    private fun unconfirmed(body: LinearLayout, agent: dev.r1ptt.herdr.model.Agent, r: Receipt, option: Option?) {
        body.addView(android.view.View(this), LinearLayout.LayoutParams(-1, d.px(40)))
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = d.fill(R.color.accent_soft, 18)
            setPadding(d.px(18), d.px(14), d.px(18), d.px(14))
            addView(d.text("YOU${option?.let { " · ${it.label.uppercase()}" } ?: ""}", 13, R.color.accent, 700, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            approval?.question?.takeIf { it.isNotBlank() }?.let { addView(d.text(it, 16, R.color.fg, 600).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, d.px(6), 0, 0) }) }
        }, LinearLayout.LayoutParams(d.px(250), -2).apply { gravity = Gravity.END })
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(android.widget.ImageView(context).apply { setImageResource(Harness.of(agent.kind).sheep); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(d.px(84), d.px(84)).apply { marginStart = -d.px(6) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = d.fill(R.color.raised, 18)
                setPadding(d.px(18), d.px(14), d.px(18), d.px(16))
                addView(d.text("●●● NOT CONFIRMED YET", 13, R.color.dim, 600, mono = true))
                addView(d.text(if (r.status == ReceiptStatus.PENDING) "It reached the bridge, but ${agent.displayName}’s host hasn’t acknowledged it. I won’t send it twice."
                    else "The connection dropped as you answered. It may have arrived. I won’t send it twice.", 16, R.color.fg, 500).apply { setLineSpacing(0f, 1.15f); setPadding(0, d.px(8), 0, 0) })
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = d.px(30) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) })
        body.addView(d.pill("Check if it arrived") { check(r, option) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
        body.addView(d.text("RECEIPT ${r.key.take(4).uppercase()} · CHECKS ITSELF WHEN BACK ONLINE", 13, R.color.dim, 500, mono = true).apply {
            gravity = Gravity.CENTER; setPadding(0, d.px(12), 0, 0)
        })
    }

    private fun check(r: Receipt, option: Option?) = io({ it.receipt(r.key) }) { found ->
        step = Step.Done(found ?: r.copy(detail = "The bridge has no record of it. Nothing was delivered.", status = ReceiptStatus.REJECTED), option); render()
    }

    private var settling: String? = null

    /** An uncertain receipt looks itself up once the link is live again, still without re-sending. */
    private fun settleWhenBack(done: Step.Done) {
        val r = done.receipt
        val uncertain = r.status == ReceiptStatus.UNKNOWN || r.status == ReceiptStatus.PENDING
        if (!uncertain || herdr.state.link != dev.r1ptt.herdr.Link.LIVE || settling == r.key) return
        settling = r.key
        check(r, done.option)
    }

    /** The question was settled somewhere else (the host's own terminal, another device). Nothing was sent from here. */
    private fun alreadyAnswered(body: LinearLayout, agent: dev.r1ptt.herdr.model.Agent, asked: Approval?) {
        body.addView(android.view.View(this), LinearLayout.LayoutParams(-1, d.px(60)))
        asked?.question?.takeIf { it.isNotBlank() }?.let { q ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = d.fill(R.color.field, 20)
                setPadding(d.px(20), d.px(16), d.px(20), d.px(18))
                addView(d.text("IT ASKED", 13, R.color.dim, 500, mono = true))
                addView(d.text(q, 18, R.color.dim, 600).apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG; setPadding(0, d.px(6), 0, 0) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(16) })
        }
        body.addView(LinearLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((getColor(R.color.ok) and 0x00FFFFFF) or 0x38000000)
                cornerRadius = d.px(20).toFloat()
            }
            setPadding(d.px(20), d.px(18), d.px(20), d.px(20))
            addView(d.text("✓", 22, R.color.ok, 700), LinearLayout.LayoutParams(d.px(40), -2))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(d.text("Already answered", 20, R.color.fg, 700))
                addView(d.text("${agent.displayName} isn’t asking this anymore; it was settled somewhere else. Nothing was sent from here.", 16, R.color.fg, 400).apply {
                    alpha = .8f; setLineSpacing(0f, 1.15f); setPadding(0, d.px(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        body.addView(android.view.View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(d.pill("See what it’s doing now") { finish(); open(AgentActivity::class.java, agent.sessionId) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(24) })
    }

    private fun back() { if (step is Step.Confirm) { step = Step.Choose; render() } else finish() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    override fun onWheel(down: Boolean): Boolean {
        val n = approval?.options?.size ?: 0
        if (step != Step.Choose || n == 0) return false
        highlighted = (highlighted + if (down) 1 else -1).coerceIn(0, n - 1)
        render()
        return true
    }
}
