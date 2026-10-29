package dev.r1ptt.herdr.ui

import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import java.time.LocalTime

/**
 * What the companion says about the herd, decided without Android: the briefing after a wake
 * ("Morning. Two done, one failed. Claude is waiting on you.") and the asleep screen's line.
 * Built only from the deck the bridge reported; every count is a count of real cards.
 */
data class Briefing(
    val lead: String,
    val point: String,
    /** The agent "Show me" opens: the first one waiting on you, else the first that failed or finished. */
    val about: Agent?,
    val harnesses: List<Harness>,
) {
    val text get() = "$lead $point".trim()
    val waiting get() = about?.state?.needsYou == true

    companion object {
        fun of(deck: List<Agent>, now: LocalTime): Briefing {
            val live = deck.filterNot { it.stale }
            val asking = live.filter { it.state.needsYou }
            val failed = live.filter { it.state == AgentState.FAILED }
            val done = live.filter { it.state == AgentState.COMPLETED_UNSEEN }
            val working = live.count { it.state == AgentState.WORKING }
            val greeting = when (now.hour) { in 5..11 -> "Morning."; in 12..17 -> "Afternoon."; else -> "Evening." }
            val counts = listOfNotNull(
                done.size.takeIf { it > 0 }?.let { "${count(it)} done" },
                failed.size.takeIf { it > 0 }?.let { "${count(it)} failed" },
                working.takeIf { it > 0 }?.let { "${count(it)} working" },
            ).joinToString(", ").replaceFirstChar { it.uppercase() }.let { if (it.isEmpty()) "" else "$it." }
            val point = when {
                asking.size == 1 -> "${short(asking[0])} is waiting on you."
                asking.size > 1 -> "${short(asking[0])} and ${count(asking.size - 1)} more are waiting on you."
                deck.isEmpty() -> "The pasture is empty."
                live.isEmpty() -> "I can't reach the herd right now."
                else -> "Nothing needs you."
            }
            val about = asking.firstOrNull() ?: failed.firstOrNull() ?: done.firstOrNull()
            val harnesses = (asking + failed + done).map { Harness.of(it.kind) }.distinct()
            return Briefing(listOf(greeting, counts).filter(String::isNotEmpty).joinToString(" "), point, about, harnesses)
        }

        /** The asleep screen: "All quiet. 3 still working." or who needs you, and nothing more. */
        fun asleep(deck: List<Agent>): Pair<String, String> {
            val live = deck.filterNot { it.stale }
            val asking = live.count { it.state.needsYou }
            val working = live.count { it.state == AgentState.WORKING }
            return when {
                asking > 0 -> "Heads up." to "$asking ${if (asking == 1) "needs" else "need"} you."
                working > 0 -> "All quiet." to "$working still working."
                else -> "All quiet." to "The herd is resting."
            }
        }

        /** True when a card now needs you, failed or finished and did not before: worth a briefing. */
        fun changed(before: Map<String, AgentState>, deck: List<Agent>): Boolean =
            deck.any { !it.stale && it.state in NEWS && before[it.sessionId] != it.state }

        /** "Claude" for an unnamed Claude Code, else the name Herdr gave it. */
        fun short(a: Agent): String = a.name?.takeIf { it.isNotBlank() && !it.equals(a.kind, true) } ?: Harness.of(a.kind).label.substringBefore(' ')

        private val NEWS = setOf(AgentState.APPROVAL_REQUIRED, AgentState.BLOCKED, AgentState.FAILED, AgentState.COMPLETED_UNSEEN)
        private val WORDS = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        private fun count(n: Int) = WORDS.getOrNull(n) ?: "$n"
    }
}
