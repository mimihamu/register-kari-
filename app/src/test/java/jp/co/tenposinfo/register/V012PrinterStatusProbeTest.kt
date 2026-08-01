package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusProbeTest {
    @Test
    fun epsonUsesDocumentedBatchPreset() {
        val preset = PrinterStatusProbePolicy.presetFor(PrinterProfile.EPSON_TM_JAPAN)

        assertEquals(PrinterStatusProbePreset.EPSON_DLE_EOT_BATCH, preset)
        assertFalse(preset.experimental)
        assertEquals(12, preset.requestBytes.size)
    }

    @Test
    fun starAndGenericUseExplicitlyExperimentalPreset() {
        assertEquals(
            PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
            PrinterStatusProbePolicy.presetFor(PrinterProfile.STAR_ESC_POS),
        )
        assertEquals(
            PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
            PrinterStatusProbePolicy.presetFor(PrinterProfile.GENERIC_ESC_POS),
        )
        assertFalse(
            PrinterStatusProbePolicy.canRun(
                PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
                experimentalConfirmed = false,
            ),
        )
        assertTrue(
            PrinterStatusProbePolicy.canRun(
                PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
                experimentalConfirmed = true,
            ),
        )
    }

    @Test
    fun fourByteResponseCanBeRenderedAndParsedWithoutClaimingStarCompatibility() {
        val result = PrinterStatusProbeResult(
            preset = PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
            host = "192.168.1.10",
            port = 9100,
            startedAt = 1_000L,
            elapsedMillis = 12L,
            requestBytes = PrinterRealtimeStatusProtocol.requestBytes(),
            responseBytes = byteArrayOf(0x12, 0x12, 0x12, 0x12),
        )

        assertEquals("12 12 12 12", result.responseHex)
        assertEquals(PrinterStatusLevel.READY, result.parsedEpsonStatus?.level)
        val csv = PrinterStatusProbeCsv.render(PrinterProfile.STAR_ESC_POS, result)
        assertTrue(csv.contains("互換試行・未検証"))
        assertTrue(csv.contains("受信HEX,12 12 12 12"))
        assertTrue(csv.contains("EPSON形式解析"))
    }
}
