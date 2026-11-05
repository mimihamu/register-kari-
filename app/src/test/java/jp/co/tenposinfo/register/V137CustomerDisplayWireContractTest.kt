package jp.co.tenposinfo.register

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class V137CustomerDisplayWireContractTest {
    private fun item(lineId: String) = CartItem(
        product = Product(
            id = "same",
            name = "同一商品",
            unitPrice = 500L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        ),
        quantity = 1,
        lineId = lineId,
    )

    @Test
    fun sameProductOnTwoCartLinesRetainsDistinctLineIdentifiers() {
        val snapshot = CustomerDisplaySnapshotFactory.sales(
            listOf(item("cart-row-a"), item("cart-row-b")),
            "つぐレジ",
            latestProductId = "same",
        )
        val root = JSONObject(snapshot.toJson())
        val rows = root.getJSONArray("items")
        assertEquals("cart-row-a", rows.getJSONObject(0).getString("lineId"))
        assertEquals("cart-row-b", rows.getJSONObject(1).getString("lineId"))
        assertNotEquals(rows.getJSONObject(0).getString("lineId"), rows.getJSONObject(1).getString("lineId"))
        assertEquals(false, rows.getJSONObject(0).getBoolean("latest"))
        assertEquals(true, rows.getJSONObject(1).getBoolean("latest"))
        assertEquals("cart-row-b", root.getString("latestLineId"))
        assertEquals(root.getJSONArray("orderItems").getJSONObject(0).getString("lineId"), rows.getJSONObject(0).getString("lineId"))
    }

    @Test
    fun canonicalPaymentAndRemainingAreAdditiveToLegacyWire() {
        val json = JSONObject(CustomerDisplaySnapshot(
            mode = CustomerDisplayMode.ACCOUNTING,
            storeName = "つぐレジ",
            totalAmount = 1430L,
            shortageAmount = 430L,
            receivedAmount = 1000L,
            paymentMethod = "現金",
            sentAtMillis = 1_700_000_000_000L,
        ).toJson())
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(430L, json.getLong("remainingAmount"))
        assertEquals(430L, json.getLong("shortageAmount"))
        assertEquals("現金", json.getJSONObject("payment").getString("receiptName"))
        assertEquals(1000L, json.getJSONObject("payment").getLong("tenderedAmount"))
        assertEquals(1000L, json.getJSONObject("payment").getLong("appliedAmount"))
        assertEquals("2023-11-14T22:13:20Z", json.getString("sentAt"))
    }
}
