package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayIdentityEnvelopeTest {
    @Test
    fun identityFieldsSurviveJsonRoundTrip() {
        val snap = CustomerDisplaySnapshot.initial().copy(
            storeId = "store-1", registerTerminalId = "pos-1",
            displayDeviceId = "cd-1", totalAmount = 1500L,
        )
        val parsed = CustomerDisplaySnapshot.parse(snap.toJson())
        assertEquals("store-1", parsed.storeId)
        assertEquals("pos-1", parsed.registerTerminalId)
        assertEquals("cd-1", parsed.displayDeviceId)
        assertEquals(1500L, parsed.totalAmount)
    }

    @Test
    fun oldWireStillWorksWithoutIdentity() {
        val parsed = CustomerDisplaySnapshot.parse(
            """{"schemaVersion":1,"sequence":3,"mode":"STANDBY","orderItems":[]}""",
        )
        assertTrue(parsed.storeId == null)
        assertTrue(parsed.registerTerminalId == null)
        assertTrue(parsed.displayDeviceId == null)
    }
}
