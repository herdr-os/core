package dev.r1ptt.herdr.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.Link
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.Insight

/**
 * Every agent on every host as a deck of cards in harness colors, the workbench view of the herd.
 * The wheel deals the next card, the chips narrow the deck to one host, a tap opens the agent.
 */
class HerdListActivity : HerdrScreen() {
    private val d by lazy { Design(this) }
    private var host: String? = null
    private var index = 0
    private val insights = HashMap<String, Insight?>()
    private var shownKey = ""
    override val wheelSteps = true

    override fun render() {
        val deck = herdr.state.deck
        val cards = deck.filter { host == null || it.machineLabel == host }
        index = index.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        val front = cards.getOrNull(index)
        if (front != null && front.sessionId !in insights) {
            insights[front.sessionId] = null
            io({ runCatching { it.insight(front.sessionId) }.getOrNull() }) { insights[front.sessionId] = it; render() }
        }
        val key = listOf(host, index, herdr.state.link, front?.let { insights[it.sessionId] },
            deck.joinToString { "${it.sessionId}${it.state}${it.stale}${it.summary}" }).joinToString("|")
        if (key == shownKey) return
        shownKey = key

        val live = herdr.state.link == Link.LIVE
        val hosts = deck.map { it.machineLabel }.distinct()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
        }
        root.addView(d.header("The Herd", SpannableStringBuilder(if (live) "LIVE · ${hosts.size} ${if (hosts.size == 1) "HOST" else "HOSTS"}" else linkLabel().uppercase()).apply {
            setSpan(ForegroundColorSpan(getColor(if (live) R.color.ok else R.color.dim)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }) { finish() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                addView(chip("ALL", deck.size, host == null) { host = null; index = 0; render() })
                hosts.forEach { h -> addView(chip(h.uppercase(), deck.count { it.machineLabel == h }, host == h) { host = h; index = 0; render() }) }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
        root.addView(FrameLayout(this).apply {
            // The next two cards peek out behind the front one, in their own colors.
            cards.drop(index + 1).take(2).forEachIndexed { i, a ->
                addView(View(context).apply { background = d.fill(Harness.of(a.kind).color, 32) },
                    FrameLayout.LayoutParams(d.px(120), -1, Gravity.END).apply { val inset = d.px(12 * (i + 1)); topMargin = inset; bottomMargin = inset; marginEnd = d.px(14 - 14 * i) })
            }
            if (front == null) addView(d.text("No agents here.", 22, R.color.dim, 600), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            else addView(card(front), FrameLayout.LayoutParams(d.px(404), -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d.px(18) })
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val asking = cards.count { it.state.needsYou && !it.stale }
            val working = cards.count { it.state == AgentState.WORKING }
            val idle = cards.count { it.state == AgentState.IDLE }
            val parts = listOfNotNull("$asking needs you".takeIf { asking > 0 }, "$working working", "$idle idle")
            addView(d.text(SpannableStringBuilder(parts.joinToString(" · ")).apply {
                if (asking > 0) setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, parts[0].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 18, R.color.dim), LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.text(if (cards.isEmpty()) "0 / 0" else "${index + 1} / ${cards.size}", 16, R.color.cream, 700, mono = true))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
        setContentView(root)
    }

    private fun chip(label: String, count: Int, on: Boolean, pick: () -> Unit) = d.text(SpannableStringBuilder("$label  $count").apply {
        if (!on) setSpan(ForegroundColorSpan(getColor(R.color.dim)), label.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }, 13, if (on) R.color.on_accent else R.color.cream, 600, mono = true).apply {
        background = d.fill(if (on) R.color.cream else R.color.raised, 999)
        gravity = Gravity.CENTER
        minHeight = d.px(36)
        setPadding(d.px(14), 0, d.px(14), 0)
        isClickable = true
        setOnClickListener { pick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = d.px(8) }
    }

    private fun card(a: Agent) = LinearLayout(this).apply {
        val h = Harness.of(a.kind)
        val ink = h.ink
        orientation = LinearLayout.VERTICAL
        background = d.fill(h.color, 32)
        setPadding(d.px(28), d.px(28), d.px(28), d.px(24))
        if (a.stale) alpha = .6f
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(d.text(listOfNotNull(a.machineLabel.uppercase(), shortPath(a.cwd) ?: a.project.ifBlank { null }).joinToString(" · "), 13, ink, 600, mono = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply { setImageResource(h.logo); setColorFilter(getColor(ink)) }, LinearLayout.LayoutParams(d.px(42), d.px(42)).apply { marginStart = d.px(12) })
        })
        addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(d.text(who(a), 44, ink, 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; letterSpacing = -.03f })
        val doing = a.summary.ifBlank { a.title }
        if (doing.isNotBlank()) addView(d.text(doing, 20, ink, 500).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(6), 0, 0) })
        addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        insights[a.sessionId]?.let { i ->
            addView(LinearLayout(context).apply {
                i.model?.let { m -> addView(tag(m.uppercase(), ink, h.color, solid = true)) }
                i.subagents.count { it.running }.takeIf { it > 0 }?.let { addView(tag("$it ${if (it == 1) "SUBAGENT" else "SUBAGENTS"}", ink, h.color)) }
                i.tasks.count { it.running }.takeIf { it > 0 }?.let { addView(tag("$it ${if (it == 1) "TASK" else "TASKS"}", ink, h.color)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(16) })
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(d.text(if (a.stale) "Last known: ${a.state.label}" else a.state.label, 20, ink, 600), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_chevron_right)!!.mutate().apply { setTint(getColor(R.color.cream)) })
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(ink)) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(d.px(68), d.px(68)))
        })
        isClickable = true
        contentDescription = "${h.label} on ${a.machineLabel}. ${stateLine(a)}. Tap to open."
        setOnClickListener { open(if (a.pendingApprovalId != null && !a.stale) DecisionActivity::class.java else AgentActivity::class.java, a.sessionId) }
    }

    private fun tag(label: String, ink: Int, brand: Int, solid: Boolean = false) = d.text(label, 14, if (solid) brand else ink, 600, mono = true).apply {
        background = GradientDrawable().apply { cornerRadius = d.px(999).toFloat(); setColor(if (solid) getColor(ink) else 0x1F000000) }
        setPadding(d.px(12), d.px(7), d.px(12), d.px(7))
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = d.px(8) }
    }

    override fun onWheel(down: Boolean): Boolean {
        val n = herdr.state.deck.count { host == null || it.machineLabel == host }
        if (n == 0) return false
        index = (index + if (down) 1 else -1).mod(n)
        render()
        return true
    }
}
