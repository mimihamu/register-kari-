package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V061ReceiptVoucherSaleContextNavigationTest {
    @Test
    fun requestedExistingSaleIsSelectedAndLocked() {
        val context = ReceiptVoucherNavigation.resolveSaleContext(20L, listOf(30L, 20L, 10L))
        assertEquals(20L, context.selectedSaleId)
        assertTrue(context.selectionLocked)
        assertFalse(context.requestedSaleUnavailable)
    }

    @Test
    fun unavailableRequestedSaleFallsBackWithoutLock() {
        val context = ReceiptVoucherNavigation.resolveSaleContext(99L, listOf(30L, 20L))
        assertEquals(30L, context.selectedSaleId)
        assertFalse(context.selectionLocked)
        assertTrue(context.requestedSaleUnavailable)
    }

    @Test
    fun emptySalesReturnsNoSelection() {
        val context = ReceiptVoucherNavigation.resolveSaleContext(null, emptyList())
        assertNull(context.selectedSaleId)
        assertFalse(context.selectionLocked)
        assertFalse(context.requestedSaleUnavailable)
    }

    @Test
    fun sourceConnectsCompletionSaleDetailIssuanceAndLedgerWithoutChangingSafetyRules() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val issuance = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val ledger = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt").readText()
        val navigation = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherNavigation.kt").readText()
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(main.contains("ReceiptVoucherNavigation.issuanceIntent(context, lastSaleId)"))
        assertTrue(main.contains("ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id)"))
        assertTrue(main.contains("この売上で領収書発行"))
        assertTrue(issuance.contains("対象売上は固定されています"))
        assertTrue(issuance.contains("lockedSaleId"))
        assertTrue(issuance.contains("ReceiptVoucherNavigation.ledgerIntent(context, selectedSaleId)"))
        assertTrue(ledger.contains("この売上で追加発行"))
        assertTrue(ledger.contains("ReceiptVoucherNavigation.issuanceIntent"))
        assertTrue(navigation.contains("requestedSaleUnavailable"))
        assertTrue(voucher.contains("requestId"))
        assertTrue(voucher.contains("remainingAmount"))
        assertTrue(workflow.contains("V061ReceiptVoucherSaleContextNavigationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk"))
    }
}
