package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val now = CustomerDisplaySnapshotCachePolicy.MAX_AGE_MS + 1_000L
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
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayModel.kt",
        ).readText()

        assertTrue(source.contains("root.optJSONObject(\"presentation\")"))
        assertTrue(source.contains("taxSymbol = item.optString(\"taxSymbol\")"))
        assertTrue(source.contains("CustomerDisplayPresentation.fromJsonObject"))
    }
}
