package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedOperationsTest {
    @Test
    fun partialReturnAllocatesDiscountAndFinalReturnTakesRemainder() {
        val first = ReturnLineRecord(
            saleItemId = 1,
            productId = "P1",
            productName = "商品",
            unitPrice = 100,
            taxCategory = TaxCategory.INCLUDED_10,
            originalQuantity = 3,
            originalDiscount = 10,
            note = "",
            returnedQuantity = 0,
            refundedDiscount = 0,
        ).toReturnItem(1)
        assertEquals(3, first.discountAmount)

        val final = ReturnLineRecord(
            saleItemId = 1,
            productId = "P1",
            productName = "商品",
            unitPrice = 100,
            taxCategory = TaxCategory.INCLUDED_10,
            originalQuantity = 3,
            originalDiscount = 10,
            note = "",
            returnedQuantity = 1,
            refundedDiscount = 3,
        ).toReturnItem(2)
        assertEquals(7, final.discountAmount)
        assertEquals(200 - 7, final.baseAmount)
    }

    @Test
    fun reversalReceiptContainsOriginalSaleAndTaxSymbol() {
        val item = CartItem(
            product = Product("P1", "弁当", 800, TaxCategory.EXCLUDED_8, 1),
            quantity = 1,
        )
        val text = OperationDocumentRenderer.renderReversal(
            ReversalDocumentData(
                reversalId = 9,
                originalSaleId = 3,
                type = ReversalType.RETURN,
                createdAt = 0,
                operatorName = "責任者",
                reason = "商品違い",
                items = listOf(item),
                taxSummary = TaxEngine.calculate(listOf(item)),
                refundPayments = listOf(PaymentTotal(PaymentMethod.CASH.name, 864)),
            ),
            ReceiptPaper.MM80,
        )
        assertTrue(text.contains("元売上No.3"))
        assertTrue(text.contains("外※"))
        assertTrue(text.contains("返金合計"))
    }

    @Test
    fun settlementReportIncludesOpeningCashAndVariance() {
        val text = OperationDocumentRenderer.renderSettlement(
            SettlementDocumentData(
                reportId = 1,
                businessDate = "2026-07-30",
                type = SettlementReportType.Z_SETTLEMENT,
                createdAt = 0,
                operatorName = "責任者",
                salesGross = 10_000,
                reversalGross = 1_000,
                netSales = 9_000,
                openingCash = 30_000,
                cashIn = 1_000,
                cashOut = 500,
                expectedCash = 35_000,
                actualCash = 34_990,
                variance = -10,
                transactionCount = 5,
                reversalCount = 1,
                pendingPrints = 0,
                heldTickets = 0,
                paymentTotals = listOf(PaymentTotal(PaymentMethod.CASH.name, 4_500)),
            ),
            ReceiptPaper.MM58,
        )
        assertTrue(text.contains("開始釣銭"))
        assertTrue(text.contains("現金過不足"))
        assertTrue(text.contains("Z精算票"))
    }
}
