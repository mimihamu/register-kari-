package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.5 UI: one upper-left Back, no legacy SCR header or redundant bottom Back. */
class V151LegacyNavigationUnificationTest {
    private val base = File("src/main/java/jp/co/tenposinfo/register")
    private fun read(name: String) = File(base, name).readText()

    @Test fun settlementAndCsvScreensUseSharedNavigationWhileKeepingExport() {
        for (name in listOf(
            "SettlementActivityV030.kt",
            "SettlementHistoryActivityV030.kt",
            "SettlementReconciliationAuditCsvExportActivityV082.kt",
        )) {
            val s = read(name)
            assertTrue(name, s.contains("RegisterTopBarV141("))
            assertTrue(name, s.contains("onBack = onClose"))
            assertFalse(name, s.contains("レジ管理へ戻る"))
            assertFalse(name, s.contains("点検・精算履歴へ戻る"))
        }
        assertTrue(read("SettlementActivityV030.kt").contains("onPdfSave"))
        assertTrue(read("SettlementReconciliationAuditCsvExportActivityV082.kt").contains("csv"))
        assertTrue(read("SettlementActivityV030.kt").contains("showConfirmation"))
    }

    @Test fun receiptAndReprintActivitiesKeepActionsAndHideObsoleteFooter() {
        val names = listOf(
            "ReceiptVoucherActivity.kt", "ReceiptVoucherLedgerActivity.kt",
            "SaleReceiptReprintActivity.kt", "SaleReceiptReprintLedgerActivity.kt",
        )
        for (name in names) {
            val src = read(name)
            assertTrue(name, src.contains("RegisterTopBarV141("))
            assertTrue(name, src.contains("onBack = onClose"))
            assertFalse(name, src.contains("管理メニューへ戻る"))
            assertFalse(name, src.contains("レジ管理へ戻る"))
        }
        assertTrue(read("ReceiptVoucherActivity.kt").contains("運用台帳・印刷状態"))
        assertTrue(read("ReceiptVoucherLedgerActivity.kt").contains("統合印刷キュー"))
        assertTrue(read("SaleReceiptReprintActivity.kt").contains("onOpenQueue"))
        assertTrue(read("SaleReceiptReprintLedgerActivity.kt").contains("ReprintLedgerSummaryCard"))
    }

    @Test fun printerScreensShareHeaderAndKeepUncertainSendSafety() {
        val names = listOf(
            "PrinterStatusAnalysisActivity.kt", "PrinterStatusLabActivity.kt",
            "PrinterStatusValidationActivity.kt", "PrinterSoakTestHistoryActivity.kt",
        )
        for (name in names) {
            val src = read(name)
            assertTrue(name, src.contains("RegisterTopBarV141("))
            assertTrue(name, src.contains("onBack = "))
            assertFalse(name, src.contains("プリンター運用へ戻る"))
        }
        val lab = read("PrinterStatusLabActivity.kt")
        assertTrue(lab.contains("if (!running) onClose()"))
        val queue = read("UnifiedPrintQueueActivity.kt")
        assertTrue(queue.contains("RegisterTopBarV141("))
        assertTrue(queue.contains("if (!working) onClose()"))
        assertTrue(queue.contains("PrintJobStatus.SENDING"))
        assertTrue(queue.contains("完了扱いまたは理由付き再印刷"))
        assertTrue(queue.contains("QueueFooter("))
        assertFalse(queue.contains("Text(\"閉じる\", fontWeight = FontWeight.Bold)"))
    }
}
