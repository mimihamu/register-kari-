package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class V019PartialReturnPolicyTest {
    private fun line(
        quantity: Int = 3,
        discount: Long = 10,
        returned: Int = 0,
        refundedDiscount: Long = 0,
        rate: Int = 12,
        included: Boolean = false,
    ) = ReturnableSaleLine(
        saleItemId = 1,
        productId = "P1",
        productName = "任意税率商品",
        unitPrice = 100,
        taxCategory = TaxCategory.EXCLUDED_10,
        taxKey = "CUSTOM_12_OUT",
        taxLabel = "12%外税",
        taxRatePercent = rate,
        taxIncluded = included,
        taxable = true,
        reduced = false,
        taxSymbol = "外",
        originalQuantity = quantity,
        originalDiscount = discount,
        note = "",
        returnedQuantity = returned,
        refundedDiscount = refundedDiscount,
    )

    @Test
    fun partialDiscountUsesRemainderOnFinalReturn() {
        assertEquals(3L, line().toReturnItem(1).discountAmount)
        assertEquals(7L, line(returned = 1, refundedDiscount = 3).toReturnItem(2).discountAmount)
    }

    @Test
    fun repeatedPartialDiscountsReturnOriginalDiscountExactly() {
        val first = line(quantity = 3, discount = 101).toReturnItem(1).discountAmount
        val second = line(quantity = 3, discount = 101, returned = 1, refundedDiscount = first)
            .toReturnItem(1).discountAmount
        val final = line(
            quantity = 3,
            discount = 101,
            returned = 2,
            refundedDiscount = first + second,
        ).toReturnItem(1).discountAmount
        assertEquals(101L, first + second + final)
    }

    @Test
    fun cancelRejectsSaleThatWasPartiallyReturned() {
        val failure = runCatching {
            PartialReturnPolicy.select(ReversalType.CANCEL, listOf(line(returned = 1, refundedDiscount = 3)), emptyMap())
        }
        assertTrue(failure.isFailure)
        assertEquals("一部返品済みの売上は取消できません", failure.exceptionOrNull()?.message)
    }

    @Test
    fun multipleReturnRequestsHaveDifferentPersistentKeysButCancelIsStable() {
        val first = PartialReturnPolicy.operationKey(ReversalType.RETURN, 10, "A")
        val second = PartialReturnPolicy.operationKey(ReversalType.RETURN, 10, "B")
        assertFalse(first == second)
        assertEquals("CANCEL:10", PartialReturnPolicy.operationKey(ReversalType.CANCEL, 10, "ignored"))
    }

    @Test
    fun refundPaymentAllocationPreservesExactOriginalTenderTotal() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 1_000,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, 700),
                PaymentTotal(PaymentMethod.CARD.name, 300),
            ),
        )
        assertEquals(1_000L, result.sumOf { it.amount })
        assertEquals(700L, result.first { it.method == PaymentMethod.CASH.name }.amount)
        assertEquals(300L, result.first { it.method == PaymentMethod.CARD.name }.amount)
    }

    @Test
    fun refundPaymentAllocationRejectsKnownTenderOverRefund() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PartialReturnPolicy.allocateRefundPayments(
                refundTotal = 1_001,
                originalPayments = listOf(
                    PaymentTotal(PaymentMethod.CASH.name, 700),
                    PaymentTotal(PaymentMethod.CARD.name, 300),
                ),
            )
        }
        assertEquals("返金額が元支払の未返金残高を超えています", error.message)
    }

    @Test
    fun repeatedSplitTenderRefundUsesOnlyRemainingMethodCapacity() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 1,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, 1),
                PaymentTotal(PaymentMethod.CARD.name, 1),
            ),
            refundedPayments = listOf(PaymentTotal(PaymentMethod.CASH.name, 1)),
        )
        assertEquals(listOf(PaymentTotal(PaymentMethod.CARD.name, 1)), result)
    }

    @Test
    fun duplicateOriginalTenderRowsAreAggregatedBeforeCapacityCheck() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 100,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, 60),
                PaymentTotal(PaymentMethod.CASH.name, 40),
            ),
        )
        assertEquals(listOf(PaymentTotal(PaymentMethod.CASH.name, 100)), result)
    }

    @Test
    fun missingOriginalPaymentBreakdownKeepsFallbackBehavior() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 500,
            originalPayments = emptyList(),
            fallbackMethod = "OTHER",
            refundedPayments = listOf(PaymentTotal("OTHER", 999)),
        )
        assertEquals(listOf(PaymentTotal("OTHER", 500)), result)
    }

    @Test
    fun allocationDoesNotOverflowLongMultiplication() {
        val nearMax = Long.MAX_VALUE / 2
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = nearMax,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, nearMax),
                PaymentTotal(PaymentMethod.CARD.name, nearMax),
            ),
        )
        assertEquals(nearMax, result.sumOf { it.amount })
        assertTrue(result.all { it.amount >= 0L })
    }

    @Test
    fun soldTaxSnapshotIsUsedForReturnCalculation() {
        val item = line(quantity = 1, discount = 0, rate = 12, included = false).toReturnItem(1)
        val summary = TaxEngine.calculate(listOf(item))
        assertEquals(112L, summary.grossAmount)
        assertEquals(12L, summary.taxAmount)
        assertEquals(12, item.product.taxRatePercent)
        assertFalse(item.product.taxIncluded)
    }

    @Test
    fun reversalDocumentUsesConfiguredIssuerWithoutFakeRegistration() {
        val item = line(quantity = 1, discount = 0, rate = 8, included = false).toReturnItem(1)
        val text = OperationDocumentRenderer.renderReversal(
            ReversalDocumentData(
                reversalId = 1,
                originalSaleId = 2,
                type = ReversalType.RETURN,
                createdAt = 0,
                operatorName = "担当A（承認:責任者B）",
                reason = "商品違い",
                items = listOf(item),
                taxSummary = TaxEngine.calculate(listOf(item)),
                refundPayments = listOf(PaymentTotal(PaymentMethod.CASH.name, 108)),
                issuer = InvoiceIssuerProfile(storeName = "つぐ食堂", address = "東京都", phone = "03-0000-0000"),
            ),
            ReceiptPaper.MM58,
        )
        assertTrue(text.contains("つぐ食堂"))
        assertTrue(text.contains("東京都"))
        assertFalse(text.contains("T1234567890123"))
    }
}
