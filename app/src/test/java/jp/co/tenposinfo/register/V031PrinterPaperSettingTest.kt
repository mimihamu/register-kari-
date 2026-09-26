package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V031PrinterPaperSettingTest {
    private fun source(name: String) =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun printExecutionScreensDoNotOfferPaperWidthSelection() {
        val main = source("MainActivity.kt")
        val operations = source("OperationsActivity.kt")
        val historyV027 = source("SettlementHistoryScreenV027.kt")
        val historyV030 = source("SettlementHistoryActivityV030.kt")
        val settings = source("AdminSettingsActivity.kt")

        for (text in listOf(main, operations, historyV027, historyV030)) {
            assertFalse(text.contains("ChoiceButton(\"58mm\""))
            assertFalse(text.contains("ChoiceButton(\"80mm\""))
            assertFalse(text.contains("HistoryFilterButton(\"58mm\""))
            assertFalse(text.contains("HistoryChoiceButtonV030(\"58mm\""))
        }
        assertTrue(main.contains("プリンタ設定 ${'$'}{paper.widthMm}mm"))
        assertTrue(operations.contains("プリンタ設定 ${'$'}{printerPaperWidthMm}mm"))
        assertTrue(historyV030.contains("プリンタ設定 ${'$'}{printerPaperWidthMm}mm"))

        // 幅の選択はプリンター設定画面だけに残す。
        assertTrue(settings.contains("AsChoiceButton(\"58mm\""))
        assertTrue(settings.contains("AsChoiceButton(\"80mm\""))
    }

    @Test
    fun printRequestApisDoNotAcceptPaperWidth() {
        val database = source("RegisterDatabase.kt")
        val operations = source("OperationsStore.kt")
        val coordinator = source("SecureOperationsCoordinator.kt")
        val receipt = source("Receipt.kt")

        // RCP-004 adds actor for audit, but paper width remains resolved from printer settings.
        val enqueueReprintSignature = database
            .substringAfter("fun enqueueReprint(")
            .substringBefore("): Long")
        assertTrue(enqueueReprintSignature.contains("saleId: Long"))
        assertTrue(enqueueReprintSignature.contains("actor: String"))
        assertFalse(enqueueReprintSignature.contains("paperWidth"))
        assertFalse(database.substringAfter("fun saveSale(").substringBefore("): Long").contains("paperWidthMm"))
        assertFalse(operations.substringAfter("fun previewSettlement(").substringBefore("): String").contains("paperWidth"))
        assertFalse(operations.substringAfter("fun reprintSettlement(").substringBefore("): Long").contains("paperWidth"))
        assertFalse(coordinator.substringAfter("fun reprintSettlement(").substringBefore("): Long").contains("paperWidth"))
        assertFalse(coordinator.substringAfter("fun createReversal(").substringBefore("): PartialReversalResult").contains("paperWidth"))
        assertFalse(receipt.substringAfter("fun encode(").substringBefore("): ByteArray").contains("paper:"))
    }

    @Test
    fun allPrintPathsResolveWidthFromDocumentPrinterRoute() {
        val settings = source("AdminSettingsStore.kt")
        val database = source("RegisterDatabase.kt")
        val operations = source("OperationsStore.kt")
        val advanced = source("AdvancedOperationsStore.kt")
        val receipt = source("Receipt.kt")

        assertTrue(settings.contains("object PrinterPaperSettingPolicy"))
        assertTrue(settings.contains("PrinterRoutingV136.resolve(context.applicationContext, DocumentPrintKindV136.SALE_RECEIPT)"))
        assertTrue(database.contains("PrinterRoutingV136.resolve("))
        assertTrue(database.contains("DocumentPrintKindV136.SALE_RECEIPT"))
        assertTrue(operations.contains("PrinterRoutingV136.resolve(appContext, documentPrintKind)"))
        assertTrue(advanced.contains("PrinterRoutingV136.resolve(appContext, documentPrintKind)"))
        assertTrue(receipt.contains("PrinterPaperSettingPolicy.paper(configuration)"))
    }
}
