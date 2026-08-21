package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Uc10CancelAuditTest {
    private fun line(
        id: Long,
        quantity: Int,
        returnedQuantity: Int = 0,
    ) = ReturnableSaleLine(
        saleItemId = id,
        productId = "P-$id",
        productName = "取消対象$id",
        unitPrice = 1_000L,
        taxCategory = TaxCategory.INCLUDED_10,
        originalQuantity = quantity,
        originalDiscount = 0L,
        note = "",
        returnedQuantity = returnedQuantity,
        refundedDiscount = 0L,
    )

    @Test
    fun cancelAlwaysSelectsEveryUnreturnedUnitRegardlessOfRequestedQuantities() {
        val selected = PartialReturnPolicy.select(
            type = ReversalType.CANCEL,
            lines = listOf(line(10L, 2), line(11L, 3)),
            requestedQuantities = mapOf(10L to 1, 11L to 1),
        )

        assertEquals(2, selected.size)
        assertEquals(2, selected.single { it.first.saleItemId == 10L }.second.quantity)
        assertEquals(3, selected.single { it.first.saleItemId == 11L }.second.quantity)
    }

    @Test
    fun cancelIsRejectedAfterAnyPartialReturn() {
        val result = runCatching {
            PartialReturnPolicy.select(
                type = ReversalType.CANCEL,
                lines = listOf(line(10L, 3, returnedQuantity = 1)),
                requestedQuantities = emptyMap(),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("一部返品済みの売上は取消できません") == true)
    }

    @Test
    fun persistentCancelKeyIsStablePerOriginalSaleAndReturnKeyRemainsRequestSpecific() {
        val cancelA = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.CANCEL,
            originalSaleId = 123L,
            requestId = "request-A",
        )
        val cancelB = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.CANCEL,
            originalSaleId = 123L,
            requestId = "request-B",
        )
        val returnA = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.RETURN,
            originalSaleId = 123L,
            requestId = "request-A",
        )
        val returnB = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.RETURN,
            originalSaleId = 123L,
            requestId = "request-B",
        )

        assertEquals("CANCEL:123", cancelA)
        assertEquals(cancelA, cancelB)
        assertFalse(returnA == returnB)
    }

    @Test
    fun cancelWritePathIsPersistentAppendOnlyAndBindsDuplicateGuard() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()

        assertTrue(source.contains("OperationsIdempotencyPolicy.reversalRequestKey(type, originalSaleId, requestId)"))
        assertTrue(source.contains("claimOperationKey("))
        assertTrue(source.contains("bindOperationKey(operationKey, reversalId)"))
        assertTrue(source.contains("\"reversal_transactions\""))
        assertTrue(source.contains("\"reversal_items\""))
        assertTrue(source.contains("\"reversal_payments\""))
        assertFalse(
            Regex("(?i)DELETE\\s+FROM\\s+(sales|sale_items|sale_payments|reversal_transactions|reversal_items|reversal_payments)")
                .containsMatchIn(source),
        )
    }

    @Test
    fun cancelReceiptLinksOriginalSaleAndPrintsNegativeLines() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()

        assertTrue(source.contains("【取消レシート】"))
        assertTrue(source.contains("元売上No.${'$'}{data.originalSaleId}"))
        assertTrue(source.contains("\"-${'$'}{item.quantity} × ${'$'}{yen(item.unitPrice)}\""))
        assertTrue(source.contains("\"-${'$'}{yen(item.baseAmount)}\""))
        assertTrue(source.contains("返金合計"))
    }

    @Test
    fun cancelRequiresReversalPermissionManagerPinReasonAndOpenBusinessSession() {
        val coordinator = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()

        assertTrue(coordinator.contains("requireOperator(OperationsAction.REVERSAL)"))
        assertTrue(coordinator.contains("requireManagerName(managerPin)"))
        assertTrue(store.contains("require(reason.isNotBlank())"))
        assertTrue(store.contains("queryActiveSession(this) ?: error(\"営業開始後に返品・取消を実行してください\")"))
        assertTrue(store.contains("check(BusinessSessionTransitionPolicy.mayOperate(session.status))"))
    }

    @Test
    fun cancellationFeedsNetSalesTenderTotalsCashAndDownstreamQueues() {
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()

        assertTrue(store.contains("netSales = salesGross - reversalGross"))
        assertTrue(store.contains("paymentMap[method] = (paymentMap[method] ?: 0L) - cursor.getLong(1)"))
        assertTrue(store.contains("cashSalesAfterRefunds = paymentMap[PaymentMethod.CASH.name] ?: 0L"))
        assertTrue(activity.contains("AutomaticPrintScheduler.enqueueNow(appContext)"))
        assertTrue(activity.contains("DriveOutboxScheduler.enqueueNow(appContext)"))
    }
}
