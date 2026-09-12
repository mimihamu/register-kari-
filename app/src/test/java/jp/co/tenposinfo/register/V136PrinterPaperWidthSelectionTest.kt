package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrinterPaperWidthSelectionTest {
    @Test
    fun formalPaperWidthsMapToReceiptColumns() {
        assertEquals(58, ReceiptPaper.fromWidth(58).widthMm)
        assertEquals(32, ReceiptPaper.fromWidth(58).charsPerLine)
        assertEquals(80, ReceiptPaper.fromWidth(80).widthMm)
        assertEquals(48, ReceiptPaper.fromWidth(80).charsPerLine)
    }

    @Test
    fun fourFormalDocumentsUseSelectedWidth() {
        val paper58 = ReceiptPaper.fromWidth(58)
        val paper80 = ReceiptPaper.fromWidth(80)
        val text58 = PrinterPaperWidthTestV136.buildAll(paper58, "2026-08-22T00:00:00Z")
        val text80 = PrinterPaperWidthTestV136.buildAll(paper80, "2026-08-22T00:00:00Z")

        assertEquals(4, PrinterPaperTestDocumentV136.entries.size)
        listOf("販売レシート", "領収書", "仮締め票", "精算票").forEach {
            assertTrue(text58.contains("[$it]"))
            assertTrue(text80.contains("[$it]"))
        }
        assertEquals(32, PrinterPaperWidthTestV136.separator(paper58).length)
        assertEquals(48, PrinterPaperWidthTestV136.separator(paper80).length)
        assertEquals(32, PrinterPaperWidthTestV136.ruler(paper58).length)
        assertEquals(48, PrinterPaperWidthTestV136.ruler(paper80).length)
    }

    @Test
    fun printerSettingsAllowOnly58Or80AndPersistPaperWidth() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt").readText()
        assertTrue(source.contains("configuration.paperWidthMm == 58 || configuration.paperWidthMm == 80"))
        assertTrue(source.contains("put(\"paper_width_mm\", configuration.paperWidthMm)"))
        assertTrue(source.contains("PrinterPaperWidthTestV136.buildAll"))
    }

    @Test
    fun salePrintJobSnapshotsWidthAndWorkerUsesSnapshot() {
        val database = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val receipt = File("src/main/java/jp/co/tenposinfo/register/Receipt.kt").readText()

        assertTrue(database.contains("insertPrintJob(this, saleId, paperWidthMm, createdAt)"))
        assertTrue(database.contains("put(\"paper_width_mm\", if (paperWidthMm >= 80) 80 else 58)"))
        assertTrue(receipt.contains("paperWidthMm = job.paperWidthMm"))
        assertTrue(receipt.contains("EscPosEncoder.encode(configuredReceipt, configuredSnapshot)"))
    }

    @Test
    fun settingsUiExposesOnly58And80Choices() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt").readText()
        assertTrue(source.contains("AsChoiceButton(\"58mm\""))
        assertTrue(source.contains("AsChoiceButton(\"80mm\""))
    }
}
