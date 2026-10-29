package dev.r1ptt.herdr

import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.HerdEvent
import dev.r1ptt.herdr.model.Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdStateTest {
    private fun agent(id: String, state: AgentState, generation: Int = 1, machine: String = "m1", label: String = "MacBook") = JSONObject()
        .put("session_id", id).put("machine_id", machine).put("machine_label", label).put("agent", "claude").put("title", "t")
        .put("project", "p").put("state", state.wire).put("priority", state.priority).put("generation", generation).put("stale", false)

    private fun snap(cursor: String, vararg agents: JSONObject) = Snapshot.parse(JSONObject().put("cursor", cursor)
        .put("machines", JSONArray()).put("agents", JSONArray(agents.toList())).put("approvals", JSONArray()).put("synced_at", "now"))

    private fun event(id: String, session: String, generation: Int, type: String = "agent.completed") = HerdEvent.parse(JSONObject()
        .put("event_id", id).put("cursor", "c$id").put("type", type).put("session_id", session).put("generation", generation).put("payload", JSONObject()))

    @Test fun `bridge timestamps show in local time`() {
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Madrid"))
            assertEquals("22:05:41", dev.r1ptt.herdr.ui.clock("2026-10-28T21:05:41.120Z", seconds = true))
        } finally { java.util.TimeZone.setDefault(zone) }
    }

    @Test fun `deck is sorted by actionable state`() {
        val s = HerdState().apply { setLink(Link.LIVE) }
        s.apply(snap("c1", agent("a", AgentState.IDLE), agent("b", AgentState.APPROVAL_REQUIRED), agent("c", AgentState.WORKING), agent("d", AgentState.FAILED)))
        assertEquals(listOf("b", "d", "c", "a"), s.deck.map { it.sessionId })
    }

    @Test fun `duplicate completion events give one transition and one notice`() {
        val s = HerdState().apply { setLink(Link.LIVE) }
        s.apply(snap("c1", agent("a", AgentState.WORKING)))
        assertTrue(s.accept(event("e1", "a", 1)))
        assertFalse("replayed event is not news", s.accept(event("e1", "a", 1)))
        val first = s.apply(snap("c2", agent("a", AgentState.COMPLETED_UNSEEN)))
        val again = s.apply(snap("c2", agent("a", AgentState.COMPLETED_UNSEEN)))
        assertEquals(1, first.size)
        assertEquals(AgentState.COMPLETED_UNSEEN, first.single().state)
        assertTrue(again.isEmpty())
    }

    @Test fun `first sync reports status without announcing it`() {
        val s = HerdState()
        assertTrue(s.apply(snap("c1", agent("a", AgentState.BLOCKED))).isEmpty())
    }

    @Test fun `an agent that appears blocked after an empty deck is announced`() {
        val s = HerdState()
        s.apply(snap("c1"))
        assertEquals(AgentState.BLOCKED, s.apply(snap("c2", agent("a", AgentState.BLOCKED))).single().state)
    }

    @Test fun `events from an older generation are rejected`() {
        val s = HerdState()
        s.apply(snap("c1", agent("a", AgentState.WORKING, generation = 4)))
        assertFalse(s.accept(event("old", "a", 3)))
        assertTrue(s.accept(event("new", "a", 4)))
        assertEquals("cnew", s.cursor)
    }

    @Test fun `a held card keeps its place until released`() {
        val s = HerdState().apply { setLink(Link.LIVE) }
        s.apply(snap("c1", agent("a", AgentState.WORKING), agent("b", AgentState.IDLE)))
        s.held = "b"
        s.apply(snap("c2", agent("a", AgentState.WORKING), agent("b", AgentState.IDLE), agent("c", AgentState.APPROVAL_REQUIRED)))
        assertEquals(listOf("a", "b", "c"), s.deck.map { it.sessionId })
        s.held = null
        assertEquals(listOf("c", "a", "b"), s.deck.map { it.sessionId })
    }

    @Test fun `cards are stale unless the stream is live`() {
        val s = HerdState()
        s.apply(snap("c1", agent("a", AgentState.WORKING)))
        s.setLink(Link.RECONNECTING)
        assertTrue(s.deck.single().stale)
        s.setLink(Link.LIVE)
        assertFalse(s.deck.single().stale)
    }

    @Test fun `same pane id on two machines stays two cards`() {
        val s = HerdState()
        s.apply(snap("c1", agent("m1:p1", AgentState.IDLE, machine = "m1", label = "MacBook"), agent("m2:p1", AgentState.IDLE, machine = "m2", label = "Server")))
        assertEquals(setOf("MacBook", "Server"), s.deck.map { it.machineLabel }.toSet())
    }
}
