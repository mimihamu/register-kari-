package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V151Syn003FrozenPrintSnapshotTest {
    private fun source(name: String): String = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test fun finalizationSnapshotContainsRequiredSyn003Authority() {
        val frozen = source("Syn003FrozenPrintPayloadV136.kt")
        listOf(
            "documentId",
            "layoutVersion",
            "printerProfileSnapshot",
            "renderedPayloadBase64",
            "reprintPayloadBase64",
            "normalSha256",
            "reprintSha256",
        ).forEach { marker -> assertTrue("missing $marker", frozen.contains(marker)) }
        assertTrue(frozen.contains("PrinterConfigurationRegistry.current()"))
        assertTrue(frozen.contains("EscPosEncoder.encode(receipt(false), configuration)"))
        assertTrue(frozen.contains("EscPosEncoder.encode(receipt(true), configuration)"))
    }

    @Test fun saleFinalizationFreezesBeforeTransactionReturns() {
        val snapshot = source("PrintDocumentSnapshotV136.kt")
        assertTrue(snapshot.contains("Syn003FrozenPrintPayloadV136.freezeSalePayload"))
        assertTrue(snapshot.contains("ContentValues().apply { put(\"payload_json\", payload) }"))
        assertTrue(snapshot.contains("put(\"payload_json\", payload)"))
    }

    @Test fun reprintJobInheritsOriginalSaleJournalSnapshot() {
        val snapshot = source("PrintDocumentSnapshotV136.kt")
        assertTrue(snapshot.contains("CAST(NEW.sale_id AS TEXT)"))
        assertTrue(snapshot.contains("instr(j.payload_json, '\"syn003FrozenPrint\"') > 0"))
        assertTrue(snapshot.contains("COALESCE("))
    }

    @Test fun queueUsesFrozenBytesBeforeLegacyRenderingFallback() {
        val receipt = source("Receipt.kt")
        val frozenIndex = receipt.indexOf("Syn003FrozenPrintPayloadV136.loadJobPayload")
        val fallbackIndex = receipt.indexOf("ReceiptFactory.fromSale(detail, reprint = isReprint)")
        assertTrue(frozenIndex >= 0)
        assertTrue(fallbackIndex > frozenIndex)
        assertTrue(receipt.contains("Legacy rows created before SYN-003"))
        assertFalse(receipt.substring(frozenIndex, fallbackIndex).contains("PrinterConfigurationRegistry.current()"))
    }
}
