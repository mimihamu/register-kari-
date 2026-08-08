package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V059ReceiptVoucherUiTest {
    @Test
    fun banquetCalculationShowsUnitTimesCopies() {
        val calculation = ReceiptVoucherUiPolicy.calculate("4000", "30")
        requireNotNull(calculation)
        assertEquals(4_000L, calculation.unitAmount)
        assertEquals(30, calculation.copies)
        assertEquals(120_000L, calculation.totalAmount)
        assertTrue(ReceiptVoucherUiPolicy.canIssue(calculation, 120_000L))
        assertTrue(ReceiptVoucherUiPolicy.confirmationText(calculation).contains("30枚"))
    }

    @Test
    fun invalidOrExcessiveInputCannotIssue() {
        assertNull(ReceiptVoucherUiPolicy.calculate("0", "1"))
        assertNull(ReceiptVoucherUiPolicy.calculate("4000", "0"))
        assertNull(ReceiptVoucherUiPolicy.calculate("4000", "201"))
        val calculation = ReceiptVoucherUiPolicy.calculate("4000", "30")
        assertFalse(ReceiptVoucherUiPolicy.canIssue(calculation, 119_999L))
    }

    @Test
    fun sourceRequiresConfirmationAndKeepsImmutableHistory() {
        val root = File("..")
        val ui = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val hub = File("src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt").readText()
        val foundation = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(ui.contains("発行内容を確認"))
        assertTrue(ui.contains("発行を確定"))
        assertTrue(ui.contains("再発行確定"))
        assertTrue(ui.contains("発行可能残額"))
        assertTrue(ui.contains("領収書履歴は削除せず"))
        assertTrue(ui.contains("ReceiptVoucherBatchRequest"))
        assertTrue(ui.contains("voucherStore.reprint"))
        assertTrue(manifest.contains(".ReceiptVoucherActivity"))
        assertTrue(hub.contains("ReceiptVoucherActivity::class.java"))
        assertTrue(hub.contains("一部領収・複数枚・再発行"))
        assertTrue(foundation.contains("receipt_voucher_reprints"))
        assertTrue(workflow.contains("V059ReceiptVoucherUiTest.kt"))
    }
}
