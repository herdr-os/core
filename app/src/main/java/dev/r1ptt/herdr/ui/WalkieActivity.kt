package dev.r1ptt.herdr.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
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
import dev.r1ptt.herdr.model.ReceiptStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Push-to-talk with one agent at a time. Each agent is a channel; the wheel changes it. Hold the
 * side button and your words go out when you let go; the agent's next reply shows in its color and
 * is spoken when voice is set up. A spoken "yes" never answers a question: an agent that is asking
 * gets a tap target instead, and nothing said here is sent to it.
 */
class WalkieActivity : HerdrScreen() {
    private var channel: String? = null
    private var insight: Insight? = null
    /** The newest agent turn already heard on this channel; only turns after it are spoken. */
    private var heardAt: String? = null
    private var sent: String? = null
    private var poll: Job? = null
    private var shownKey = ""
    private val d by lazy { Design(this) }
    override val wheelSteps = true

    private val channels get() = herdr.state.deck
    private val current: Agent? get() = channels.firstOrNull { it.sessionId == channel } ?: channels.firstOrNull()

    override val listenPrompt get() = "Talk. It goes out when you let go."
    override val sendsTo get() = current?.let { a ->
        Addressee("Ch ${channels.indexOf(a) + 1}", who(a), listOfNotNull(insight?.model, a.machineLabel).joinToString(" · "), status = "Transmitting", release = "Release · over")
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        channel = sessionId
    }

    override fun onResume() {
        super.onResume()
        tune(current?.sessionId)
    }

    override fun onPause() {
        poll?.cancel()
        super.onPause()
    }

    private fun tune(id: String?) {
        poll?.cancel()
        channel = id; insight = null; heardAt = null; sent = null; shownKey = ""
        render()
        id ?: return
        poll = scope.launch {
            while (isActive) {
                val client = herdr.client() ?: break
                withContext(Dispatchers.IO) { runCatching { client.insight(id) }.getOrNull() }?.let { fresh ->
                    val reply = fresh.messages.lastOrNull { !it.you }
                    if (reply != null && heardAt != null && reply.at > heardAt!!) { herdr.speak(Markdown.plain(reply.text)); sent = null }
                    if (reply != null) heardAt = reply.at
                    if (heardAt == null) heardAt = ""
                    insight = fresh
                    render()
                }
                delay(POLL_MS)
            }
        }
    }

    override fun onWheel(down: Boolean): Boolean {
        val list = channels
        if (list.size < 2) return false
        val i = (list.indexOf(current) + if (down) 1 else -1).mod(list.size)
        tune(list[i].sessionId)
        return true
    }

    override fun onDraft(text: String) {
        val a = current ?: return
        if (text.isBlank()) return
        if (a.pendingApprovalId != null) return showToast("${who(a)} is asking a question. Tap to answer; your voice can't approve.")
        sent = text
        render()
        io({ it.message(a.sessionId, text, a.generation) }) { r ->
            if (r.status != ReceiptStatus.ACCEPTED) { sent = null; showToast(receiptText(r)) }
            render()
        }
    }

    override fun render() {
        val a = current
        val asking = a?.let { herdr.state.approval(it.pendingApprovalId) }?.takeIf { !a.stale }
        val key = "${a?.sessionId}${a?.state}${a?.stale}${asking?.id}$insight$sent${channels.map { it.sessionId }}"
        if (key == shownKey) return
        shownKey = key
        if (a == null) {
            setContentView(ui.page("Walkie") { finish() }.also { it.body.addView(ui.text("No agents on the air. Start one with Herdr and it shows up here as a channel.")) }.root)
            return
        }
        val h = Harness.of(a.kind)
        val ink = getColor(h.ink)
        val ch = channels.indexOf(a) + 1
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(h.color))
            setPadding(d.px(28), d.px(26), d.px(28), d.px(24))
        }
        val mode = when { asking != null -> "Asking"; sent != null -> "Sent"; a.stale -> "Off air"; else -> "Receiving" }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(d.text("CH $ch · ${mode.uppercase()}", 16, h.ink, 700, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.text(listOfNotNull(insight?.model, a.machineLabel).joinToString(" · ").uppercase(), 13, h.ink, 600, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        })
        val said = asking?.question ?: sent?.let { "You said: $it" } ?: insight?.messages?.lastOrNull { !it.you }?.text?.let(Markdown::plain)
            ?: if (insight == null) "Tuning in…" else "Nothing said yet. Hold the button to talk."
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.on_accent)) }
                addView(ImageView(context).apply { setImageResource(if (a.state == AgentState.IDLE) h.asleep else h.sheep); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    FrameLayout.LayoutParams(d.px(104), d.px(104), Gravity.CENTER))
            }, LinearLayout.LayoutParams(d.px(132), d.px(132)))
            val first = said.substringBefore(". ", said).let { if (it.length < said.length) "$it." else it }
            val rest = said.removePrefix(first).trim()
            // The rest reads as the same voice, quieter; the brand fill never changes with it.
            val words = SpannableStringBuilder(first).apply { if (rest.isNotEmpty()) { append(" "); val st = length; append(rest); setSpan(ForegroundColorSpan((ink and 0xFFFFFF) or 0x99000000.toInt()), st, length, 0) } }
            addView(d.text(words, 26, h.ink, 700).apply {
                setLineSpacing(0f, 1.08f)
                maxLines = 7; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(18) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        asking?.let { root.addView(d.pill("Tap to answer", fill = R.color.accent) { open(DecisionActivity::class.java, a.sessionId) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(14) }) }
        root.addView(LinearLayout(this).apply {
            val list = channels
            val start = (list.indexOf(a) - 1).coerceIn(0, (list.size - 3).coerceAtLeast(0))
            list.drop(start).take(3).forEachIndexed { i, x ->
                val on = x == a
                addView(d.text("${start + i + 1} ${x.kind.uppercase()}", 14, if (on) R.color.fg else h.ink, 700, mono = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = d.px(40)
                    maxLines = 1
                    setPadding(d.px(12), 0, d.px(10), 0)
                    background = GradientDrawable().apply { cornerRadius = d.px(20).toFloat(); setColor(if (on) getColor(R.color.on_accent) else (ink and 0xFFFFFF) or 0x1F000000) }
                    setCompoundDrawablesRelative(getDrawable(Harness.of(x.kind).sheep)?.apply { setBounds(0, 0, d.px(26), d.px(26)) }, null, null, null)
                    compoundDrawablePadding = d.px(8)
                    isClickable = true
                    setOnClickListener { tune(x.sessionId) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = d.px(8) })
            }
        })
        root.addView(LinearLayout(this).apply {
            setPadding(0, d.px(18), 0, d.px(10))
            addView(d.twoTone("WHEEL", "· CHANGE CHANNEL", 13, h.ink, h.ink).apply { typeface = dev.r1ptt.ui.Fonts.mono(context, 600) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.twoTone("HOLD", "· TALK", 13, h.ink, h.ink).apply { typeface = dev.r1ptt.ui.Fonts.mono(context, 600) })
        })
        root.addView(d.text("SAYING “YES” NEVER APPROVES. A TOUCH DOES.", 13, h.ink, 500, mono = true))
        setContentView(root)
    }

    companion object {
        private const val POLL_MS = 3_000L
    }
}
