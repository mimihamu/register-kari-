package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136DocumentPrintPreviewTest {
    @Test
    fun formalPreviewWidthsUse58And80Geometry() {
        assertEquals(384, DocumentPrintPreviewV136.previewDotWidth(ReceiptPaper.MM58))
        assertEquals(576, DocumentPrintPreviewV136.previewDotWidth(ReceiptPaper.MM80))
        assertEquals(32, ReceiptPaper.MM58.charsPerLine)
        assertEquals(48, ReceiptPaper.MM80.charsPerLine)
    }

    @Test
    fun saleReceiptPreviewUsesProductionRendererAndFitsEachWidth() {
        val setting = DocumentPrintSettingV136(
            copies = 2,
            header = "保存前ヘッダ",
            footer = "保存前フッタ",
        )

        listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
            val preview = DocumentPrintPreviewV136.render(
                DocumentPrintKindV136.SALE_RECEIPT,
                setting,
                paper,
            )
            assertTrue(preview.contains("保存前ヘッダ"))
            assertTrue(preview.contains("保存前フッタ"))
            assertTrue(preview.contains("通常商品サンプル [内]"))
            assertTrue(preview.contains("軽減税率商品サンプル [内※]"))
            assertTrue(preview.contains("No.000123"))
            preview.lineSequence().forEach { line ->
                assertTrue(
                    "${paper.widthMm}mm preview exceeds ${paper.charsPerLine} columns: $line",
                    ReceiptLineWrapV136.displayWidth(line) <= paper.charsPerLine,
                )
            }
        }
    }

    @Test
    fun previewReflectsUnsavedDraftImmediately() {
        val before = DocumentPrintPreviewV136.render(
            DocumentPrintKindV136.SALE_RECEIPT,
            DocumentPrintSettingV136(header = "変更前", footer = "フッタ"),
            ReceiptPaper.MM58,
        )
        val after = DocumentPrintPreviewV136.render(
            DocumentPrintKindV136.SALE_RECEIPT,
            DocumentPrintSettingV136(header = "未保存の変更後", footer = "フッタ"),
            ReceiptPaper.MM58,
        )

        assertNotEquals(before, after)
        assertTrue(after.contains("未保存の変更後"))
    }

    @Test
    fun everyConfiguredDocumentHas58And80Preview() {
        DocumentPrintKindV136.entries.forEach { kind ->
            listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
                val preview = DocumentPrintPreviewV136.render(
                    kind,
                    DocumentPrintSettingV136(header = "H", footer = "F"),
                    paper,
                )
                assertTrue("empty ${kind.name}/${paper.widthMm}", preview.isNotBlank())
                assertTrue(preview.contains("H"))
                assertTrue(preview.contains("F"))
            }
        }
    }

    @Test
    fun settingsUiExposesSaveBefore58And80Switch() {
        val source = File("app/src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt").readText()
        assertTrue(source.contains("印刷プレビュー（SCR-640・保存前）"))
        assertTrue(source.contains("編集中のヘッダ・フッタを保存せず確認できます"))
        assertTrue(source.contains("previewPaper = ReceiptPaper.MM58"))
        assertTrue(source.contains("previewPaper = ReceiptPaper.MM80"))
        assertTrue(source.contains("DocumentPrintPreviewV136.render(selected, draftSetting, previewPaper)"))
    }
}
