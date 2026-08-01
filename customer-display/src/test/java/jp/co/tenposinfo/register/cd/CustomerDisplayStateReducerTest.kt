package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDisplayStateReducerTest {
    private fun snapshot(sequence: Long, mode: CustomerDisplayMode = CustomerDisplayMode.SALES) = CustomerDisplaySnapshot(
        schemaVersion = CUSTOMER_DISPLAY_SCHEMA_VERSION,
        sequence = sequence,
        mode = mode,
        transactionId = null,
        storeName = "テンポス食堂",
        numberOfProducts = 1,
        subtotalAmount = 550,
        totalAmount = 550,
        paymentMethod = null,
        receivedAmount = 0,
        shortageAmount = 0,
        changeAmount = 0,
        message = null,
        orderItems = emptyList(),
    )

    @Test
    fun newerSnapshotIsAccepted() {
        val current = CustomerDisplayUiState(snapshot = snapshot(10))
        val updated = CustomerDisplayStateReducer.received(current, snapshot(11, CustomerDisplayMode.COMPLETE))

        assertTrue(updated.connected)
        assertEquals(11L, updated.snapshot.sequence)
        assertEquals(CustomerDisplayMode.COMPLETE, updated.snapshot.mode)
        assertNull(updated.lastError)
    }

    @Test
    fun staleOrDuplicatedSnapshotIsIgnored() {
        val current = CustomerDisplayUiState(connected = true, snapshot = snapshot(20), statusMessage = "接続中")

        assertEquals(current, CustomerDisplayStateReducer.received(current, snapshot(20)))
        assertEquals(current, CustomerDisplayStateReducer.received(current, snapshot(19)))
    }

    @Test
    fun disconnectKeepsLastSnapshotButMarksAmountsAsUnavailable() {
        val current = CustomerDisplayUiState(connected = true, snapshot = snapshot(30), statusMessage = "接続中")
        val disconnected = CustomerDisplayStateReducer.disconnected(current, "LAN切断")

        assertFalse(disconnected.connected)
        assertEquals(30L, disconnected.snapshot.sequence)
        assertEquals("LAN切断", disconnected.lastError)
        assertEquals("再接続中", disconnected.statusMessage)
    }

    @Test
    fun websocketHandshakeMatchesServerImplementation() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            CustomerDisplayClientHandshake.accept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }
}
