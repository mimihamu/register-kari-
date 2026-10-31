package jp.co.tenposinfo.register.cd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137FormalWireNegotiatedReceiveTest {
    private fun formal(mode: String): String = JSONObject(
        CustomerDisplaySnapshot.initial().copy(
            sequence = 100L,
            mode = CustomerDisplayMode.ACCOUNTING,
            subtotalAmount = 500L,
            totalAmount = 500L,
            receivedAmount = 400L,
            shortageAmount = 100L,
        ).toJson(),
    ).apply {
        put("schemaVersion", "1.0")
        put("mode", mode)
        put("canonicalMode", mode)
    }.toString()

    @Test
    fun formalModesParseWithStrictMoneyFields() {
        for (mode in listOf("CART", "SUBTOTAL", "PAYMENT", "CASH_RECEIVED", "CHANGE", "COMPLETE", "STANDBY")) {
            val parsed = CustomerDisplaySnapshot.parse(formal(mode))
            assertEquals(mode, parsed.canonicalMode)
            assertEquals(500L, parsed.totalAmount)
            assertEquals(100L, parsed.shortageAmount)
        }
    }

    @Test
    fun formalWireRejectsMissingTotalInsteadOfShowingWrongZero() {
        val objectValue = JSONObject(formal("PAYMENT"))
        objectValue.remove("totalAmount")
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(objectValue.toString()) }.isFailure)
    }

    @Test
    fun oldNumericWireStillParsesWithoutHeader() {
        val root = JSONObject(formal("CART"))
        root.put("schemaVersion", 1)
        root.put("mode", "SALES")
        root.put("canonicalMode", "CART")
        val parsed = CustomerDisplaySnapshot.parse(root.toString())
        assertEquals(CustomerDisplayMode.SALES, parsed.mode)
        assertEquals(500L, parsed.totalAmount)
    }
}
