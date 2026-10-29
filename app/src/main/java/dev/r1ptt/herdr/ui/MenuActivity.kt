package dev.r1ptt.herdr.ui

import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.r1ptt.R

/**
 * The pasture's menu as a full page: one large row per place to go. The wheel moves the highlight
 * and keeps it on screen; a tap opens a row. Anything waiting on you is counted in orange.
 */
class MenuActivity : HerdrScreen() {
    private val d by lazy { Design(this) }
    private var at = 0
    private var shownKey = ""
    private val rows = mutableListOf<View>()
    private var page: dev.r1ptt.ui.DeviceUi.Page? = null
    override val wheelSteps = true

    private class Item(val title: String, val sub: CharSequence, val go: () -> Unit)

    private fun items(): List<Item> {
        val s = herdr.state
        val open = s.deck.count { it.state.needsYou && !it.stale }
        val waiting = if (open > 0) SpannableStringBuilder("$open WAITING ON YOU").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else "NOTHING WAITING"
        val hosts = s.machines.size
        return listOf(
            Item("Inbox", waiting) { open(HerdActivityFeed::class.java) },
            Item("Briefing", "WHAT THE HERD DID") { open(BriefingActivity::class.java) },
            Item("All agents", "${s.deck.size} SHEEP · $hosts ${if (hosts == 1) "HOST" else "HOSTS"}") { open(HerdListActivity::class.java) },
            Item("New task", "SAY IT, PICK WHO TAKES IT") { open(DelegateActivity::class.java) },
            Item("Quick controls", "WI-FI · VOLUME · POWER") { startActivity(Intent(this, dev.r1ptt.QuickControlsActivity::class.java)) },
            Item("Settings", "HOSTS · VOICE · UPDATES") { open(HerdrSettingsActivity::class.java) },
            Item("robotOS home", "THE R1'S OTHER APPS") { startActivity(Intent(this, dev.r1ptt.HomeActivity::class.java)) },
        )
    }

    override fun render() {
        val list = items()
        val key = list.joinToString { "${it.title}${it.sub}" }
        if (key == shownKey) return highlight()
        shownKey = key
        val p = designPage(this, d, "Menu", null) { finish() }
        page = p
        rows.clear()
        list.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = d.px(72)
                setPadding(d.px(18), d.px(12), d.px(18), d.px(12))
                addView(d.text(item.title, 22, R.color.cream, 700))
                addView(d.text(item.sub, 13, R.color.dim, 500, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(4), 0, 0) })
                contentDescription = "${item.title}. ${item.sub}"
                isClickable = true
                setOnClickListener { at = i; item.go() }
            }
            rows += row
            p.body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d.px(4) })
        }
        p.root.addView(d.text(SpannableStringBuilder("WHEEL · MOVE     TAP · OPEN").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, 13, R.color.dim, 600, mono = true).apply { setPadding(d.px(26), d.px(8), d.px(26), d.px(20)) })
        setContentView(p.root)
        highlight()
    }

    /** Focus is a darker fill, never an outline. */
    private fun highlight() {
        rows.forEachIndexed { i, r -> r.background = if (i == at) d.fill(R.color.raised, 22) else null }
        val row = rows.getOrNull(at) ?: return
        page?.scroll?.post { page?.scroll?.smoothScrollTo(0, (row.top - d.px(60)).coerceAtLeast(0)) }
    }

    override fun onWheel(down: Boolean): Boolean {
        if (rows.isEmpty()) return false
        at = (at + if (down) 1 else -1).coerceIn(0, rows.size - 1)
        highlight()
        return true
    }
}
