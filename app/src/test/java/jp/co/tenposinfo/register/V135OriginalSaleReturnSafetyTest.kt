package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V135OriginalSaleReturnSafetyTest {
    @Test
    fun returnReReadsRemainingQuantityAndSaleTimeTaxSnapshotInsideStorePath() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        assertTrue(source.contains("val lines = loadReturnableLines(this, originalSaleId)"))
        assertTrue(source.contains("PartialReturnPolicy.select(type, lines, requestedQuantities)"))
        assertTrue(source.contains("LEFT JOIN line_tax_snapshots lts"))
        assertTrue(source.contains("lts.scope = 'SALE'"))
        assertTrue(source.contains("COALESCE(SUM(ri.return_quantity), 0)"))
    }

    @Test
    fun repeatedSplitTenderReturnUsesPriorRefundsBeforeNewAllocation() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val priorRefundQuery = source.indexOf("WHERE r.original_sale_id = ?")
        val allocation = source.indexOf("refundedPayments = refundedPayments")
        val reversalInsert = source.indexOf("\"reversal_transactions\"")

        assertTrue(priorRefundQuery >= 0)
        assertTrue(allocation > priorRefundQuery)
        assertTrue(reversalInsert > allocation)
        assertTrue(source.contains("FROM reversal_payments rp"))
        assertTrue(source.contains("GROUP BY rp.payment_method"))
    }

    @Test
    fun allocatorCapsEachTenderByUnrefundedOriginalCapacity() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PartialReturn.kt").readText()
        assertTrue(source.contains("refundedPayments: List<PaymentTotal> = emptyList()"))
        assertTrue(source.contains("originalAmount - (refundedByMethod[method] ?: 0L)"))
        assertTrue(source.contains("返金額が元支払の未返金残高を超えています"))
        assertTrue(source.contains("check(amount <= capacity)"))
        assertTrue(source.contains("BigInteger.valueOf(refundTotal)"))
    }

    @Test
    fun coordinatorRequiresReversalPermissionManagerPinAndSameSaleExecutionGate() {
        val source = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        assertTrue(source.contains("RegisterPermission.REVERSAL"))
        assertTrue(source.contains("managerNameForPin(managerPin)"))
        assertTrue(source.contains("reversal:$originalSaleId"))
    }

    @Test
    fun correctionRemainsAppendOnly() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        assertTrue(source.contains("insertOrThrow(\n                \"reversal_transactions\""))
        assertTrue(source.contains("insertOrThrow(\n                    \"reversal_items\""))
        assertTrue(source.contains("insertOrThrow(\n                    \"reversal_payments\""))
        assertFalse(Regex("(?i)DELETE\\s+FROM\\s+(sales|sale_items|reversal_transactions|reversal_items|reversal_payments)").containsMatchIn(source))
    }
}
