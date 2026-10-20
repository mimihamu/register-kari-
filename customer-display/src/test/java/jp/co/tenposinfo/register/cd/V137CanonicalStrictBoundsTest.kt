package jp.co.tenposinfo.register.cd

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class V137CanonicalStrictBoundsTest {
    private fun valid(): JSONObject = JSONObject(CustomerDisplaySnapshot.initial().copy(
        sequence = 9L,
        mode = CustomerDisplayMode.SALES,
        totalAmount = 600L,
        subtotalAmount = 600L,
        shortageAmount = 600L,
        numberOfProducts = 2,
        orderItems = listOf(
            CustomerDisplayOrderItem(
                productId = "drink", lineId = "line-1", name = "飲み物",
                quantity = 2, quantityHundredths = 200L, unitPrice = 300L,
                amount = 600L, latest = true, cancelled = false,
            ),
        ),
    ).toJson()).apply {
        put("schemaVersion", "1.0")
        put("mode", "CART")
    }

    private fun fails(root: JSONObject): Boolean =
        runCatching { CustomerDisplaySnapshot.parse(root.toString()) }.isFailure

    @Test
    fun exactFormalSalesEnvelopeAccepted() {
        val snap = CustomerDisplaySnapshot.parse(valid().toString())
        assertEquals(600L, snap.totalAmount)
        assertEquals("line-1", snap.orderItems.single().lineId)
    }

    @Test
    fun requiredCanonicalArrayAndItemCountCannotBeAbsent() {
        assertTrue(fails(valid().apply { remove("items") }))
        assertTrue(fails(valid().apply { remove("itemCount") }))
        assertTrue(fails(valid().put("itemCount", -1)))
        assertTrue(fails(valid().put("itemCount", 3.5)))
    }

    @Test
    fun duplicateOrBlankLineIdentifiersAreRejected() {
        val duplicate = valid()
        val original = duplicate.getJSONArray("items").getJSONObject(0)
        duplicate.getJSONArray("items").put(JSONObject(original.toString()))
        assertTrue(fails(duplicate))
        val blank = valid()
        blank.getJSONArray("items").getJSONObject(0).put("lineId", "")
        assertTrue(fails(blank))
    }

    @Test
    fun negativeAmountsCannotBeRenderedAsValidSale() {
        for (name in listOf("subtotalAmount", "totalAmount", "remainingAmount", "changeAmount", "receivedAmount")) {
            assertTrue("negative $name", fails(valid().put(name, -1)))
        }
        val negativeItem = valid()
        negativeItem.getJSONArray("items").getJSONObject(0).put("lineAmount", -5)
        assertTrue(fails(negativeItem))
    }

    @Test
    fun zeroOrNegativeQuantitiesAndPricesRejected() {
        val zero = valid()
        zero.getJSONArray("items").getJSONObject(0).put("quantityScaled", 0)
        zero.getJSONArray("items").getJSONObject(0).put("quantityHundredths", 0)
        assertTrue(fails(zero))
        val price = valid()
        price.getJSONArray("items").getJSONObject(0).put("unitPrice", -1)
        assertTrue(fails(price))
    }

    @Test
    fun legacyNumericWireStillAcceptsMissingOptionalCanonicalAliases() {
        val raw = JSONObject(valid().toString())
        raw.put("schemaVersion", 1)
        raw.remove("items")
        raw.remove("itemCount")
        assertEquals(600L, CustomerDisplaySnapshot.parse(raw.toString()).totalAmount)
    }
}
