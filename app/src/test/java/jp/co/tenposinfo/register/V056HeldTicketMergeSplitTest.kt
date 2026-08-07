package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V056HeldTicketMergeSplitTest {
    private fun item(
        id: String,
        quantity: Int,
        unitPrice: Long = 1_000L,
        discountAmount: Long = 0L,
        taxCategory: TaxCategory = TaxCategory.INCLUDED_10,
    ): CartItem = CartItem(
        product = Product(
            id = id,
            name = "商品$id",
            unitPrice = unitPrice,
            taxCategory = taxCategory,
            displayOrder = 1,
        ),
        quantity = quantity,
        unitPrice = unitPrice,
        discountAmount = discountAmount,
    )

    @Test
    fun mergeKeepsTargetLinesBeforeSourceLines() {
        val target = listOf(item("A", 1), item("B", 2))
        val source = listOf(item("C", 3))

        val merged = HeldTicketMergeSplitPolicy.mergeItems(target, source)

        assertEquals(listOf("A", "B", "C"), merged.map { it.product.id })
        assertEquals(6, merged.sumOf { it.quantity })
    }

    @Test
    fun splitCanMovePartOfQuantityWithoutLosingDiscount() {
        val original = listOf(
            item(id = "A", quantity = 3, unitPrice = 1_000L, discountAmount = 101L),
            item(id = "B", quantity = 2, unitPrice = 500L, discountAmount = 20L),
        )

        val plan = HeldTicketMergeSplitPolicy.splitItems(
            items = original,
            movedQuantities = mapOf(0 to 1, 1 to 2),
        )

        assertEquals(3, plan.movedItems.sumOf { it.quantity })
        assertEquals(2, plan.remainingItems.sumOf { it.quantity })
        assertEquals(33L, plan.movedItems.first { it.product.id == "A" }.discountAmount)
        assertEquals(68L, plan.remainingItems.first { it.product.id == "A" }.discountAmount)
        assertEquals(
            original.sumOf { it.discountAmount },
            plan.movedItems.sumOf { it.discountAmount } + plan.remainingItems.sumOf { it.discountAmount },
        )
        assertEquals(
            original.sumOf { it.baseAmount },
            plan.movedItems.sumOf { it.baseAmount } + plan.remainingItems.sumOf { it.baseAmount },
        )
    }

    @Test
    fun splitKeepsTaxSnapshotFieldsOnBothSides() {
        val dynamic = item(
            id = "DYNAMIC",
            quantity = 2,
            taxCategory = TaxCategory.EXCLUDED_10,
        ).let { row ->
            row.copy(
                product = row.product.copy(
                    taxKey = "FUTURE_12",
                    taxLabel = "12%外税",
                    taxSymbol = "外12",
                    taxRatePercent = 12,
                    taxIncluded = false,
                    taxable = true,
                ),
            )
        }

        val plan = HeldTicketMergeSplitPolicy.splitItems(
            items = listOf(dynamic),
            movedQuantities = mapOf(0 to 1),
        )

        val both = plan.remainingItems + plan.movedItems
        assertTrue(both.all { it.product.taxKey == "FUTURE_12" })
        assertTrue(both.all { it.product.taxRatePercent == 12 })
        assertTrue(both.all { it.product.taxSymbol == "外12" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitRejectsMovingEveryItemBecauseThatWouldDestroySourceTicket() {
        HeldTicketMergeSplitPolicy.splitItems(
            items = listOf(item("A", 2)),
            movedQuantities = mapOf(0 to 2),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitRejectsQuantityBeyondOriginalLine() {
        HeldTicketMergeSplitPolicy.splitItems(
            items = listOf(item("A", 2)),
            movedQuantities = mapOf(0 to 3),
        )
    }

    @Test
    fun splitNameIsUniqueAndBounded() {
        val first = HeldTicketSafetyPolicy.splitName("宴会A", emptyList())
        val second = HeldTicketSafetyPolicy.splitName("宴会A", listOf(first))

        assertEquals("宴会A-分割", first)
        assertEquals("宴会A-分割-2", second)
        assertTrue(second.length <= HeldTicketSafetyPolicy.MAX_NAME_LENGTH)
    }
}
