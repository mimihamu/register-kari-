package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UC-11 cumulative acceptance gate.
 *
 * The v2.5 correction contract spans COR-001..004 for a working transaction and
 * COR-008..010 for a correction of an already confirmed sale. Confirmed sales are
 * never edited in place: RETURN/CANCEL opposite transactions carry the monetary
 * correction, refund, execution business day, audit and print trail.
 */
class V135Uc11CorrectionAuditTest {
    private fun returnableLine(
        id: Long,
        quantity: Int,
        returnedQuantity: Int = 0,
    ) = ReturnableSaleLine(
        saleItemId = id,
        productId = "P-$id",
        productName = "訂正対象$id",
        unitPrice = 1_000L,
        taxCategory = TaxCategory.INCLUDED_10,
        originalQuantity = quantity,
        originalDiscount = 0L,
        note = "",
        returnedQuantity = returnedQuantity,
        refundedDiscount = 0L,
    )

    @Test
    fun workingTransactionCorrectionKeepsCor001To004AsTheSingleImplementation() {
        val numeric = File("src/main/java/jp/co/tenposinfo/register/NumericCorrectionPolicyV135.kt").readText()
        val cart = File("src/main/java/jp/co/tenposinfo/register/CartCorrectionV135.kt").readText()
        val abort = File("src/main/java/jp/co/tenposinfo/register/TransactionAbortV135.kt").readText()

        assertTrue(numeric.contains("fun shouldClearInput(rawInput: String): Boolean = rawInput.isNotBlank()"))
        assertTrue(cart.contains("LAST_LINE"))
        assertTrue(cart.contains("SELECTED_LINE"))
        assertTrue(cart.contains("cart_correction_history"))
        assertTrue(cart.contains("put(\"line_id\", record.lineId)"))
        assertTrue(cart.contains("put(\"cancelled_quantity\", record.cancelledQuantity)"))

        val abortBody = abort.substringAfter("fun abort(items: List<CartItem>, reason: String)")
            .substringBefore("override fun close()")
        assertTrue(abortBody.contains("\"TRANSACTION_ABORT\""))
        assertTrue(abortBody.contains("put(\"operator_name\", operator.name)"))
        assertTrue(abortBody.contains("db.delete(\"cart_items\", null, null)"))
        assertFalse(abortBody.contains("insertOrThrow(\n                \"sales\""))
        assertFalse(Regex("(?i)\\bUPDATE\\s+sales\\b").containsMatchIn(abortBody))
    }

    @Test
    fun confirmedSaleCorrectionIsAppendOnlyAndPostsToExecutionBusinessDay() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val reversal = source.functionBody("fun createReversal(", "fun createFullReversal(")

        assertTrue(reversal.contains("put(\"original_sale_id\", originalSaleId)"))
        assertTrue(reversal.contains("put(\"business_session_id\", session.id)"))
        assertTrue(reversal.contains("put(\"business_date\", session.businessDate)"))
        assertTrue(reversal.contains("\"reversal_transactions\""))
        assertTrue(reversal.contains("\"reversal_items\""))
        assertTrue(reversal.contains("\"reversal_payments\""))
        assertFalse(reversal.contains("update(\"sales\""))
        assertFalse(reversal.contains("delete(\"sales\""))
        assertFalse(reversal.contains("update(\"sale_items\""))
        assertFalse(reversal.contains("delete(\"sale_items\""))
        assertFalse(reversal.contains("update(\"sale_payments\""))
        assertFalse(reversal.contains("delete(\"sale_payments\""))
        assertFalse(reversal.contains("update(\"settlement_reports\""))
        assertFalse(reversal.contains("delete(\"settlement_reports\""))
    }

    @Test
    fun paymentRefundAndReturnCancelMutualExclusionStayBoundToOriginalSale() {
        val allocations = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 1_200L,
            originalPayments = listOf(
                PaymentTotal(PaymentMethod.CASH.name, 1_000L),
                PaymentTotal(PaymentMethod.CARD.name, 2_000L),
            ),
            refundedPayments = listOf(PaymentTotal(PaymentMethod.CARD.name, 300L)),
        )
        assertEquals(1_200L, allocations.sumOf { it.amount })
        assertTrue(allocations.all { it.amount > 0L })

        val cancelAfterReturn = runCatching {
            PartialReturnPolicy.select(
                type = ReversalType.CANCEL,
                lines = listOf(returnableLine(10L, quantity = 3, returnedQuantity = 1)),
                requestedQuantities = emptyMap(),
            )
        }
        assertTrue(cancelAfterReturn.isFailure)
        assertTrue(cancelAfterReturn.exceptionOrNull()?.message?.contains("一部返品済みの売上は取消できません") == true)
    }

    @Test
    fun confirmedCorrectionRetryIsIdempotentAndCancelCannotBeRekeyed() {
        val returnA = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.RETURN,
            originalSaleId = 77L,
            requestId = "same-request",
        )
        val returnRetry = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.RETURN,
            originalSaleId = 77L,
            requestId = "same-request",
        )
        val cancelA = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.CANCEL,
            originalSaleId = 77L,
            requestId = "cancel-A",
        )
        val cancelB = OperationsIdempotencyPolicy.reversalRequestKey(
            ReversalType.CANCEL,
            originalSaleId = 77L,
            requestId = "cancel-B",
        )

        assertEquals(returnA, returnRetry)
        assertEquals("CANCEL:77", cancelA)
        assertEquals(cancelA, cancelB)

        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val reversal = source.functionBody("fun createReversal(", "fun createFullReversal(")
        assertTrue(reversal.contains("claimOperationKey("))
        assertTrue(reversal.contains("bindOperationKey(operationKey, reversalId)"))
    }

    @Test
    fun correctionProducesAuditPrintAndDownstreamSyncWithoutTouchingPastZ() {
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val document = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()
        val reversal = store.functionBody("fun createReversal(", "fun createFullReversal(")

        assertTrue(reversal.contains("insertDocumentJob("))
        assertTrue(reversal.contains("insertAudit("))
        assertTrue(reversal.contains("eventType = type.name"))
        assertTrue(reversal.contains("元売上 No.$originalSaleId"))
        assertTrue(document.contains("元売上No.${'$'}{data.originalSaleId}"))
        assertTrue(document.contains("返金合計"))
        assertTrue(activity.contains("AutomaticPrintScheduler.enqueueNow(appContext)"))
        assertTrue(activity.contains("DriveOutboxScheduler.enqueueNow(appContext)"))
        assertFalse(reversal.contains("settlement_reports"))
    }

    private fun String.functionBody(startMarker: String, nextMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = indexOf(nextMarker, start + startMarker.length)
        require(end > start) { "next marker not found: $nextMarker" }
        return substring(start, end)
    }
}
