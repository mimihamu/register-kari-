package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerDisplayScrollPolicyTest {
    private fun item(id: String, latest: Boolean = false) = CustomerDisplayOrderItem(
        productId = id,
        name = "商品$id",
        quantity = 1,
        unitPrice = 100,
        amount = 100,
        latest = latest,
        cancelled = false,
    )

    @Test
    fun emptyListHasNoTarget() {
        assertEquals(-1, CustomerDisplayScrollPolicy.targetIndex(emptyList()))
    }

    @Test
    fun latestChangedItemIsTargetEvenWhenItIsNotLast() {
        val items = listOf(item("1"), item("2", latest = true), item("3"), item("4"))
        assertEquals(1, CustomerDisplayScrollPolicy.targetIndex(items))
    }

    @Test
    fun lastItemIsTargetWhenLatestFlagIsMissing() {
        val items = listOf(item("1"), item("2"), item("3"))
        assertEquals(2, CustomerDisplayScrollPolicy.targetIndex(items))
    }
}
