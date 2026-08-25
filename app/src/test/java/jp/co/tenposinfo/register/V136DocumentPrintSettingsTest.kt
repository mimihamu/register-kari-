package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136DocumentPrintSettingsTest {
    @Test
    fun copiesAreClampedToFormalRanges() {
        assertEquals(1, DocumentPrintSettingsPolicyV136.normalizeCopies(0))
        assertEquals(3, DocumentPrintSettingsPolicyV136.normalizeCopies(9))
        assertEquals(1, DocumentPrintSettingsPolicyV136.normalizeCopies(DocumentPrintKindV136.SALE_RECEIPT, 0))
        assertEquals(1, DocumentPrintSettingsPolicyV136.normalizeCopies(DocumentPrintKindV136.RECEIPT_VOUCHER, 0))
        assertEquals(1, DocumentPrintSettingsPolicyV136.normalizeCopies(DocumentPrintKindV136.PROVISIONAL_RECEIPT, 0))
        assertEquals(0, DocumentPrintSettingsPolicyV136.normalizeCopies(DocumentPrintKindV136.INSPECTION, -1))
        assertEquals(0, DocumentPrintSettingsPolicyV136.normalizeCopies(DocumentPrintKindV136.SETTLEMENT, 0))
        assertEquals(3, DocumentPrintSettingsPolicyV136.normalizeCopies(DocumentPrintKindV136.SETTLEMENT, 9))
    }

    @Test
    fun documentKindsKeepIndependentStorageKeys() {
        assertEquals(5, DocumentPrintKindV136.entries.size)
        assertEquals(
            DocumentPrintKindV136.entries.size,
            DocumentPrintKindV136.entries.map { it.storageKey }.distinct().size,
        )
    }

    @Test
    fun backwardsCompatibleDefaultsPreserveExistingFlows() {
        assertTrue(DocumentPrintKindV136.SALE_RECEIPT.defaultAutoPrint)
        assertTrue(DocumentPrintKindV136.INSPECTION.defaultAutoPrint)
        assertTrue(DocumentPrintKindV136.SETTLEMENT.defaultAutoPrint)
        assertFalse(DocumentPrintKindV136.RECEIPT_VOUCHER.defaultAutoPrint)
        assertFalse(DocumentPrintKindV136.PROVISIONAL_RECEIPT.defaultAutoPrint)
    }

    @Test
    fun headerAndFooterDecoratePayload() {
        assertEquals(
            "BODY",
            DocumentPrintSettingsPolicyV136.decorateText("BODY", DocumentPrintSettingV136()),
        )
        assertEquals(
            "HEADER\nBODY\nFOOTER",
            DocumentPrintSettingsPolicyV136.decorateText(
                "BODY",
                DocumentPrintSettingV136(header = "HEADER", footer = "FOOTER"),
            ),
        )
    }

    @Test
    fun saleCopiesStayInsideOneLogicalQueueJob() {
        val source = File("src/main/java/jp/co/tenposinfo/register/Receipt.kt").readText()
        assertTrue(source.contains("documentCopies: Int = 1"))
        assertTrue(source.contains("openDrawer = false"))
        assertFalse(source.contains("openDrawer = openDrawer && copyIndex == 0"))
        assertTrue(source.contains("applyToReceipt(receipt, saleReceiptSetting)"))
    }

    @Test
    fun xAndZAutomaticPrintingAreIndependentlyGated() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        assertTrue(source.contains("DocumentPrintKindV136.INSPECTION"))
        assertTrue(source.contains("DocumentPrintKindV136.SETTLEMENT"))
        assertTrue(source.contains("if (documentPrintSetting.autoPrintEnabled)"))
        assertTrue(source.contains("normalizeCopies(settingsKind, setting.copies)"))
        assertTrue(source.contains("kotlin.repeat(copies)"))
    }

    @Test
    fun printerSettingsExposeRcp016Panel() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt").readText()
        assertTrue(source.contains("DocumentPrintSettingsPanelV136(receiptAutoPrintEnabled = receiptAutoPrint)"))
    }

    @Test
    fun receiptVoucherAndProvisionalRoutesHonorRcp016() {
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val provisional = File("src/main/java/jp/co/tenposinfo/register/HeldTicketProvisionalPrintV135.kt").readText()
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()

        assertTrue(voucher.contains("DocumentPrintKindV136.RECEIPT_VOUCHER"))
        assertTrue(voucher.contains("if (documentPrintSetting.autoPrintEnabled)"))
        assertTrue(voucher.contains("decorateText(payload, documentPrintSetting)"))
        assertTrue(voucher.contains("normalizeCopies(documentPrintSetting.copies)"))
        assertTrue(voucher.contains("kotlin.repeat("))
        assertTrue(provisional.contains("fun enqueueIfAutomatic"))
        assertTrue(provisional.contains("DocumentPrintKindV136.PROVISIONAL_RECEIPT"))
        assertTrue(provisional.contains("decorateText(payload, documentPrintSetting)"))
        assertTrue(provisional.contains("kotlin.repeat("))
        assertTrue(main.contains("service.enqueueIfAutomatic(heldTicketId, operatorName)"))
    }
}
