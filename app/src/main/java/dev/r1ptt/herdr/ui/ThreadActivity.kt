package dev.r1ptt.herdr.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.r1ptt.R
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.Insight
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.herdr.model.ReceiptStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The conversation with one agent, read from its own session file: you in orange, the agent in its
 * harness color. A hold on the side button sends what you said when you let go; that is a message,
 * never an answer to a pending question (Herdr refuses to type into an agent that is asking).
 *
 * Claude Code can't take a message for one of its subagents, so with [EXTRA_ABOUT] the words go
 * to the lead, prefixed with which subagent they are about.
 */
class ThreadActivity : HerdrScreen() {
    private var insight: Insight? = null
    private var loaded = false
    private var outgoing: Pair<String, Receipt?>? = null
    private var poll: Job? = null
    private var shownKey = ""
    private val d by lazy { Design(this) }
    private val about get() = intent.getStringExtra(EXTRA_ABOUT)

    override val listenPrompt get() = "Say it. It goes when you let go."
    override val sendsTo get() = agent?.let { a ->
        val detail = listOfNotNull(insight?.model, a.machineLabel).joinToString(" · ")
        about?.let { Addressee("To subagent", it, "via ${who(a)}, its lead · $detail") } ?: Addressee("To", who(a), detail)
    }

    override fun onResume() {
        super.onResume()
        val id = sessionId ?: return
        poll = scope.launch {
            while (isActive) {
                val client = herdr.client() ?: break
                withContext(Dispatchers.IO) { runCatching { client.insight(id) } }.onSuccess { insight = it }
                loaded = true
                outgoing?.let { (text, r) -> if (r != null && insight?.messages.orEmpty().any { it.you && it.text.contains(text) }) outgoing = null }
                render()
                delay(POLL_MS)
            }
        }
    }

    override fun onPause() {
        poll?.cancel()
        super.onPause()
    }

    override fun onDraft(text: String) {
        val a = agent ?: return
        if (text.isBlank()) return open(DelegateActivity::class.java, a.sessionId)
        outgoing = text to null
        render()
        val body = about?.let { "About your subagent \"$it\": $text" } ?: text
        io({ it.message(a.sessionId, body, a.generation) }) { r ->
            outgoing = text to r
            if (r.status != ReceiptStatus.ACCEPTED) showToast(receiptText(r))
            render()
        }
    }

    private var built: String? = null
    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null

