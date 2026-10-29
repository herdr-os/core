package dev.r1ptt.herdr.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.Link
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import java.time.LocalTime

/**
 * The pasture: every agent as a sheep in its harness's colors. The one in front is the one the
 * wheel has picked, the neediest first; if it is asking something, its question floats above it.
 * A tap opens it. Past six sheep the herd folds into one flock per host.
 */
class HerdActivity : HerdrScreen() {
    private val d by lazy { Design(this) }
    private var selected: String? = null
    private var shownKey = ""
    private var front: View? = null
    /** What the last render showed, so motion plays once on a change and never on a plain redraw. */
    private var lastStates = emptyMap<String, AgentState>()
    private val bounced = mutableSetOf<String>()
    private var wasFolded = true
    /** The host whose flock is open as its own field, or null for the whole herd. */
    private var pen: String? = null
    override val wheelSteps = true

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        home = java.lang.ref.WeakReference(this)
        SetupActivity.firstRun(this)
    }

    override fun onDestroy() {
        if (home.get() === this) { app.turns.detachTarget(this); home = java.lang.ref.WeakReference(null) }
        super.onDestroy()
    }

    override fun render() {
        val s = herdr.state
        if (s.link == Link.LIVE) lastKnown = false
        hidden.retainAll(s.machines.filter { !it.connected }.map { it.label }.toSet())
        val herd = s.deck.filter { it.machineLabel !in hidden }
        if (pen != null && herd.none { it.machineLabel == pen }) pen = null
        val deck = pen?.let { h -> herd.filter { it.machineLabel == h } } ?: herd
        if (selected !in deck.map { it.sessionId }) selected = deck.firstOrNull()?.sessionId
        val now = LocalTime.now()
        val battery = battery()
        val key = listOf(pen, s.link, s.syncedAt.takeIf { s.link != Link.LIVE }, selected, herdr.problem, herdr.paired, Daylight.at(now), s.machines, hidden, lastKnown, battery,
            deck.joinToString { "${it.sessionId}${it.state}${it.stale}${it.summary}${it.pendingApprovalId}" }).joinToString("|")
        if (key == shownKey) return
        shownKey = key

        val pasture = pen?.let { PastureModel.pen(deck, selected, now) } ?: PastureModel.of(deck, selected, now, s.machines)
        val root = FrameLayout(this)
        root.addView(FieldView(this, pasture.daylight, bare = !herdr.paired || deck.isEmpty() && s.link != Link.LIVE), FrameLayout.LayoutParams(-1, -1))
        var headline = deck.isNotEmpty()
        var status = true
        when {
            !herdr.paired -> unpaired(root)
            deck.isEmpty() && s.link == Link.LIVE -> noSheep(root)
            s.link != Link.LIVE && herdr.problem != null && !lastKnown -> { headline = false; cantReach(root, deck) }
            deck.isEmpty() -> return boot()
            battery != null && deck.none { it.state.needsYou && !it.stale } -> { headline = false; status = false; sleepy(root, battery) }
            pasture.folded -> flocks(root, pasture)
            else -> field(root, pasture)
        }
        if (status) root.addView(header(pasture, deck, headline), FrameLayout.LayoutParams(-1, -2).apply { setMargins(d.px(26), d.px(22), d.px(26), 0) })
        lastStates = deck.associate { it.sessionId to it.state }
        wasFolded = pasture.folded
        if (headline && (pasture.folded || pasture.sheep.isNotEmpty())) root.addView(hints(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d.px(26), 0, d.px(26), d.px(20)) })
        setContentView(root)
    }

    /** Hosts the user hid while they are down; they come back when the host does. */
    private val hidden = mutableSetOf<String>()
    /** The user chose to look at the last-known herd instead of the "can't reach" screen. */
    private var lastKnown = false
    private var batteryDismissed = false

    /** The charge in percent while it is low and the R1 is on battery and not yet waved off; else null. */
    private fun battery(): Int? {
        val b = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged) batteryDismissed = false
        val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return level.takeIf { !plugged && !batteryDismissed && it in 0..LOW_BATTERY }
    }

    private fun header(p: Pasture, deck: List<Agent>, showHeadline: Boolean) = LinearLayout(this).apply {
        val s = herdr.state
        if (pen != null) addView(ui.iconButton(R.drawable.ic_back, "All hosts") { leavePen() }, LinearLayout.LayoutParams(d.px(64), d.px(64)).apply { marginEnd = d.px(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hosts = s.machines.size
            val up = s.machines.count { it.connected }
            val status = when {
                pen != null -> "${pen!!.uppercase()} · ${deck.size} SHEEP"
                !herdr.paired -> "NOT PAIRED"
                s.link == Link.LIVE && up < hosts -> "$up OF $hosts HOSTS · ${deck.size} SHEEP"
                s.link == Link.LIVE -> "$hosts ${if (hosts == 1) "HOST" else "HOSTS"} · ${deck.size} SHEEP"
                else -> "${s.link.label.uppercase()}${s.syncedAt?.let { " · LAST SEEN ${clock(it)}" } ?: ""}"
            }
            addView(d.text(status, 13, if (s.link == Link.LIVE) R.color.ok else R.color.dim, 600, mono = true).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(ui.dot(if (s.link == Link.LIVE) R.color.ok else R.color.faint, 6), null, null, null)
                compoundDrawablePadding = d.px(8)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            when {
                !herdr.paired -> addView(d.text("The pasture is empty.", 44, R.color.fg, 700).apply { letterSpacing = -.02f; setLineSpacing(0f, .95f); setPadding(0, d.px(8), 0, 0) })
                deck.isEmpty() && s.link == Link.LIVE -> addView(d.text(p.headline, 28, R.color.fg, 700).apply { setPadding(0, d.px(8), 0, 0) })
                showHeadline -> addView(d.twoTone(p.headline, p.aside, 28).apply { setPadding(0, d.px(8), 0, 0) })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.iconButton(R.drawable.ic_more, "Menu") { open(MenuActivity::class.java) }, LinearLayout.LayoutParams(d.px(56), d.px(56)).apply { marginStart = d.px(8) })
    }

    private fun field(root: FrameLayout, p: Pasture) {
        // Back to front, so nearer sheep overlap farther ones.
        for (placed in p.sheep.sortedBy { it.slot.y + it.slot.size }) {
            if (placed.focused) focused(root, placed.agent) else grazing(root, placed)
        }
    }

    private fun sheepImage(a: Agent) = ImageView(this).apply {
        val h = Harness.of(a.kind)
        setImageResource(if (a.state == AgentState.IDLE || a.state == AgentState.DISCONNECTED) h.asleep else h.sheep)
        if (a.stale) alpha = .5f
    }

    /** A sheep that just finished closes its eyes; its z's fade in twice, then hold. */
    private fun dozeOff(root: FrameLayout, a: Agent, slot: Slot) {
        if (a.state != AgentState.IDLE || lastStates[a.sessionId] != AgentState.WORKING) return
        val z = d.text("z z", 14, R.color.dim, 700, mono = true).apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(z, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = d.px(slot.x + slot.size * 3 / 4); topMargin = d.px(slot.y - 6) })
        android.animation.ObjectAnimator.ofFloat(z, View.ALPHA, 0f, 1f, 0f, 1f).setDuration(600).start()
    }

    private fun grazing(root: FrameLayout, placed: PlacedSheep) {
        val a = placed.agent
        val slot = placed.slot
        dozeOff(root, a, slot)
        root.addView(sheepImage(a).apply {
            if (slot.size < 60) alpha = alpha * .8f
            contentDescription = "${Harness.of(a.kind).label} on ${a.machineLabel}, ${stateLine(a)}"
            setOnClickListener { selected = a.sessionId; render() }
        }, at(slot.x, slot.y, slot.size, slot.size))
        if (slot.size >= 70) {
            val chip = d.text("${Harness.of(a.kind).label.substringBefore(' ').uppercase()} · ${a.state.label.uppercase()}", 13, R.color.dim, 600, mono = true).apply {
                background = d.fill(R.color.field, 14)
                setPadding(d.px(9), d.px(5), d.px(9), d.px(5))
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            root.addView(chip, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = d.px((slot.x + slot.size / 2 - 60).coerceIn(12, 340)); topMargin = d.px(slot.y + slot.size + 2) })
        }
    }

    private fun focused(root: FrameLayout, a: Agent) {
        val h = Harness.of(a.kind)
        val slot = Slot.FRONT
        val asking = a.state.needsYou && !a.stale
        root.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(d.px(3), getColor(if (asking) R.color.accent else R.color.outline)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, at(slot.x + 5, slot.y + 112, 140, 40))
        val sheep = sheepImage(a).apply {
            contentDescription = "${h.label} on ${a.machineLabel}. ${stateLine(a)}. Tap to open."
            setOnClickListener { walkIn(root, this, a) }
        }
        root.addView(sheep, at(slot.x, slot.y, slot.size, slot.size))
        front = sheep
        dozeOff(root, a, slot)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(d.text(who(a), 22, R.color.fg, 700).apply { gravity = Gravity.CENTER })
            addView(d.text("${a.machineLabel.uppercase()} · ${stateLine(a).uppercase()}", 13, R.color.dim, 500, mono = true).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0)
            })
        }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = d.px(slot.y + slot.size + 6); setMargins(d.px(30), d.px(slot.y + slot.size + 6), d.px(30), 0) })
        if (!asking) return
        val bubble = askBubble(a, h)
        root.addView(bubble, FrameLayout.LayoutParams(d.px(270), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = d.px(640 - slot.y + 2) })
        // A new question: the sheep hops once and the bubble pops in with one bounce, then all is still.
        if (bounced.add(a.pendingApprovalId ?: a.sessionId)) {
            sheep.animate().translationY(-d.px(16).toFloat()).setDuration(160).withEndAction { sheep.animate().translationY(0f).setDuration(160).start() }.start()
            bubble.post {
                bubble.pivotX = bubble.width / 2f; bubble.pivotY = bubble.height.toFloat()
                bubble.scaleX = .85f; bubble.scaleY = .85f
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }
        }
    }

    private fun walkIn(root: FrameLayout, sheep: View, a: Agent) {
        for (i in 0 until root.childCount) root.getChildAt(i).takeIf { it !== sheep }?.animate()?.alpha(0f)?.setDuration(260)?.start()
        sheep.animate().translationX(-d.px(120).toFloat()).translationY(d.px(110).toFloat()).scaleX(1.3f).scaleY(1.3f).setDuration(260)
            .withEndAction { a.open(); @Suppress("DEPRECATION") overridePendingTransition(0, 0); shownKey = ""; root.postDelayed({ render() }, 400) }.start()
    }

    /** The question the sheep in front is asking, in its own color, pointing down at it. */
    private fun askBubble(a: Agent, h: Harness) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val question = herdr.state.approval(a.pendingApprovalId)?.question?.takeIf { it.isNotBlank() } ?: a.state.label
        addView(d.bubble(h.color, 16).apply {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(d.text("!", 13, R.color.on_accent, 800).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.accent)) }
                }, LinearLayout.LayoutParams(d.px(18), d.px(18)))
                addView(d.text("NEEDS YOU", 13, h.ink, 700, mono = true).apply { setPadding(d.px(7), 0, 0, 0) })
            })
            addView(d.text(question, 18, h.ink, 700).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0); setLineSpacing(0f, 1.05f) })
            isClickable = true
            setOnClickListener { a.open() }
            contentDescription = "${h.label} asks: $question"
        }, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { background = d.fill(h.color, 2); rotation = 45f }, LinearLayout.LayoutParams(d.px(16), d.px(16)).apply { topMargin = -d.px(9) })
    }

    private var herded = 0

    /** When the herd first folds into pens, the sheep walk in a few at a time. */
    private fun herdIn(v: View) {
        if (wasFolded) return
        val k = herded++
        v.alpha = 0f; v.translationX = -d.px(24).toFloat()
        v.animate().alpha(1f).translationX(0f).setStartDelay(30L * k).setDuration(400).start()
    }

    private fun flocks(root: FrameLayout, p: Pasture) {
        herded = 0
        val anyDown = p.flocks.any { it.down != null }
        // Host cards stand on their own; the field's edges would peek between them.
        if (anyDown) root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            p.flocks.forEach { f ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = d.fill(if (f.down != null) R.color.field else if (f.focused || anyDown) R.color.field_day else R.color.bg, 24)
                    setPadding(d.px(16), d.px(14), d.px(16), d.px(14))
                    addView(LinearLayout(context).apply {
                        addView(d.text(f.host.uppercase(), 14, if (f.focused || anyDown) R.color.fg else R.color.dim, 700, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                        val (note, tint) = when {
                            f.needsYou > 0 -> "${f.needsYou} NEEDS YOU" to R.color.accent
                            f.down != null -> "NO ANSWER${f.down.lastSeenAt?.let { " · ${quiet(it)}" } ?: ""}" to R.color.error
                            anyDown -> "LIVE" to R.color.ok
                            else -> "${f.agents.size} SHEEP" to R.color.dim
                        }
                        addView(d.text(note, 13, tint, 700, mono = true))
                    })
                    addView(LinearLayout(context).apply {
                        setPadding(0, d.px(8), 0, 0)
                        f.agents.take(7).forEach { a -> addView(sheepImage(a).also { herdIn(it) }, LinearLayout.LayoutParams(d.px(44), d.px(44)).apply { marginEnd = d.px(4) }) }
                    })
                    f.down?.let { m -> downNote(this, m, f.agents) }
                    if (f.down == null) {
                        isClickable = true
                        contentDescription = "${f.host}, ${f.agents.size} sheep. Tap to open."
                        setOnClickListener { pen = f.host; selected = (f.agents.firstOrNull { it.state.needsYou && !it.stale } ?: f.agents[0]).sessionId; render() }
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(12) })
            }
        }, FrameLayout.LayoutParams(-1, -2).apply { setMargins(d.px(22), d.px(if (anyDown) 132 else 130), d.px(22), 0) })
    }

    /** What is last known on a host that stopped answering, and the two things to do about it. */
    private fun downNote(card: LinearLayout, m: dev.r1ptt.herdr.model.Machine, agents: List<Agent>) {
        val a = agents.singleOrNull()
        val known = if (a != null) "Its ${Harness.of(a.kind).label} was ${a.state.label.lowercase()}${clock(a.stateSince).ifBlank { null }?.let { " at $it" } ?: ""}."
            else "Its ${agents.size} sheep were last seen${m.lastSeenAt?.let { " at ${clock(it)}" } ?: ""}."
        card.addView(d.text("$known That’s the last I know; ${if (a != null) "it" else "they"} may still be running.", 16, R.color.dim, 500).apply {
            setLineSpacing(0f, 1.15f); setPadding(0, d.px(10), 0, d.px(12))
        })
        card.addView(LinearLayout(this).apply {
            addView(d.pill("Try again", height = 56) { herdr.refresh() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d.px(16).toFloat()); setPadding(d.px(18), 0, d.px(18), 0) })
            addView(quietPill(d, "Hide ${m.label}") { hidden += m.label; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(8) })
        })
    }

    /** "6 MIN" since a host last answered. */
    private fun quiet(iso: String): String {
        val m = runCatching { java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).toMinutes() }.getOrNull() ?: return ""
        return when { m < 60 -> "$m MIN"; m < 48 * 60 -> "${m / 60} H"; else -> "${m / 1440} D" }
    }

    /** Not paired: an empty, dashed field and the companion with the one next step. */
    private fun unpaired(root: FrameLayout) {
        val revoked = herdr.problem
        root.addAtFoot(d, companionScene(this, d,
            if (revoked != null) "Your bridge let this R1 go." else "Bring your herd here.",
            if (revoked != null) "It was revoked, so I erased everything from your herd." else "Pair a computer that runs Herdr and its agents show up as sheep.",
            Step("Pair a host") { open(SetupActivity::class.java) }))
    }

    /** Live, but no agent runs anywhere: the companion alone on the field. */
    private fun noSheep(root: FrameLayout) {
        root.addView(ImageView(this).apply { setImageResource(R.drawable.sheep_plain); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, at(195, 230, 90, 90))
        root.addAtFoot(d, companionScene(this, d, null, boldTail(this, "Nothing’s running.", "Give the herd a job?"),
            Step("New task") { open(DelegateActivity::class.java) }, Step("Hosts") { open(HerdrSettingsActivity::class.java) }, withSheep = false))
    }

    /** The bridge stopped answering: the last-known herd faded at the top, the companion saying so below. */
    private fun cantReach(root: FrameLayout, deck: List<Agent>) {
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            alpha = .28f
            deck.take(3).forEachIndexed { i, a -> addView(sheepImage(a), LinearLayout.LayoutParams(d.px(if (i == 1) 84 else 64), d.px(if (i == 1) 84 else 64)).apply { marginStart = if (i > 0) d.px(18) else 0 }) }
        }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = d.px(130) })
        val since = herdr.state.syncedAt?.let { "This is from ${clock(it)}." } ?: "Nothing has synced yet."
        root.addAtFoot(d, companionScene(this, d, "I can’t reach your herd.", "The bridge isn’t answering. $since",
            Step("Try again") { herdr.refresh() }, if (deck.isNotEmpty()) Step("Show last known") { lastKnown = true; render() } else null))
    }

    /** Connecting with nothing cached yet: the boot screen, until the first snapshot lands. */
    private fun boot() {
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context).apply { setImageResource(R.drawable.sheep_plain); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(d.px(140), d.px(140)))
                addView(d.text(SpannableStringBuilder("HERDR OS").apply {
                    setSpan(ForegroundColorSpan(getColor(R.color.accent)), 6, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }, 22, R.color.fg, 700, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = .2f; contentDescription = "Herdr OS"; setPadding(0, d.px(22), 0, d.px(24)) })
                val bar = View(context).apply { background = d.fill(R.color.accent, 2) }
                addView(FrameLayout(context).apply {
                    background = d.fill(R.color.field, 2)
                    addView(bar, FrameLayout.LayoutParams(d.px(54), -1))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(d.px(120), d.px(3)))
                // No real progress to report, so the bar sweeps instead of filling.
                android.animation.ObjectAnimator.ofFloat(bar, View.TRANSLATION_X, 0f, d.px(66).toFloat()).apply {
                    duration = 900; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; start()
                }
                addView(d.text("WAKING THE HERD", 14, R.color.dim, 500, mono = true).apply { gravity = Gravity.CENTER; letterSpacing = .12f; setPadding(0, d.px(26), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { bottomMargin = d.px(60) })
        })
    }

    /** Low battery on the pasture: the charge, big, and the companion asking for the charger. */
    private fun sleepy(root: FrameLayout, level: Int) {
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text("$level%", 88, R.color.accent, 700).apply { letterSpacing = -.04f; includeFontPadding = false; contentDescription = "Battery $level percent" })
            addView(FrameLayout(context).apply {
                background = d.fill(R.color.field, 5)
                addView(View(context).apply { background = d.fill(R.color.accent, 5) }, FrameLayout.LayoutParams(d.px(428 * level / 100).coerceAtLeast(d.px(10)), -1))
            }, LinearLayout.LayoutParams(-1, d.px(10)).apply { topMargin = d.px(12) })
        }, FrameLayout.LayoutParams(-1, -2).apply { setMargins(d.px(26), d.px(26), d.px(26), 0) })
        val connected = herdr.connectedOnBattery
        root.addAtFoot(d, companionScene(this, d, "I’m getting sleepy.",
            if (connected) "Staying connected on battery drains me. Plug me in soon." else "Plug me in soon. I only sync while you look.",
            Step("OK") { batteryDismissed = true; render() },
            if (connected) Step("Save battery") { herdr.connectedOnBattery = false; batteryDismissed = true; render() } else null))
    }

    private fun hints() = LinearLayout(this).apply {
        addView(hint("WHEEL", "NEXT SHEEP"), LinearLayout.LayoutParams(0, -2, 1f))
        addView(hint("TAP", "OPEN"))
    }

    private fun hint(key: String, what: String) = d.text(SpannableStringBuilder("$key · $what").apply {
        setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, key.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }, 13, R.color.dim, 700, mono = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun at(x: Int, y: Int, w: Int, h: Int) = FrameLayout.LayoutParams(d.px(w), d.px(h)).apply { leftMargin = d.px(x); topMargin = d.px(y) }

    private fun Agent.open() = if (pendingApprovalId != null && !stale) open(DecisionActivity::class.java, sessionId) else open(AgentActivity::class.java, sessionId)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // This is the home screen: Back only closes an open flock.
        if (pen != null) leavePen()
    }

    private fun leavePen() { pen = null; wasFolded = true; render() }

    override fun onWheel(down: Boolean): Boolean {
        val deck = herdr.state.deck.filter { pen == null || it.machineLabel == pen }
        if (deck.size < 2) return deck.isNotEmpty()
        val i = deck.indexOfFirst { it.sessionId == selected }.coerceAtLeast(0)
        selected = deck[(i + if (down) 1 else -1).mod(deck.size)].sessionId
        render()
        // The new sheep steps forward. The system animator scale turns it off for reduced motion.
        front?.apply { translationY = d.px(if (down) 14 else -14).toFloat(); animate().translationY(0f).setDuration(180).start() }
        return true
    }

    companion object {
        private const val LOW_BATTERY = 15
        private var home = java.lang.ref.WeakReference<HerdActivity>(null)
        /** The home pasture while it exists; the push-to-talk target whenever no other Herdr screen is in front. */
        val live: HerdActivity? get() = home.get()?.takeUnless { it.isDestroyed }
    }
}
