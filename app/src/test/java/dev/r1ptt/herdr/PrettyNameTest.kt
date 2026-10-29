package dev.r1ptt.herdr

import dev.r1ptt.herdr.ui.pretty
import org.junit.Assert.assertEquals
import org.junit.Test

class PrettyNameTest {
    @Test fun `folder names read as capitalized words`() {
        assertEquals("Herdr OS", pretty("herdr-os"))
        assertEquals("Lab Claude", pretty("lab_claude"))
        assertEquals("Backend Api V2", pretty("backend.api  v2"))
        assertEquals("MyApp", pretty("myApp"))
        assertEquals("--", pretty("--"))
    }
}
