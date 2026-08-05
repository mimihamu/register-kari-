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
        val savedAt = 1_000L
        assertTrue(
            CustomerDisplaySnapshotCachePolicy.isFresh(
                savedAt,
                savedAt + CustomerDisplaySnapshotCachePolicy.MAX_AGE_MS,
            ),
        )
        assertFalse(
            CustomerDisplaySnapshotCachePolicy.isFresh(
                savedAt,
                savedAt + CustomerDisplaySnapshotCachePolicy.MAX_AGE_MS + 1L,
            ),
        )
        assertFalse(CustomerDisplaySnapshotCachePolicy.isFresh(0L, savedAt))
        assertFalse(CustomerDisplaySnapshotCachePolicy.isFresh(savedAt + 1L, savedAt))
    }

    @Test
    fun optionalPresentationAndTaxFieldsRemainBackwardCompatible() {
        val sourceFile = sequenceOf(
            File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayModel.kt"),
            File("customer-display/src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayModel.kt"),
        ).firstOrNull { it.isFile }
            ?: error("CustomerDisplayModel.kt was not found from the test working directory")
        val source = sourceFile.readText()

        assertTrue(source.contains("root.optJSONObject(\"presentation\")"))
        assertTrue(source.contains("taxSymbol = item.optString(\"taxSymbol\")"))
        assertTrue(source.contains("CustomerDisplayPresentation.fromJsonObject"))
    }
}
