package dev.r1ptt.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    @Test fun `reads what herdr-bridge enroll --qr prints`() {
        assertEquals(PairingLink("https://bridge.tail.ts.net:8443", "4821-0937"),
            PairingLink.parse("herdr://pair?u=https%3A%2F%2Fbridge.tail.ts.net%3A8443&c=4821-0937"))
    }

    @Test fun `refuses anything else`() {
        assertEquals(PairingLink("http://100.80.68.69:8787", "1-2", listOf("read", "act"), "2026-10-29T10:00:00.000Z"),
            PairingLink.parse("herdr://pair?u=http%3A%2F%2F100.80.68.69%3A8787&c=1-2&s=read%2Cact&x=2026-10-29T10%3A00%3A00.000Z"))
        assertNull(PairingLink.parse("https://example.com/?u=https%3A%2F%2Fx&c=1"))
        assertNull(PairingLink.parse("herdr://pair?u=javascript%3Aalert(1)&c=1"))
        assertNull(PairingLink.parse("herdr://pair?u=https%3A%2F%2Fx"))
        assertNull(PairingLink.parse("not a link at all"))
    }
}
