package dev.r1ptt.herdr

import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.Approval
import dev.r1ptt.herdr.model.HerdEvent
import dev.r1ptt.herdr.model.Machine
import dev.r1ptt.herdr.model.Snapshot

enum class Link(val label: String) { LIVE("Live"), RECONNECTING("Reconnecting"), OFFLINE("Offline"), UNPAIRED("Not paired") }

/** Something the user should be told once: a card that now needs them, failed or finished. */
data class Notice(val sessionId: String, val state: AgentState, val text: String)

/**
 * The deck as the device knows it. Snapshots are the truth; events only say "something changed"
 * and are deduplicated and generation-checked here, so a replayed or reordered stream never moves
 * a card twice or announces the same completion twice. Pure: no Android, no I/O.
 */
class HerdState {
    var link = Link.UNPAIRED
        private set
    var cursor: String? = null
        private set
    var syncedAt: String? = null
        private set
    var machines: List<Machine> = emptyList()
        private set
    private val agents = LinkedHashMap<String, Agent>()
    private val approvals = HashMap<String, Approval>()
    private var order: List<String> = emptyList()
    private val seen = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > SEEN_LIMIT
    }
    private val generations = HashMap<String, Int>()
    private var synced = false

    /** The card under review. While set, new snapshots never reorder the deck around it. */
    var held: String? = null
        set(value) {
            field = value
            if (value == null) order = sorted()
        }

    val deck: List<Agent> get() = order.mapNotNull(agents::get).map { if (link == Link.LIVE) it else it.copy(stale = true) }
    fun agent(sessionId: String): Agent? = deck.firstOrNull { it.sessionId == sessionId }
    fun approval(id: String?): Approval? = id?.let(approvals::get)
    val needsYou: Int get() = agents.values.count { it.state.needsYou }

    fun setLink(next: Link) { link = next }

    /** Replaces everything known with [s]; returns notices for cards whose state just changed. */
    fun apply(s: Snapshot): List<Notice> {
        val before = agents.toMap()
        agents.clear()
        s.agents.forEach { agents[it.sessionId] = it; generations.merge(it.sessionId, it.generation, ::maxOf) }
        approvals.clear()
        s.approvals.forEach { approvals[it.id] = it }
        machines = s.machines
        cursor = s.cursor
        syncedAt = s.syncedAt
        order = if (held == null) sorted() else order.filter(agents::containsKey) + agents.keys.filter { it !in order }
        // A first sync is a status report, not news: only transitions seen while running notify.
        if (!synced) { synced = true; return emptyList() }
        return s.agents.mapNotNull { a ->
            val was = before[a.sessionId]?.state
            if (was == a.state || !a.state.announces) null else Notice(a.sessionId, a.state, "${a.displayName} on ${a.machineLabel}: ${a.state.label}")
        }
    }

    /**
     * True when [e] is news that warrants a fresh snapshot: not seen before and not from an older
     * generation of its session. The bridge sends events in cursor order, so the last accepted one is the cursor.
     */
    fun accept(e: HerdEvent): Boolean {
        if (seen.containsKey(e.id)) return false
        seen[e.id] = Unit
        val known = e.sessionId?.let(generations::get)
        if (known != null && e.generation != null && e.generation < known) return false
        e.sessionId?.let { id -> e.generation?.let { generations.merge(id, it, ::maxOf) } }
        cursor = e.cursor
        return true
    }

    /** Forgets every card, cursor and dedupe record, as if never synced. */
    fun clear() {
        agents.clear(); approvals.clear(); order = emptyList(); machines = emptyList()
        seen.clear(); generations.clear(); cursor = null; syncedAt = null; synced = false; held = null
    }

    private fun sorted() = agents.values.sortedWith(compareByDescending<Agent> { it.priority }.thenBy { it.machineLabel }.thenBy { it.sessionId }).map { it.sessionId }

    private val AgentState.announces get() = this == AgentState.APPROVAL_REQUIRED || this == AgentState.BLOCKED || this == AgentState.FAILED || this == AgentState.COMPLETED_UNSEEN

    companion object {
        private const val SEEN_LIMIT = 1024
    }
}
