package jp.co.tenposinfo.register

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class V136CustomerDisplayScaledWireTest {
    @Test
    fun fractionalQuantitySurvivesPosSnapshotSerialization() {
        val product = Product(
            id = "weight",
            name = "量り売り",
            unitPrice = 200L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
            quantityMode = QuantityMode.DECIMAL,
        )
        val item = CartItem(product = product, quantity = 1, quantityHundredths = 50L)
        val snapshot = CustomerDisplaySnapshotFactory.sales(listOf(item), "つぐレジ")
        val wire = JSONObject(snapshot.toJson()).getJSONArray("orderItems").getJSONObject(0)

        assertEquals(50L, wire.getLong("quantityScaled"))
        assertEquals(2, wire.getInt("quantityScale"))
        assertEquals(50L, wire.getLong("quantityHundredths"))
        assertEquals("0.5", wire.getString("quantityText"))
        assertEquals(wire.getLong("amount"), wire.getLong("lineAmount"))
        val canonical = JSONObject(snapshot.toJson()).getJSONArray("items").getJSONObject(0)
        assertEquals(50L, canonical.getLong("quantityScaled"))
        assertEquals(100L, canonical.getLong("lineAmount"))
        assertEquals(false, canonical.getBoolean("isVoided"))
        assertEquals(snapshot.numberOfProducts, JSONObject(snapshot.toJson()).getInt("itemCount"))
    }
}