    override fun render() {
        val a = agent
        val messages = insight?.messages.orEmpty()
        val approval = a?.let { herdr.state.approval(it.pendingApprovalId)?.takeIf { _ -> !it.stale } }
        // Only what this screen shows: the poll's other numbers (context, tasks) never redraw it.
        val key = listOf(a?.state, a?.stale, approval?.id, loaded, insight == null, messages, outgoing, insight?.step).joinToString("|")
        if (key == shownKey) return
        shownKey = key
        if (a == null) {
            built = null
            setContentView(ui.page("Thread") { finish() }.also { it.body.addView(ui.text("This agent is no longer reported by its machine.")) }.root)
            return
        }
        val h = Harness.of(a.kind)
        // The frame is built once per agent; later updates only refill the list, in place.
        if (built != a.sessionId + a.stale) {
            built = a.sessionId + a.stale
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.bg))
                setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
            }
            root.addView(header(a, h))
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
            val sc = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(l) }
            wheelScrollTarget = sc
            root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d.px(16); bottomMargin = d.px(16) })
            root.addView(footer(a))
            setContentView(root)
            list = l; scroll = sc
        }
        val l = list ?: return
        val sc = scroll ?: return
        // Follow new messages only when you were already at the end; reading back stays put.
        val atEnd = l.childCount == 0 || sc.scrollY + sc.height >= l.height - d.px(24)
        l.removeAllViews()
        when {
            !loaded -> l.addView(note("Reading the conversation…"))
            insight == null -> l.addView(note("The bridge can't read this agent's session file, so there's no conversation to show. Messages still go through."))
            messages.isEmpty() && outgoing == null -> l.addView(note("Nothing said yet."))
        }
        messages.forEach { l.addView(bubble(it.you, if (it.you) R.color.accent else h.color, if (it.you) R.color.on_accent else h.ink, it.text, it.photo, label(it))) }
        outgoing?.let { (text, r) ->
            val state = when (r?.status) { null, ReceiptStatus.PENDING -> "Sending"; ReceiptStatus.ACCEPTED -> "Delivered"; ReceiptStatus.REJECTED -> "Not sent"; ReceiptStatus.UNKNOWN -> "Uncertain" }
            l.addView(bubble(true, R.color.accent, R.color.on_accent, text, null, listOfNotNull(about?.let { "Via lead" }, state).joinToString(" · ")))
        }
        if (a.state == AgentState.WORKING && !a.stale) l.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d.px(4), d.px(4), 0, 0)
            addView(d.text("•••", 16, h.color, 700))
            addView(d.text(insight?.step ?: "Working", 13, R.color.dim, 400, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(d.px(10), 0, 0, 0) })
        })
        approval?.let { q ->
            l.addView(d.pill("Answer: ${q.question.take(60)}", fill = R.color.accent) { open(DecisionActivity::class.java, a.sessionId) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(12) })
        }
        // Scrolled before the next frame draws, so the list never shows at the top first.
        if (atEnd) sc.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean { sc.viewTreeObserver.removeOnPreDrawListener(this); sc.scrollTo(0, l.height); return true }
        })
    }

    private fun label(m: Insight.Message) = listOfNotNull(m.photo?.let { "Photo" }, clock(m.at).ifBlank { null }).joinToString(" · ").ifBlank { null }

    private fun note(text: String) = d.text(text, 16, R.color.dim).apply { setPadding(d.px(4), 0, d.px(4), d.px(8)) }

    private fun header(a: Agent, h: Harness) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(round(R.drawable.ic_back, "Back", 64) { finish() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(h.sheep); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(d.px(34), d.px(34)))
                addView(d.text(who(a), 22, R.color.fg, 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(d.px(10), 0, 0, 0) })
            })
            val sub = listOfNotNull(about?.let { "About $it" }, insight?.model, a.machineLabel).joinToString(" · ").uppercase()
            addView(d.text(sub, 14, R.color.dim, 500, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(16) })
        addView(round(R.drawable.ic_display, "Terminal", 56) { open(TerminalActivity::class.java, a.sessionId) })
    }

    private fun footer(a: Agent) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(d.twoTone("Hold to message", about?.let { "${who(a)} about $it" } ?: who(a), 18, R.color.dim, R.color.fg).apply {
            typeface = dev.r1ptt.ui.Fonts.display(context, 400)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = d.px(64)
            setPadding(d.px(22), 0, d.px(16), 0)
            background = d.fill(R.color.field, 32)
            setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.accent)); setSize(d.px(10), d.px(10)) }, null, null, null)
            compoundDrawablePadding = d.px(12)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(round(R.drawable.ic_camera, "Show it a photo", 64) { open(CameraActivity::class.java, a.sessionId) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(10) })
        addView(round(R.drawable.ic_keyboard, "Type a message", 64) { onDraft("") }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(8) })
    }

    private fun round(icon: Int, description: String, size: Int, action: () -> Unit) = ui.iconButton(icon, description, action = action).apply {
        background = d.fill(R.color.raised, size / 2)
        layoutParams = LinearLayout.LayoutParams(d.px(size), d.px(size))
        minimumWidth = d.px(size); minimumHeight = d.px(size)
    }

    /** A speech bubble; its tail corner points at the speaker's side. */
    private fun bubble(you: Boolean, fill: Int, ink: Int, text: String, photo: String?, label: String?) = LinearLayout(this).apply {
        gravity = if (you) Gravity.END else Gravity.START
        addView(d.bubble(fill).apply {
            val r = d.px(24).toFloat(); val tail = d.px(8).toFloat()
            background = GradientDrawable().apply {
                setColor(getColor(fill))
                cornerRadii = if (you) floatArrayOf(r, r, r, r, tail, tail, r, r) else floatArrayOf(r, r, r, r, r, r, tail, tail)
            }
            photo?.let { thumbnail(it) }?.let { addView(it, LinearLayout.LayoutParams(-1, d.px(140)).apply { bottomMargin = d.px(10) }) }
            if (you && label != null) addView(d.text(label.uppercase(), 13, ink, 600, mono = true).apply { alpha = .7f; setPadding(0, 0, 0, d.px(6)) })
            if (text.isNotBlank()) addView(d.text(Markdown.render(text, getColor(ink)), 18, ink, 500).apply { setLineSpacing(0f, 1.15f) })
            contentDescription = "${if (you) "You" else "Agent"}: ${Markdown.plain(text)}"
        }, LinearLayout.LayoutParams(-2, -2).apply { if (you) marginStart = d.px(80) else marginEnd = d.px(68) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(12) }
    }

    /** The photo as taken, when this R1 still has its copy; the host keeps the original. */
    private fun thumbnail(hostPath: String): View? {
        val file = CameraActivity.localCopy(this, hostPath).takeIf(File::exists) ?: return null
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
        return ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = d.fill(R.color.bg, 16)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    companion object {
        const val EXTRA_ABOUT = "herdr.thread.about"
        private const val POLL_MS = 3_000L
    }
}
