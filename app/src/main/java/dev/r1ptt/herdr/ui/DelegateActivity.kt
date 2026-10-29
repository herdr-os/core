package dev.r1ptt.herdr.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Candidate
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.herdr.model.Resolution

/**
 * Speech or typing becomes a task only here, and only after review: the draft is resolved by the
 * bridge (read-only), the destination is shown, an ambiguous match becomes a choice, and the
 * user's tap on Send is what dispatches. A held button adds words to the draft; it never sends.
 */
class DelegateActivity : HerdrScreen() {
    private sealed interface Step {
        data object Draft : Step
        data object Resolving : Step
        data class Review(val r: Resolution) : Step
        /** Who takes it: the wheel moves through [choices] when the bridge found more than one. */
        data class Confirm(val choices: List<Candidate>, val at: Int, val instruction: String) : Step {
            val target get() = choices[at]
        }
        data object Sending : Step
        data class Done(val target: Candidate, val receipt: Receipt) : Step
    }

    private var step: Step = Step.Draft
    private var draft = ""
    private var field: EditText? = null
    private var shownStep: Step? = null
    /** The model each candidate's session file names, fetched once per candidate. */
    private val models = mutableMapOf<String, String?>()
    private val d by lazy { Design(this) }
    override val wheelSteps get() = (step as? Step.Confirm)?.choices?.size?.let { it > 1 } == true

    override fun onWheel(down: Boolean): Boolean {
        val s = step as? Step.Confirm ?: return false
        if (s.choices.size < 2) return false
        step = s.copy(at = (s.at + if (down) 1 else -1).coerceIn(0, s.choices.size - 1))
        render()
        return true
    }

