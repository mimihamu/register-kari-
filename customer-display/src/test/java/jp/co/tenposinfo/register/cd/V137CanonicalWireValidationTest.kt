package jp.co.tenposinfo.register.cd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CanonicalWireValidationTest {
    private fun canonical() = JSONObject().apply {
        put("schemaVersion", "1.0")
        put("sequence", 41)
        put("mode", "CART")
        put("subtotalAmount", 500)
        put("totalAmount", 500)
        put("changeAmount", 0)
        put("remainingAmount", 500)
        put("itemCount", 1)
        put("latestLineId", "row-1")
        put("items", org.json.JSONArray().put(JSONObject().apply {
            put("lineId", "row-1")
            put("name", "商品")
            put("quantityScaled", 100)
            put("quantityScale", 2)
            put("unitPrice", 500)
            put("lineAmount", 500)
            put("isVoided", false)
        }))
    }

    @Test
    fun formalStringVersionCanonicalModeAndLineIdAreAccepted() {
        val decoded = CustomerDisplaySnapshot.parse(canonical().toString())
        assertEquals(1, decoded.schemaVersion)
        assertEquals(CustomerDisplayMode.SALES, decoded.mode)
        assertEquals("row-1", decoded.orderItems.single().lineId)
        assertTrue(decoded.orderItems.single().latest)
        assertEquals(500L, decoded.totalAmount)
        assertEquals(500L, decoded.shortageAmount)
    }

    @Test
    fun unknownMajorAndMissingMoneyCannotTurnIntoZeroTotal() {
        val badVersion = canonical().put("schemaVersion", "2.0")
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(badVersion.toString()) }.isFailure)
        val missingMoney = canonical().apply { remove("totalAmount") }
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(missingMoney.toString()) }.isFailure)
        val decimalMoney = canonical().put("totalAmount", 500.5)
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(decimalMoney.toString()) }.isFailure)
        val negativeChange = canonical().put("changeAmount", -1)
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(negativeChange.toString()) }.isFailure)
    }

    @Test
    fun unknownOptionalKeyAndLegacyPayloadRemainCompatible() {
        val optional = canonical().put("futureOptional", "ignored")
        assertEquals(500L, CustomerDisplaySnapshot.parse(optional.toString()).totalAmount)
        val legacy = """{"schemaVersion":1,"sequence":2,"mode":"SALES","orderItems":[]}"""
        assertEquals(CustomerDisplayMode.SALES, CustomerDisplaySnapshot.parse(legacy).mode)
    }

    @Test
    fun paymentObjectIsParsedWithoutCardDetails() {
        val wire = canonical().put("mode", "PAYMENT").put("payment", JSONObject().apply {
            put("receiptName", "現金")
            put("tenderedAmount", 700)
            put("appliedAmount", 500)
        })
        val parsed = CustomerDisplaySnapshot.parse(wire.toString())
        assertEquals(CustomerDisplayMode.ACCOUNTING, parsed.mode)
        assertEquals("現金", parsed.paymentMethod)
        assertEquals(700L, parsed.receivedAmount)
    }
}
