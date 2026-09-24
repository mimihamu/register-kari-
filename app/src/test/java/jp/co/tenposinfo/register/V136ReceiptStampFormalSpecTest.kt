package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正式仕様 v2.5 §8.14 SCR-720 の画像スタンプ要件を固定する。
 * Issue #137 の旧「ロゴ濃度」「20種プリセット」表現ではなく正式仕様を正とする。
 */
class V136ReceiptStampFormalSpecTest {
    @Test
    fun imageStampSupportsFormalAdjustmentControls() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ReceiptStampV136.kt").readText()

        assertEquals(-100, ReceiptStampPolicyV136.MIN_BRIGHTNESS)
        assertEquals(100, ReceiptStampPolicyV136.MAX_BRIGHTNESS)
        assertEquals(0, ReceiptStampPolicyV136.MIN_THRESHOLD)
        assertEquals(255, ReceiptStampPolicyV136.MAX_THRESHOLD)
        assertEquals(384, ReceiptStampPolicyV136.MM58_MAX_DOTS)
        assertEquals(576, ReceiptStampPolicyV136.MM80_MAX_DOTS)

        assertTrue(source.contains("rotationDegrees in setOf(0, 90, 180, 270)"))
        assertTrue(source.contains("cropPercent in 0..ReceiptStampPolicyV136.MAX_CROP_PERCENT"))
        assertTrue(source.contains("ReceiptStampDitherV136.entries"))
        assertTrue(source.contains("store.previewBitmap(it, ReceiptPaper.MM58)"))
        assertTrue(source.contains("store.previewBitmap(it, ReceiptPaper.MM80)"))
    }

    @Test
    fun stampVersionAndSourceHashAreFrozenForHistoricalReprints() {
        val stamp = File("src/main/java/jp/co/tenposinfo/register/ReceiptStampV136.kt").readText()
        val snapshot = File("src/main/java/jp/co/tenposinfo/register/ReceiptStampSnapshotV136.kt").readText()

        assertTrue(stamp.contains("stampVersion = load().stampVersion + 1L"))
        assertTrue(stamp.contains("fun sourceSha256(): String?"))
        assertTrue(snapshot.contains("imageStampVersion"))
        assertTrue(snapshot.contains("sourceImageSha256"))
        assertTrue(snapshot.contains("prefixBytes"))
    }
}
