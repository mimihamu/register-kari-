package jp.co.tenposinfo.register.cd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137SnapshotSequenceResyncTest {
    private fun snapshot(
        sequence: Long,
        sessionId: String = "session-a",
        messageType: CustomerDisplayMessageType = CustomerDisplayMessageType.STATE_UPDATE,
        serverInstanceId: String = "server-a",
    ) = CustomerDisplaySnapshot.initial().copy(
        sequence = sequence,
        sessionId = sessionId,
        messageType = messageType,
        serverInstanceId = serverInstanceId,
        totalAmount = sequence * 100L,
    )

    private fun decision(
        previous: CustomerDisplaySnapshot?,
        incoming: CustomerDisplaySnapshot,
        awaiting: Boolean = false,
    ) = CustomerDisplayDeliveryPolicy.decide(previous, incoming, awaiting)

    @Test
    fun firstMessageMustBeFullSnapshot() {
        assertEquals(CustomerDisplayDeliveryDecision.REQUEST_SNAPSHOT, decision(null, snapshot(5)))
        assertEquals(
            CustomerDisplayDeliveryDecision.APPLY,
            decision(null, snapshot(5, messageType = CustomerDisplayMessageType.SNAPSHOT)),
        )
    }

    @Test
    fun consecutiveUpdateIsAppliedButDuplicatesAndOlderUpdatesIgnored() {
        val previous = snapshot(10)
        assertEquals(CustomerDisplayDeliveryDecision.APPLY, decision(previous, snapshot(11)))
        assertEquals(CustomerDisplayDeliveryDecision.IGNORE, decision(previous, snapshot(10)))
        assertEquals(CustomerDisplayDeliveryDecision.IGNORE, decision(previous, snapshot(9)))
    }

    @Test
    fun missingSequenceStopsUpdatesAndRequiresFullSnapshot() {
        val previous = snapshot(10)
        assertEquals(CustomerDisplayDeliveryDecision.REQUEST_SNAPSHOT, decision(previous, snapshot(12)))
        assertEquals(CustomerDisplayDeliveryDecision.REQUEST_SNAPSHOT, decision(previous, snapshot(11), awaiting = true))
        assertEquals(
            CustomerDisplayDeliveryDecision.APPLY,
            decision(previous, snapshot(14, messageType = CustomerDisplayMessageType.SNAPSHOT), awaiting = true),
        )
    }

    @Test
    fun sessionSwitchAndServerRestartNeedSnapshotRatherThanGuessingTotals() {
        val old = snapshot(100)
        assertEquals(
            CustomerDisplayDeliveryDecision.REQUEST_SNAPSHOT,
            decision(old, snapshot(101, sessionId = "session-b")),
        )
        assertEquals(
            CustomerDisplayDeliveryDecision.REQUEST_SNAPSHOT,
            decision(old, snapshot(101, serverInstanceId = "server-b")),
        )
        assertEquals(
            CustomerDisplayDeliveryDecision.APPLY,
            decision(old, snapshot(1, sessionId = "session-b", messageType = CustomerDisplayMessageType.SNAPSHOT)),
        )
    }

    @Test
    fun outdatedSnapshotFromSameSessionIsIgnoredButEqualReconnectSnapshotAccepted() {
        val old = snapshot(100)
        assertEquals(
            CustomerDisplayDeliveryDecision.IGNORE,
            decision(old, snapshot(99, messageType = CustomerDisplayMessageType.SNAPSHOT)),
        )
        assertEquals(
            CustomerDisplayDeliveryDecision.APPLY,
            decision(old, snapshot(100, messageType = CustomerDisplayMessageType.SNAPSHOT)),
        )
    }

    @Test
    fun canonicalMessageFieldsRoundTripWithoutChangingSchemaMajor() {
        val sent = snapshot(55, sessionId = "session-55")
        val root = JSONObject(sent.toJson())
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("session-55", root.getString("sessionId"))
        assertEquals("STATE_UPDATE", root.getString("messageType"))
        val received = CustomerDisplaySnapshot.parse(sent.toJson())
        assertEquals(sent.sessionId, received.sessionId)
        assertEquals(sent.sequence, received.sequence)
        assertEquals(sent.messageType, received.messageType)
    }

    @Test
    fun legacyPayloadWithoutMessageTypeRemainsFullSnapshot() {
        val incoming = CustomerDisplaySnapshot.parse(
            """{"schemaVersion":1,"sequence":4,"mode":"SALES","orderItems":[] }""",
        )
        assertEquals(CustomerDisplayMessageType.SNAPSHOT, incoming.messageType)
        assertEquals(null, incoming.sessionId)
    }

    @Test
    fun unknownMandatoryMessageTypeDoesNotFallBackToMoneyDisplay() {
        val malformed = """{"schemaVersion":1,"sequence":4,"mode":"SALES","messageType":"UNKNOWN","totalAmount":5000}"""
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(malformed) }.isFailure)
    }

    @Test
    fun reducerAcceptsLowerSequenceAfterExplicitNewSessionSnapshot() {
        val old = CustomerDisplayUiState(
            connected = true,
            snapshot = snapshot(100),
            statusMessage = "接続中",
        )
        val newSession = snapshot(1, sessionId = "new-session", messageType = CustomerDisplayMessageType.SNAPSHOT)
        val result = CustomerDisplayStateReducer.received(old, newSession)
        assertTrue(result.connected)
        assertEquals("new-session", result.snapshot.sessionId)
        assertEquals(1L, result.snapshot.sequence)
        assertFalse(result.lastError != null)
    }
}
