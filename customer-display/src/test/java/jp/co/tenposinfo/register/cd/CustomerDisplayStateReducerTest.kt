package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDisplayStateReducerTest {
    private fun snapshot(
        sequence: Long,
        mode: CustomerDisplayMode = CustomerDisplayMode.SALES,
        serverInstanceId: String? = "server-a",
    ) = CustomerDisplaySnapshot(
        schemaVersion = CUSTOMER_DISPLAY_SCHEMA_VERSION,
        sequence = sequence,
        serverInstanceId = serverInstanceId,
        sentAtMillis = 1_000L + sequence,
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
    fun staleOrDuplicatedSnapshotFromSameServerIsIgnored() {
        val current = CustomerDisplayUiState(connected = true, snapshot = snapshot(20), statusMessage = "接続中")

        assertEquals(current, CustomerDisplayStateReducer.received(current, snapshot(20)))
        assertEquals(current, CustomerDisplayStateReducer.received(current, snapshot(19)))
    }

    @Test
    fun lowerSequenceFromNewRegisterProcessIsAccepted() {
        val current = CustomerDisplayUiState(
            connected = false,
            snapshot = snapshot(9_000, CustomerDisplayMode.COMPLETE, serverInstanceId = "server-before-restart"),
            statusMessage = "再接続中",
            lastError = "process restarted",
        )
        val afterRestart = snapshot(
            sequence = 2,
            mode = CustomerDisplayMode.STANDBY,
            serverInstanceId = "server-after-restart",
        )

        val updated = CustomerDisplayStateReducer.received(current, afterRestart)

        assertTrue(updated.connected)
        assertEquals(2L, updated.snapshot.sequence)
        assertEquals("server-after-restart", updated.snapshot.serverInstanceId)
        assertEquals(CustomerDisplayMode.STANDBY, updated.snapshot.mode)
        assertNull(updated.lastError)
    }

    @Test
    fun lowerSequenceFromFirstIdentifiedServerIsAcceptedAfterLegacyServer() {
        val legacy = CustomerDisplayUiState(
            connected = false,
            snapshot = snapshot(50_000, CustomerDisplayMode.COMPLETE, serverInstanceId = null),
            statusMessage = "再接続中",
        )
        val upgradedRegister = snapshot(
            sequence = 3,
            mode = CustomerDisplayMode.STANDBY,
            serverInstanceId = "identified-server",
        )

        val updated = CustomerDisplayStateReducer.received(legacy, upgradedRegister)

        assertEquals(3L, updated.snapshot.sequence)
        assertEquals("identified-server", updated.snapshot.serverInstanceId)
        assertEquals(CustomerDisplayMode.STANDBY, updated.snapshot.mode)
    }

    @Test
    fun legacySnapshotWithoutInstanceStillUsesSequenceProtection() {
        val current = CustomerDisplayUiState(
            connected = true,
            snapshot = snapshot(30, serverInstanceId = null),
            statusMessage = "接続中",
        )

        assertEquals(
            current,
            CustomerDisplayStateReducer.received(current, snapshot(29, serverInstanceId = null)),
        )
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
