package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusProbeAnnotationTest {
    @Test
    fun conditionIsRequiredAndOtherNeedsMemo() {
        assertEquals(
            "試験条件を選択してください",
            PrinterStatusProbeAnnotationPolicy.validationError(PrinterStatusProbeAnnotation()),
        )
        assertEquals(
            "「その他」の場合はメモへ試験条件を入力してください",
            PrinterStatusProbeAnnotationPolicy.validationError(
                PrinterStatusProbeAnnotation(condition = PrinterStatusTestCondition.OTHER),
            ),
        )
        assertNull(
            PrinterStatusProbeAnnotationPolicy.validationError(
                PrinterStatusProbeAnnotation(
                    condition = PrinterStatusTestCondition.OTHER,
                    memo = "キャッシュドロア接続時",
                ),
            ),
        )
    }

    @Test
    fun annotationTextIsTrimmedNormalizedAndLimited() {
        val normalized = PrinterStatusProbeAnnotationPolicy.normalize(
            PrinterStatusProbeAnnotation(
                condition = PrinterStatusTestCondition.PAPER_OUT,
                printerModel = "  TM-m30II  ",
                emulationMode = "  ESC/POS\r\nMode  ",
                memo = "x".repeat(600),
            ),
        )

        assertEquals("TM-m30II", normalized.printerModel)
        assertEquals("ESC/POS\nMode", normalized.emulationMode)
        assertEquals(PrinterStatusProbeAnnotationPolicy.MAX_MEMO_LENGTH, normalized.memo.length)
    }

    @Test
    fun conditionAndKeywordFiltersMatchModelModeMemoIpAndHex() {
        val record = record()

        assertTrue(
            PrinterStatusProbeAnnotationPolicy.matches(
                record,
                PrinterStatusTestCondition.COVER_OPEN,
                "mC-Print3",
            ),
        )
        assertTrue(PrinterStatusProbeAnnotationPolicy.matches(record, null, "star esc/pos"))
        assertTrue(PrinterStatusProbeAnnotationPolicy.matches(record, null, "カバーを開けた"))
        assertTrue(PrinterStatusProbeAnnotationPolicy.matches(record, null, "192.168.10.20"))
        assertTrue(PrinterStatusProbeAnnotationPolicy.matches(record, null, "12 16"))
        assertFalse(
            PrinterStatusProbeAnnotationPolicy.matches(
                record,
                PrinterStatusTestCondition.PAPER_OUT,
                "",
            ),
        )
        assertFalse(PrinterStatusProbeAnnotationPolicy.matches(record, null, "TM-T88"))
    }

    @Test
    fun summaryKeepsConditionModelModeAndMemo() {
        assertEquals(
            "紙切れ / 機種:TM-m30II / モード:EPSON ESC/POS / ロール紙を外して採取",
            PrinterStatusProbeAnnotationPolicy.summary(
                PrinterStatusProbeAnnotation(
                    condition = PrinterStatusTestCondition.PAPER_OUT,
                    printerModel = "TM-m30II",
                    emulationMode = "EPSON ESC/POS",
                    memo = "ロール紙を外して採取",
                ),
            ),
        )
    }

    private fun record() = PrinterStatusProbeHistoryRecord(
        id = 12L,
        startedAt = 1_000L,
        profile = PrinterProfile.STAR_ESC_POS,
        preset = PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
        verification = PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY,
        host = "192.168.10.20",
        port = 9100,
        elapsedMillis = 15L,
        requestHex = "10 04 01",
        responseHex = "12 16 12 12",
        responseAscii = "....",
        responseSize = 4,
        success = true,
        parsedLevel = null,
        parsedSummary = null,
        protocolValid = null,
        errorMessage = null,
        actor = "tester",
        createdAt = 2_000L,
        condition = PrinterStatusTestCondition.COVER_OPEN,
        printerModel = "STAR mC-Print3",
        emulationMode = "Star ESC/POS",
        memo = "カバーを開けた状態",
    )
}
