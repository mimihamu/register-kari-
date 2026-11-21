package jp.co.tenposinfo.register

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayWireNegotiationTest {
    private val snapshot = CustomerDisplaySnapshotFactory.sales(
        items = listOf(CartItem(
            product = Product(
                id = "drink",
                name = "飲料",
                unitPrice = 350L,
                taxCategory = TaxCategory.INCLUDED_10,
                displayOrder = 1,
            ),
            quantity = 2,
            lineId = "sale-line-1",
        )),
        storeName = "店",
        latestLineId = "sale-line-1",
    ).copy(sequence = 7, sessionId = "session-1")

    @Test
    fun oldPeerReceivesUnchangedNumericWire() {
        val original = snapshot.toJson()
        assertFalse(CustomerDisplayWireNegotiation.wantsCanonical(null))
        assertFalse(CustomerDisplayWireNegotiation.wantsCanonical("2.0"))
        val projected = CustomerDisplayWireNegotiation.payload(original, false)
        assertEquals(original, projected)
        val root = JSONObject(projected)
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("SALES", root.getString("mode"))
    }

    @Test
    fun newPeerGetsFormalSchemaAndModeWithoutLosingLegacyAliases() {
        assertTrue(CustomerDisplayWireNegotiation.wantsCanonical("1.0"))
        val canonical = JSONObject(CustomerDisplayWireNegotiation.payload(snapshot.toJson(), true))
        assertEquals("1.0", canonical.getString("schemaVersion"))
        assertEquals("CART", canonical.getString("mode"))
        assertEquals("CART", canonical.getString("canonicalMode"))
        assertEquals(7L, canonical.getLong("sequence"))
        assertEquals("session-1", canonical.getString("sessionId"))
        assertTrue(canonical.has("orderItems"))
        assertTrue(canonical.has("items"))
        assertEquals("sale-line-1", canonical.getString("latestLineId"))
        assertEquals(700L, canonical.getLong("totalAmount"))
        assertEquals(700L, canonical.getJSONArray("items").getJSONObject(0).getLong("lineAmount"))
    }

    @Test
    fun completionChangeAndPaymentModesAreAlsoNegotiated() {
        val templates = listOf(
            CustomerDisplaySnapshotFactory.standby("店") to "STANDBY",
            snapshot.copy(mode = CustomerDisplayMode.SUBTOTAL) to "SUBTOTAL",
            snapshot.copy(mode = CustomerDisplayMode.ACCOUNTING, paymentMethod = "現金", receivedAmount = 800L) to "CASH_RECEIVED",
            snapshot.copy(mode = CustomerDisplayMode.ACCOUNTING, paymentMethod = "電子決済", receivedAmount = 0L) to "PAYMENT",
            snapshot.copy(mode = CustomerDisplayMode.COMPLETE, changeAmount = 100L) to "CHANGE",
            snapshot.copy(mode = CustomerDisplayMode.COMPLETE, changeAmount = 0L) to "COMPLETE",
        )
        templates.forEach { (current, expected) ->
            val canon = JSONObject(CustomerDisplayWireNegotiation.payload(current.toJson(), true))
            assertEquals(expected, canon.getString("mode"))
            assertEquals("1.0", canon.getString("schemaVersion"))
        }
    }

    @Test
    fun unknownHeaderNeverUpgradesImplicitly() {
        listOf("", "1", "1.0-extra", "2.0", "1.0\r\n").forEach {
            assertFalse(CustomerDisplayWireNegotiation.wantsCanonical(it))
        }
    }
}
