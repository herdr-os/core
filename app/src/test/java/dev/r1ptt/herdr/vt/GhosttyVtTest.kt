package dev.r1ptt.herdr.vt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The JNI glue against a real libghostty-vt, built for this computer by tools/build-ghostty-vt.sh. */
class GhosttyVtTest {
    companion object {
        private val host = listOf("libherdrvt.dylib", "libherdrvt.so").map { File("../out/herdrvt-host/$it") }.firstOrNull(File::exists)

        @BeforeClass @JvmStatic fun load() {
            assumeTrue("run tools/build-ghostty-vt.sh for the host library", host != null)
            System.load(host!!.absolutePath)
        }
    }

    private fun term(vararg chunks: String) = GhosttyVt(40, 6).apply { chunks.forEach { write(it.toByteArray()) } }

    @Test fun `text lands on the grid with colors and the cursor after it`() = term("hello\r\n\u001b[1;31mred\u001b[0m").use { vt ->
        val s = vt.snapshot()
        assertEquals(6, s.rows); assertEquals(40, s.cols)
        assertEquals("hello", s.line(0)); assertEquals("red", s.line(1))
        assertNotEquals(s.defaultFg, s.fg(1, 0))
        assertTrue(s.flags(1, 0) and VtScreen.BOLD != 0)
        assertEquals(1, s.cursorRow); assertEquals(3, s.cursorCol)
    }

    @Test fun `cursor movement and clears behave like a terminal`() = term("aaaa\r\nbbbb", "\u001b[2J\u001b[3;5HX").use { vt ->
        val s = vt.snapshot()
        assertEquals("", s.line(0)); assertEquals("", s.line(1))
        assertEquals("    X", s.line(2))
    }

    @Test fun `clipboard and hyperlink escapes never show up as text`() = term("\u001b]52;c;cGF3bmVk\u0007ok\u001b]8;;https://evil.example\u0007link\u001b]8;;\u0007").use { vt ->
        assertEquals("oklink", vt.snapshot().line(0))
    }

    @Test fun `a cell stacked with combining marks is replaced, not overflowed`() = term("e" + "́".repeat(40) + "!").use { vt ->
        val line = vt.snapshot().line(0)
        assertTrue(line, line.startsWith("�") || line.startsWith("e"))
        assertTrue(line.endsWith("!"))
    }

    @Test fun `writes after close are refused instead of crashing`() {
        val vt = term("x"); vt.close()
        assertFalse(runCatching { vt.write("y".toByteArray()) }.isSuccess)
    }

    @Test fun `reverse video keeps a white foreground distinct from no background`() = term("\u001b[7mhi\u001b[0m").use { vt ->
        val s = vt.snapshot()
        assertTrue(s.flags(0, 0) and VtScreen.INVERSE != 0)
        assertEquals(VtScreen.NONE, s.bg(0, 0))
        assertNotEquals("white must not read as no color", VtScreen.NONE, s.fg(0, 0))
    }
}
