package dev.r1ptt.herdr.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.HerdEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The inbox: what is waiting on you on top, then what the herd did earlier, newest first. */
class HerdActivityFeed : HerdrScreen() {
    private val d by lazy { Design(this) }
    private var events: List<HerdEvent>? = null
    private var shownKey: Any? = Unit

    override fun onResume() {
        super.onResume()
        io({ it.feed(40) }) { events = it; render() }
    }

    override fun render() {
        val open = herdr.state.deck.filter { it.state.needsYou && !it.stale }
        val list = events
        val key = list to open.map { it.sessionId + it.pendingApprovalId }
        if (shownKey == key) return
        shownKey = key
        val today = LocalDate.now()
        val done = list.orEmpty().count { it.type == "agent.completed" && runCatching { Instant.parse(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }.getOrDefault(false) }
        val sub = SpannableStringBuilder("TODAY · ").apply {
            val start = length
            append("${open.size} OPEN")
            if (open.isNotEmpty()) setSpan(ForegroundColorSpan(getColor(R.color.accent)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" · $done DONE")
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
        }
        body.addView(d.header("Inbox", sub) { finish() })
        open.forEach { body.addView(openRow(it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(14) }) }
        if (open.isEmpty()) {
            // Inbox zero fills the first screen; what got done today is a wheel turn below.
            body.addView(inboxZero(this, d, herdr.state.deck.count { it.state == dev.r1ptt.herdr.model.AgentState.WORKING }), LinearLayout.LayoutParams(-1, d.px(470)))
            if (done > 0) body.addView(d.text(SpannableStringBuilder("WHEEL · SEE THE $done DONE TODAY").apply {
                setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 13, R.color.dim, 600, mono = true).apply { gravity = android.view.Gravity.CENTER })
        }
        body.addView(d.text("EARLIER", 13, R.color.dim, 600, mono = true).apply { setPadding(d.px(4), d.px(20), 0, d.px(4)) })
        val rows = list?.let(::earlier)
        when {
            rows == null -> body.addView(d.text("Loading…", 16, R.color.dim).apply { setPadding(d.px(4), d.px(12), 0, 0) })
            rows.isEmpty() -> body.addView(d.text("Nothing has happened yet.", 16, R.color.dim).apply { setPadding(d.px(4), d.px(12), 0, 0) })
            else -> rows.forEach(body::addView)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }
        wheelScrollTarget = scroll
        setContentView(scroll)
    }

    /** An agent waiting on you, in a card, with the orange dot that says it is yours to answer. */
    private fun openRow(a: Agent) = LinearLayout(this).apply {
        val h = Harness.of(a.kind)
        gravity = Gravity.CENTER_VERTICAL
        background = d.fill(R.color.raised, 24)
        setPadding(d.px(18), d.px(16), d.px(18), d.px(16))
        addView(d.logoChip(h, 40))
        val question = herdr.state.approval(a.pendingApprovalId)?.question?.takeIf { it.isNotBlank() } ?: a.summary.ifBlank { a.state.label }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(question, 18, R.color.cream, 700).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            addView(d.text(listOfNotNull(who(a), a.machineLabel, since(a.stateSince)).joinToString(" · "), 14, R.color.dim, mono = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14); marginEnd = d.px(14) })
        addView(View(context).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(getColor(R.color.accent)) } },
            LinearLayout.LayoutParams(d.px(12), d.px(12)))
        isClickable = true
        contentDescription = "${h.label} on ${a.machineLabel} asks: $question"
        setOnClickListener { open(if (a.pendingApprovalId != null) DecisionActivity::class.java else AgentActivity::class.java, a.sessionId) }
    }

    private data class Outcome(val label: String, val color: Int)

    /**
     * One row per thing that ended. A block is not news here (it is either open above, or answered
     * below), and a resolved approval borrows the question its block asked.
     */
    private fun earlier(list: List<HerdEvent>): List<View> {
        val agents = herdr.state.deck.associateBy { it.sessionId }
        val machines = herdr.state.machines.associateBy { it.id }
        val asked = list.filter { it.type == "agent.blocked" }.associate { it.payload.optString("approval_id") to it.payload.optString("summary") }
        return list.asReversed().mapNotNull { e ->
            val agent = e.sessionId?.let(agents::get)
            val host = agent?.machineLabel ?: e.machineId?.let(machines::get)?.label ?: e.machineId
            val h = Harness.of(agent?.kind ?: e.payload.optString("agent"))
            val name = agent?.let(::who) ?: h.label
            val summary = e.payload.optString("summary")
            val (title, by, outcome) = when (e.type) {
                "agent.completed" -> Triple(summary.ifBlank { "Finished" }, name, Outcome("DONE", R.color.ok))
                "agent.failed" -> Triple(summary.ifBlank { "Failed" }, name, Outcome("FAILED", R.color.error))
                "agent.started" -> Triple(e.payload.optString("title").ifBlank { "Started" }, name, Outcome("NEW", R.color.dim))
                "approval.resolved" -> {
                    val expired = e.payload.optString("status") == "expired"
                    Triple(asked[e.payload.optString("approval_id")]?.ifBlank { null } ?: "A question", if (expired) name else "$name · answered",
                        Outcome(if (expired) "EXPIRED" else "ANSWERED", R.color.dim))
                }
                "action.receipt" -> {
                    val accepted = e.payload.optString("status") == "accepted"
                    Triple("You: ${e.payload.optString("kind").ifBlank { "action" }}", name, Outcome(if (accepted) "SENT" else "REFUSED", if (accepted) R.color.dim else R.color.error))
                }
                "machine.connected" -> Triple("$host came online", null, Outcome("ONLINE", R.color.ok))
                "machine.disconnected" -> Triple("$host went offline", null, Outcome("OFFLINE", R.color.dim))
                else -> return@mapNotNull null
            }
            row(h, title, listOfNotNull(by, host.takeIf { by != null }, clock(e.occurredAt).ifBlank { null }).joinToString(" · "), outcome)
                .apply { e.sessionId?.takeIf { it in agents }?.let { id -> isClickable = true; setOnClickListener { open(AgentActivity::class.java, id) } } }
        }
    }

    private fun row(h: Harness, title: String, sub: String, outcome: Outcome) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d.px(4), d.px(12), d.px(4), d.px(12))
        addView(d.logoChip(h, 32))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(title, 16, R.color.cream, 500).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(d.text(sub, 13, R.color.dim, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14); marginEnd = d.px(8) })
        addView(d.text(outcome.label, 13, outcome.color, 600, mono = true).apply { gravity = Gravity.END })
    }

    private fun since(iso: String) = runCatching { Duration.between(Instant.parse(iso), Instant.now()) }.getOrNull()?.let { ago(it).removeSuffix(" ago") }
}