    override val listenPrompt get() = if (step == Step.Draft) "Keep talking; it's added to your draft." else super.listenPrompt

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        draft = intent.getStringExtra(EXTRA_TEXT).orEmpty()
    }

    override fun onDraft(text: String) {
        draft = listOf(field?.text?.toString() ?: draft, text).filter(String::isNotBlank).joinToString(" ")
        step = Step.Draft
        shownStep = null
        render()
    }

    override fun render() {
        if (step == shownStep) return
        shownStep = step
        (step as? Step.Confirm)?.let { return confirm(it) }
        val focus = agent
        val page = ui.page(if (focus != null) "Message ${focus.displayName}" else "New task") { back() }
        wheelScrollTarget = page.scroll
        val body = page.body
        when (val s = step) {
            Step.Draft -> {
                body.addView(d.text(focus?.let { "To ${who(it)} · ${it.machineLabel}" }?.uppercase() ?: "NEW TASK · YOU SAID", 14, R.color.accent, 600, mono = true).apply { setPadding(0, 0, 0, d.px(10)) })
                field = ui.field("e.g. Ask Codex on my MacBook to write regression tests for auth", draft,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
                    minLines = 3
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d.px(28).toFloat())
                    typeface = dev.r1ptt.ui.Fonts.display(context, 700)
                    setTextColor(getColor(R.color.cream))
                    background = d.fill(R.color.field, 20)
                }
                body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(14) })
                body.addView(d.hint("Hold", "keep talking").apply { setPadding(0, 0, 0, d.px(14)) })
                body.addView(d.pill("Review") { resolve() }, LinearLayout.LayoutParams(-1, -2))
            }
            Step.Resolving -> body.addView(ui.title("Finding the right agent…", 24f))
            is Step.Review -> review(body, s.r)
            is Step.Confirm -> Unit
            Step.Sending -> body.addView(ui.title("Sending…", 24f))
            is Step.Done -> done(body, s.target, s.receipt)
        }
        setContentView(page.root)
    }

    private fun resolve() {
        draft = field?.text?.toString()?.trim().orEmpty()
        if (draft.isBlank()) return showToast("Say or type what you want done")
        if (herdr.client() == null) return showToast("Pair a Herdr bridge first")
        step = Step.Resolving; render()
        io({ it.resolve(draft, sessionId) }) { r ->
            val match = r.candidates.firstOrNull()
            step = when {
                r.status == "resolved" && match != null -> Step.Confirm(listOf(match), 0, r.instruction)
                r.status == "ambiguous" && match != null -> Step.Confirm(r.candidates, 0, r.instruction)
                else -> Step.Review(r)
            }
            render()
        }
    }

    private fun review(body: LinearLayout, r: Resolution) {
        body.addView(ui.title(if (r.status == "not_an_operation") "That sounds like a question" else "No agent matches", 26f))
        body.addView(ui.note(r.reason))
        body.addView(ui.note("Name an agent and what it should do, like “Ask Claude on my MacBook to fix the failing test.” Or open an agent and talk to it directly."))
        body.addView(ui.button("Edit the draft") { step = Step.Draft; render() })
    }

    /**
     * What you said, large, and who takes it: one chip per candidate (the wheel moves between
     * them), its machine and project, and its model when its session file names one. The companion
     * asks; the tap on Send is what dispatches.
     */
    private fun confirm(s: Step.Confirm) {
        val c = s.target
        val live = herdr.state.agent(c.sessionId)
        val name = live?.let(::who) ?: Harness.of(c.agent).label
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
        }
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(d.text("NEW TASK · YOU SAID", 14, R.color.accent, 600, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.round(R.drawable.ic_back) { back() }.apply { contentDescription = "Edit the draft" })
        })
        root.addView(d.text("“${s.instruction}”", if (s.instruction.length > 70) 28 else 44, R.color.cream, 700).apply {
            letterSpacing = -.02f
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, d.px(10), 0, 0)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (s.choices.size > 1) root.addView(d.text(android.text.SpannableStringBuilder("WHEEL · CHANGE WHO TAKES IT").apply {
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.accent)), 0, 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, 14, R.color.dim, 600, mono = true).apply { setPadding(d.px(4), 0, 0, d.px(10)) })
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                s.choices.forEachIndexed { i, x ->
                    val h = Harness.of(x.agent)
                    val dup = s.choices.count { Harness.of(it.agent) == h } > 1
                    addView(d.text(h.label + if (dup) " · ${x.machine}" else "", 16, if (i == s.at) h.ink else R.color.cream, 600).apply {
                        gravity = android.view.Gravity.CENTER
                        minHeight = d.px(56)
                        setPadding(d.px(18), 0, d.px(18), 0)
                        background = d.fill(if (i == s.at) h.color else R.color.field, 28)
                        isClickable = true
                        setOnClickListener { step = s.copy(at = i); render() }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = d.px(8) })
                }
            })
        })
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, d.px(8), 0, d.px(14))
            addView(d.text("${c.machine} · ${shortPath(live?.cwd) ?: c.project}".uppercase(), 14, R.color.on_accent, 600, mono = true).apply {
                maxLines = 1
                maxWidth = d.px(290)
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = d.fill(R.color.cream, 22)
                setPadding(d.px(14), d.px(12), d.px(14), d.px(12))
            })
            models[c.sessionId]?.let { m ->
                addView(d.text(m.uppercase(), 14, R.color.dim, 600, mono = true).apply {
                    maxLines = 1
                    background = d.fill(R.color.field, 22)
                    setPadding(d.px(14), d.px(12), d.px(14), d.px(12))
                    contentDescription = "Model $m. Change model"
                    isClickable = true
                    setOnClickListener { open(ModelActivity::class.java, c.sessionId) }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(8) })
            }
        })
        if (!models.containsKey(c.sessionId)) {
            models[c.sessionId] = null
            io({ runCatching { it.insight(c.sessionId) }.getOrNull()?.model }) { m -> models[c.sessionId] = m; if (m != null && (step as? Step.Confirm)?.target == c) { shownStep = null; render() } }
        }
        val sendable = live != null && !live.stale
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.BOTTOM
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.sheep_plain)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(d.px(88), d.px(88)).apply { marginStart = -d.px(6) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(d.bubble(R.color.cream, 14).apply {
                    addView(d.text(if (sendable) "$name on ${c.machine} takes it. Send it there?" else "$name's machine isn't live right now, so nothing can be sent.", 16, R.color.on_accent, 500))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(10) })
                if (sendable) addView(d.pill("Send to the herd", height = 64) {
                    step = Step.Sending; render()
                    io({ it.delegate(c.sessionId, s.instruction, live!!.generation) }) { r -> step = Step.Done(c, r); render(); herdr.refresh() }
                }, LinearLayout.LayoutParams(-1, -2))
                else addView(d.pill("Edit the draft", fill = R.color.field, ink = R.color.cream, height = 64) { step = Step.Draft; render() }, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(6) })
        })
        setContentView(root)
    }

    private fun done(body: LinearLayout, c: Candidate, r: Receipt) {
        val ok = r.status == ReceiptStatus.ACCEPTED
        body.addView(ui.title(when (r.status) {
            ReceiptStatus.ACCEPTED -> "Task started"
            ReceiptStatus.PENDING -> "Sending…"
            ReceiptStatus.UNKNOWN -> "Not sure it arrived"
            ReceiptStatus.REJECTED -> "Not started"
        }, 28f))
        body.addView(ui.text(if (ok) "${c.agent.replaceFirstChar(Char::uppercase)} on ${c.machine} has it." else receiptText(r), 16f, muted = true).apply { setPadding(0, ui.dp(6), 0, ui.dp(16)) })
        if (r.status == ReceiptStatus.UNKNOWN || r.status == ReceiptStatus.PENDING) body.addView(ui.button("Check status", primary = true) {
            io({ it.receipt(r.key) }) { found -> step = Step.Done(c, found ?: r.copy(status = ReceiptStatus.REJECTED, detail = "none: The bridge has no record of it. Nothing was delivered.")); render() }
        })
        body.addView(ui.button("Open ${c.agent.replaceFirstChar(Char::uppercase)}", primary = ok) { finish(); open(AgentActivity::class.java, c.sessionId) })
        body.addView(ui.button("Back to the herd") { finish() })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    private fun back() {
        when (step) {
            is Step.Review, is Step.Confirm -> { step = Step.Draft; render() }
            else -> finish()
        }
    }

    companion object {
        const val EXTRA_TEXT = "herdr.draft"
    }
}
