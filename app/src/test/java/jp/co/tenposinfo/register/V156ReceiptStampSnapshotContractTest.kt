package jp.co.tenposinfo.register

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class V156ReceiptStampSnapshotContractTest {
    @Test fun textImageBothSelectExactDeterministicPrefix() {
        val image = byteArrayOf(0x11, 0x22)
        val text = byteArrayOf(0x33, 0x44)
        val common = mapOf(
            ReceiptHeaderModeV135.TEXT to text,
            ReceiptHeaderModeV135.IMAGE to image,
            ReceiptHeaderModeV135.BOTH to image + text,
        )
        common.forEach { (mode, expected) ->
            val snapshot = ReceiptStampSnapshotV136.compose(mode, image, text, 7L, 9L, "a".repeat(64))
            assertContentEquals(expected, snapshot.prefixBytes)
        }
    }

    @Test fun snapshotIdentityChangesWithModeOrComponentVersion() {
        val base = ReceiptStampSnapshotV136.compose(ReceiptHeaderModeV135.BOTH, byteArrayOf(1), byteArrayOf(2), 1L, 1L, "b".repeat(64))
        val imageChanged = ReceiptStampSnapshotV136.compose(ReceiptHeaderModeV135.BOTH, byteArrayOf(1), byteArrayOf(2), 2L, 1L, "b".repeat(64))
        val modeChanged = ReceiptStampSnapshotV136.compose(ReceiptHeaderModeV135.TEXT, byteArrayOf(1), byteArrayOf(2), 1L, 1L, "b".repeat(64))
        assertNotEquals(base.stampVersion, imageChanged.stampVersion)
        assertNotEquals(base.stampVersion, modeChanged.stampVersion)
        assertTrue(base.prefixSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals("AQI=", base.prefixBase64())
    }

    @Test fun saleFinalizationOwnsStampSnapshotAndTransportDoesNotRestamp() {
        val db = source("RegisterDatabase.kt")
        val frozen = source("Syn003FrozenPrintPayloadV136.kt")
        val automatic = source("AutomaticPrintWorker.kt")
        val unified = source("UnifiedPrintQueue.kt")
        val receipt = source("Receipt.kt")
        assertTrue(db.contains("ReceiptStampSnapshotV136.capture("))
        assertTrue(db.contains("stampSnapshot = stampSnapshot"))
        assertTrue(frozen.contains("\\\"stampSnapshot\\\""))
        assertTrue(frozen.contains("sourceImageSha256"))
        assertTrue(frozen.contains("prefixBase64"))
        assertTrue(frozen.contains("stampSnapshot.applyToPayload"))
        assertTrue(receipt.contains("suppressStoreHeader"))
        assertFalse(automatic.contains("ReceiptStampGatewayV136("))
        assertFalse(automatic.contains("ReceiptTextStampGatewayV136("))
        assertFalse(unified.contains("ReceiptStampGatewayV136("))
        assertFalse(unified.contains("ReceiptTextStampGatewayV136("))
    }

    private fun source(name: String): String = File("src/main/java/jp/co/tenposinfo/register/$name").readText()
}
