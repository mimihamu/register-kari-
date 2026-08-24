package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class V136DocumentPrintPreviewOperationsTest {
    @Test
    fun previewShowsConfiguredCopyCountWithoutChangingFormalCopyBounds() {
        val setting = DocumentPrintSettingV136(
            autoPrintEnabled = true,
            copies = 2,
        )
        val state = DocumentPrintPreviewPolicyV136.evaluate(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
            setting,
        )

        assertEquals(2, state.effectiveCopies)
        assertFalse(state.printDisabled)
        assertTrue(state.canRender)
        val preview = DocumentPrintPreviewV136.render(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
            setting,
            ReceiptPaper.MM58,
        )
        assertTrue(preview.contains("プレビュー: 印刷部数 2部"))
    }

    @Test
    fun inspectionAndSettlementZeroCopiesAreExplicitlyPreviewedAsElectronicOnly() {
        listOf(DocumentPrintKindV136.INSPECTION, DocumentPrintKindV136.SETTLEMENT).forEach { kind ->
            val setting = DocumentPrintSettingV136(copies = 0)
            val state = DocumentPrintPreviewPolicyV136.evaluate(kind, setting)

            assertEquals(0, state.effectiveCopies)
            assertTrue(state.printDisabled)
            assertTrue(state.canRender)
            val preview = DocumentPrintPreviewV136.render(kind, setting, ReceiptPaper.MM58)
            assertTrue(preview.replace("\n", "").contains("印刷無効（電子保存のみ）"))
        }
    }

    @Test
    fun documentKindsThatCannotBeDisabledClampZeroCopiesBackToOne() {
        listOf(
            DocumentPrintKindV136.SALE_RECEIPT,
            DocumentPrintKindV136.RECEIPT_VOUCHER,
            DocumentPrintKindV136.PROVISIONAL_RECEIPT,
        ).forEach { kind ->
            val state = DocumentPrintPreviewPolicyV136.evaluate(
                kind,
                DocumentPrintSettingV136(copies = 0),
            )
            assertEquals(1, state.effectiveCopies)
            assertFalse(state.printDisabled)
        }
    }

    @Test
    fun autoPrintOffIsDistinguishedFromDocumentDisabled() {
        val setting = DocumentPrintSettingV136(
            autoPrintEnabled = false,
            copies = 1,
        )
        val state = DocumentPrintPreviewPolicyV136.evaluate(
            DocumentPrintKindV136.PROVISIONAL_RECEIPT,
            setting,
        )

        assertFalse(state.autoPrintEnabled)
        assertFalse(state.printDisabled)
        val preview = DocumentPrintPreviewV136.render(
            DocumentPrintKindV136.PROVISIONAL_RECEIPT,
            setting,
            ReceiptPaper.MM58,
        )
        assertTrue(preview.contains("自動印刷OFF（手動印刷可）"))
    }

    @Test
    fun invalidSaleFooterFailsPreviewWithActionableCorrectionRoute() {
        val invalidFooter = (1..11).joinToString("\n") { "固定文$it" }
        val setting = DocumentPrintSettingV136(footer = invalidFooter)
        val state = DocumentPrintPreviewPolicyV136.evaluate(
            DocumentPrintKindV136.SALE_RECEIPT,
            setting,
        )

        assertNotNull(state.validationError)
        assertFalse(state.canRender)
        val error = assertThrows(IllegalArgumentException::class.java) {
            DocumentPrintPreviewV136.render(
                DocumentPrintKindV136.SALE_RECEIPT,
                setting,
                ReceiptPaper.MM58,
            )
        }
        assertTrue(error.message.orEmpty().contains("最大10行"))
        assertTrue(error.message.orEmpty().contains("設定項目を修正してください"))
    }

    @Test
    fun previewStatusAndDocumentRemainInsideEachFormalLogicalWidth() {
        DocumentPrintKindV136.entries.forEach { kind ->
            listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
                val copies = if (kind == DocumentPrintKindV136.INSPECTION) 0 else 3
                val preview = DocumentPrintPreviewV136.render(
                    kind,
                    DocumentPrintSettingV136(autoPrintEnabled = false, copies = copies),
                    paper,
                )
                preview.lineSequence().forEach { line ->
                    assertTrue(
                        "${kind.name}/${paper.widthMm}mm exceeds ${paper.charsPerLine}: $line",
                        ReceiptLineWrapV136.displayWidth(line) <= paper.charsPerLine,
                    )
                }
            }
        }
    }
}
