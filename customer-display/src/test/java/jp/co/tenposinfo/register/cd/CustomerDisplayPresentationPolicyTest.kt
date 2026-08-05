package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDisplayPresentationPolicyTest {
    private fun item(index: Int, latest: Boolean = false, cancelled: Boolean = false) =
        CustomerDisplayOrderItem(
            productId = "P$index",
            name = "商品$index",
            quantity = 1,
            unitPrice = index.toLong(),
            amount = index.toLong(),
            latest = latest,
            cancelled = cancelled,
            taxSymbol = if (index % 2 == 0) "内" else "外",
        )

    @Test
    fun hidesCancelledRowsAndKeepsConfiguredMaximumAroundLatest() {
        val items = (1..12).map { item(it, latest = it == 10, cancelled = it == 3) }
        val result = CustomerDisplayPresentationPolicy.visibleItems(
            items,
            CustomerDisplayPresentation(
                maxVisibleRows = 5,
                showCancelledItems = false,
            ),
        )

        assertEquals(5, result.size)
        assertFalse(result.any { it.cancelled })
        assertTrue(result.any { it.latest })
        assertEquals("P10", result.last().productId)
    }

    @Test
    fun cacheFreshnessHasExplicitBoundary() {
        val now = 1_000_000L
        assertTrue(
            CustomerDisplaySnapshotCachePolicy.isFresh(
                now - CustomerDisplaySnapshotCachePolicy.MAX_AGE_MS,
                now,
            ),
        )
        assertFalse(
            CustomerDisplaySnapshotCachePolicy.isFresh(
                now - CustomerDisplaySnapshotCachePolicy.MAX_AGE_MS - 1L,
                now,
            ),
        )
        assertFalse(CustomerDisplaySnapshotCachePolicy.isFresh(0L, now))
    }

    @Test
    fun optionalPresentationAndTaxFieldsRemainBackwardCompatible() {
        val legacy = """
            {
              "schemaVersion":1,
              "sequence":1,
              "mode":"SALES",
              "storeName":"旧形式",
              "numberOfProducts":1,
              "subtotalAmount":100,
              "totalAmount":100,
              "receivedAmount":0,
              "shortageAmount":100,
              "changeAmount":0,
              "orderItems":[
                {
                  "productId":"P1",
                  "name":"旧商品",
                  "quantity":1,
                  "unitPrice":100,
                  "amount":100,
                  "latest":true,
                  "cancelled":false
                }
              ]
            }
        """.trimIndent()
        val snapshot = CustomerDisplaySnapshot.parse(legacy)

        assertEquals(CustomerDisplayPresentation(), snapshot.presentation)
        assertEquals("", snapshot.orderItems.single().taxSymbol)
    }
}
