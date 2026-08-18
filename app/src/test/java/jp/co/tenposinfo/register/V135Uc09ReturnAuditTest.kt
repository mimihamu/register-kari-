package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Uc09ReturnAuditTest {
    private fun line(
        originalQuantity: Int = 3,
        returnedQuantity: Int = 2,
    ) = ReturnableSaleLine(
        saleItemId = 10L,
        productId = "P-10",
        productName = "返品対象",
        unitPrice = 1_000L,
        taxCategory = TaxCategory.INCLUDED_10,
        originalQuantity = originalQuantity,
        originalDiscount = 0L,
        note = "",
        returnedQuantity = returnedQuantity,
        refundedDiscount = 0L,
    )

    @Test
    fun partialReturnRejectsQuantityBeyondRemaining() {
        val result = runCatching { line().toReturnItem(2) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("返品数量が残数を超えています") == true)
    }

    @Test
    fun splitTenderRefundNeverExceedsUnrefundedOriginalCapacity() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 500L,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, 600L),
                PaymentTotal(PaymentMethod.CARD.name, 400L),
            ),
            refundedPayments = listOf(PaymentTotal(PaymentMethod.CASH.name, 500L)),
        )

        assertEquals(500L, result.sumOf { it.amount })
        assertEquals(100L, result.single { it.method == PaymentMethod.CASH.name }.amount)
        assertEquals(400L, result.single { it.method == PaymentMethod.CARD.name }.amount)
    }

    @Test
    fun originalSaleReturnReReadsRemainingQuantityInsideWriteTransaction() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()

        assertTrue(source.contains("val lines = loadReturnableLines(this, originalSaleId)"))
        assertTrue(source.contains("PartialReturnPolicy.select(type, lines, requestedQuantities)"))
        assertTrue(source.contains("COALESCE(SUM(ri.return_quantity), 0)"))
        assertTrue(source.contains("claimOperationKey("))
        assertTrue(source.contains("bindOperationKey(operationKey, reversalId)"))
    }

    @Test
    fun returnReceiptShowsOriginalSaleAndNegativeQuantityAndAmount() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()

        assertTrue(source.contains("元売上No.${'$'}{data.originalSaleId}"))
        assertTrue(source.contains("\"-${'$'}{item.quantity} × ${'$'}{yen(item.unitPrice)}\""))
        assertTrue(source.contains("\"-${'$'}{yen(item.baseAmount)}\""))
        assertTrue(source.contains("返金合計"))
        assertTrue(source.contains("paymentLabel(payment.method)"))
    }

    @Test
    fun receiptlessReturnRouteRequiresReversalPermissionAndManagerApproval() {
        val hub = File("src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt").readText()
        val source = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()

        assertTrue(hub.contains("ManualReturnActivityV135::class.java"))
        assertTrue(hub.contains("RegisterPermission.REVERSAL in permissions"))
        assertTrue(source.contains("RegisterPermission.REVERSAL"))
        assertTrue(source.contains("managerNameForPin(managerPin)"))
        assertTrue(source.contains("quantity INTEGER NOT NULL CHECK(quantity < 0)"))
        assertTrue(source.contains("amount INTEGER NOT NULL CHECK(amount < 0)"))
    }

    @Test
    fun linkedReturnQueuesReceiptAndKeepsDriveEventSchedulingHook() {
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()

        assertTrue(activity.contains("AutomaticPrintScheduler.enqueueNow(appContext)"))
        assertTrue(activity.contains("DriveOutboxScheduler.enqueueNow(appContext)"))
        assertTrue(store.contains("OperationDocumentType.REVERSAL_RECEIPT"))
    }
}
