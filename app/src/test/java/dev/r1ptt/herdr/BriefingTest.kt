package dev.r1ptt.herdr

import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.ui.Briefing
import dev.r1ptt.herdr.ui.Harness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class BriefingTest {
    private fun agent(id: String, kind: String, state: AgentState, stale: Boolean = false, name: String? = null) =
        Agent(id, "cerebro", "cerebro", kind, name, "t", "p", null, state, state.priority, 1, "2026-10-29T09:00:00Z", stale, null, "")

    @Test fun `the briefing counts what happened and names who is waiting`() {
        val deck = listOf(agent("a", "claude", AgentState.APPROVAL_REQUIRED), agent("b", "pi", AgentState.COMPLETED_UNSEEN),
            agent("c", "codex", AgentState.COMPLETED_UNSEEN), agent("d", "codex", AgentState.FAILED))
        val b = Briefing.of(deck, LocalTime.of(8, 0))
        assertEquals("Morning. Two done, one failed. Claude is waiting on you.", b.text)
        assertEquals("a", b.about?.sessionId)
        assertTrue(b.waiting)
        assertEquals(listOf(Harness.CLAUDE, Harness.CODEX, Harness.PI), b.harnesses.sorted())
    }

    @Test fun `with nobody waiting it points at a failure, and says nothing needs you`() {
        val b = Briefing.of(listOf(agent("a", "claude", AgentState.WORKING), agent("b", "codex", AgentState.FAILED, name = "linter")), LocalTime.of(19, 0))
        assertEquals("Evening. One failed, one working. Nothing needs you.", b.text)
        assertEquals("linter", Briefing.short(b.about!!))
        assertFalse(b.waiting)
    }

    @Test fun `stale cards are never counted or announced`() {
        val b = Briefing.of(listOf(agent("a", "claude", AgentState.APPROVAL_REQUIRED, stale = true)), LocalTime.NOON)
        assertEquals("Afternoon. I can't reach the herd right now.", b.text)
        assertEquals(null, b.about)
    }

    @Test fun `a wake briefs only when a card newly needs you, failed or finished`() {
        val now = listOf(agent("a", "claude", AgentState.APPROVAL_REQUIRED), agent("b", "pi", AgentState.WORKING))
        assertFalse(Briefing.changed(mapOf("a" to AgentState.APPROVAL_REQUIRED, "b" to AgentState.IDLE), now))
        assertTrue(Briefing.changed(mapOf("a" to AgentState.WORKING), now))
        assertEquals("All quiet." to "1 still working.", Briefing.asleep(listOf(agent("b", "pi", AgentState.WORKING))))
        assertEquals("Heads up." to "1 needs you.", Briefing.asleep(now))
    }
}
