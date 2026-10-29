package dev.r1ptt.herdr.ui

import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.Machine
import java.time.LocalTime

/**
 * What the home screen shows, decided without Android: which sheep stands where, what the
 * companion says, the time of day, and when a big herd folds into one flock per host. Positions
 * are design pixels on the 480×640 panel.
 */
data class Pasture(
    val headline: String,
    val aside: String,
    val sheep: List<PlacedSheep>,
    val flocks: List<Flock>,
    val daylight: Daylight,
) {
    val folded get() = flocks.isNotEmpty()
}

data class PlacedSheep(val agent: Agent, val slot: Slot, val focused: Boolean)

/** [down] is the host's machine when the bridge can't reach it; its sheep are last known. */
data class Flock(val host: String, val agents: List<Agent>, val focused: Boolean, val down: Machine? = null) {
    val needsYou get() = agents.count { it.state.needsYou && !it.stale }
}

/** Where a sheep stands: its box's top-left and size, front to back. */
enum class Slot(val x: Int, val y: Int, val size: Int) {
    FRONT(165, 330, 150),
    LEFT(48, 236, 80),
    RIGHT(350, 290, 76),
    BACK(214, 176, 52),
    BACK_RIGHT(386, 236, 50),
    BACK_LEFT(118, 196, 50),
}

enum class Daylight {
    DAWN, DAY, DUSK, NIGHT;

    companion object {
        fun at(t: LocalTime): Daylight = when (t.hour) {
            in 5..8 -> DAWN
            in 9..17 -> DAY
            in 18..20 -> DUSK
            else -> NIGHT
        }
    }
}

object PastureModel {
    /** More sheep than this and the field gets crowded; the herd folds into flocks by host. */
    const val FIELD_LIMIT = 6

    /** A host that went quiet folds the herd too, so its last-known sheep stand apart from the live ones. */
    fun of(deck: List<Agent>, focused: String?, now: LocalTime, machines: List<Machine> = emptyList()): Pasture {
        val focus = deck.firstOrNull { it.sessionId == focused } ?: deck.firstOrNull()
        val down = machines.filter { !it.connected }
        val (headline, aside) = words(deck, down, machines.size)
        val daylight = Daylight.at(now)
        if (deck.size > FIELD_LIMIT || down.any { m -> deck.any { it.machineId == m.id } }) {
            val flocks = deck.groupBy { it.machineLabel }.map { (host, agents) ->
                Flock(host, agents, agents.any { it.sessionId == focus?.sessionId }, down.firstOrNull { it.id == agents[0].machineId })
            }.sortedWith(compareByDescending<Flock> { it.needsYou }.thenBy { it.down != null }.thenBy { it.host })
            return Pasture(headline, aside, emptyList(), flocks, daylight)
        }
        return Pasture(headline, aside, place(deck, focus), emptyList(), daylight)
    }

    /**
     * One host's flock opened as its own field. Past six sheep it shows the picked one and the next
     * five in wheel order, so turning the wheel walks the whole flock.
     */
    fun pen(flock: List<Agent>, focused: String?, now: LocalTime): Pasture {
        val i = flock.indexOfFirst { it.sessionId == focused }.coerceAtLeast(0)
        val window = List(minOf(FIELD_LIMIT, flock.size)) { flock[(i + it) % flock.size] }
        val (headline, aside) = words(flock, emptyList(), 1)
        return Pasture(headline, aside, place(window, window.firstOrNull()), emptyList(), Daylight.at(now))
    }

    private fun place(deck: List<Agent>, focus: Agent?): List<PlacedSheep> {
        val rest = deck.filter { it.sessionId != focus?.sessionId }
        return listOfNotNull(focus?.let { PlacedSheep(it, Slot.FRONT, true) }) + rest.zip(Slot.entries.drop(1)) { a, slot -> PlacedSheep(a, slot, false) }
    }

    private fun words(deck: List<Agent>, down: List<Machine>, hosts: Int): Pair<String, String> {
        val others = if (down.size < hosts) "Everyone else is fine." else "Their sheep are last known."
        val live = deck.filterNot { it.stale }
        val asking = live.filter { it.state.needsYou }
        val working = live.count { it.state == AgentState.WORKING }
        val restLine = if (working > 0) "The rest are grazing." else "The rest are resting."
        return when {
            deck.isEmpty() -> "The pasture is quiet." to "Nothing is running."
            asking.size == 1 -> "${Harness.of(asking[0].kind).label} is asking for you." to restLine
            asking.size > 1 -> "${asking.size} sheep need you." to restLine
            down.size == 1 -> "${down[0].label} went quiet." to others
            down.size > 1 -> "${down.size} hosts went quiet." to others
            working > 0 -> "$working at work." to "Nothing needs you."
            else -> "All quiet." to "Nothing needs you."
        }
    }
}
