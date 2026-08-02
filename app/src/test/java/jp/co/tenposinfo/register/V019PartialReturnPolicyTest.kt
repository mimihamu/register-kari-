package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun refundPaymentAllocationPreservesTotal() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 1_001,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, 700),
                PaymentTotal(PaymentMethod.CARD.name, 300),
            ),
        )
        assertEquals(1_001L, result.sumOf { it.amount })
        assertEquals(700L, result[0].amount)
        assertEquals(301L, result[1].amount)
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
