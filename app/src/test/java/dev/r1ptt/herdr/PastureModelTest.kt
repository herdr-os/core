package dev.r1ptt.herdr

import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.ui.Daylight
import dev.r1ptt.herdr.ui.Harness
import dev.r1ptt.herdr.ui.PastureModel
import dev.r1ptt.herdr.ui.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PastureModelTest {
    private fun agent(id: String, kind: String, state: AgentState, host: String = "cerebro", stale: Boolean = false) =
        Agent(id, host, host, kind, null, "t", "p", null, state, state.priority, 1, "2026-10-29T09:00:00Z", stale, null, "")

    private val noon = LocalTime.NOON

    @Test fun `the focused sheep stands in front and the rest fill the field back to front`() {
        val deck = listOf(agent("a", "claude", AgentState.APPROVAL_REQUIRED), agent("b", "codex", AgentState.WORKING), agent("c", "pi", AgentState.IDLE))
        val p = PastureModel.of(deck, "b", noon)
        assertEquals(listOf("b" to Slot.FRONT, "a" to Slot.LEFT, "c" to Slot.RIGHT), p.sheep.map { it.agent.sessionId to it.slot })
        assertTrue(p.sheep.first().focused)
    }

    @Test fun `the companion names who is asking and how the rest are doing`() {
        val deck = listOf(agent("a", "claude", AgentState.APPROVAL_REQUIRED), agent("b", "codex", AgentState.WORKING))
        val p = PastureModel.of(deck, null, noon)
        assertEquals("Claude Code is asking for you.", p.headline)
        assertEquals("The rest are grazing.", p.aside)
        assertEquals("All quiet.", PastureModel.of(listOf(agent("c", "pi", AgentState.IDLE)), null, noon).headline)
        assertEquals("The pasture is quiet.", PastureModel.of(emptyList(), null, noon).headline)
    }

    @Test fun `a stale sheep never claims to need you`() {
        val p = PastureModel.of(listOf(agent("a", "claude", AgentState.APPROVAL_REQUIRED, stale = true)), null, noon)
        assertEquals("All quiet.", p.headline)
    }

    @Test fun `past six sheep the herd folds into one flock per host, neediest first`() {
        val deck = (1..5).map { agent("m$it", "codex", AgentState.IDLE, host = "mbp") } + listOf(agent("n1", "pi", AgentState.WORKING, host = "namek"), agent("c1", "claude", AgentState.BLOCKED, host = "cerebro"))
        val p = PastureModel.of(deck, "c1", noon)
        assertTrue(p.folded)
        assertEquals(listOf("cerebro", "mbp", "namek"), p.flocks.map { it.host })
        assertTrue(p.flocks.first().focused)
        assertTrue(p.sheep.isEmpty())
    }

    @Test fun `the sky follows the clock`() {
        assertEquals(Daylight.DAWN, Daylight.at(LocalTime.of(6, 30)))
        assertEquals(Daylight.DAY, Daylight.at(LocalTime.of(13, 0)))
        assertEquals(Daylight.DUSK, Daylight.at(LocalTime.of(19, 15)))
        assertEquals(Daylight.NIGHT, Daylight.at(LocalTime.of(23, 48)))
    }

    @Test fun `harness kinds map to brands, unknown tools get the plain sheep`() {
        assertEquals(Harness.CLAUDE, Harness.of("Claude"))
        assertEquals(Harness.OTHER, Harness.of("aider"))
    }

    @Test fun `an opened flock is its own field, and past six the wheel walks a window through it`() {
        val flock = (1..14).map { agent("m$it", "claude", AgentState.WORKING, host = "macbook") }
        val p = PastureModel.pen(flock, "m13", LocalTime.NOON)
        assertTrue("never folds", !p.folded)
        assertEquals(listOf("m13", "m14", "m1", "m2", "m3", "m4"), p.sheep.map { it.agent.sessionId })
        assertEquals(Slot.FRONT, p.sheep.first { it.agent.sessionId == "m13" }.slot)
        assertEquals(2, PastureModel.pen(flock.take(2), null, LocalTime.NOON).sheep.size)
    }
}
