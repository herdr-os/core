package dev.r1ptt.herdr

import dev.r1ptt.herdr.ui.Harness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTest {
    /** `herdr agent start --kind` in Herdr 0.9.5. */
    private val herdrKinds = "pi, claude, codex, gemini, cursor, devin, agy, cline, omp, mastracode, opencode, copilot, kimi, kiro, droid, amp, grok, hermes, kilo, qodercli, qwen, letta, maki, muse".split(", ")

    @Test fun `every agent Herdr can start has its own sheep`() {
        val missing = herdrKinds.filter { Harness.of(it) == Harness.OTHER }
        assertTrue("no harness for $missing", missing.isEmpty())
        assertEquals("one harness per kind", herdrKinds.size, herdrKinds.map(Harness::of).toSet().size)
    }

    @Test fun `an agent Herdr OS doesn't know yet is a plain sheep`() = assertEquals(Harness.OTHER, Harness.of("something-new"))
}
