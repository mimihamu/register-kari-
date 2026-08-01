package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusProbeAnalysisTest {
    @Test
    fun progressCountsSuccessfulFailedAndMissingConditions() {
        val analysis = PrinterStatusProbeAnalysisPolicy.analyze(
            listOf(
                record(1, PrinterStatusTestCondition.NORMAL, true, "12 12 12 12"),
                record(2, PrinterStatusTestCondition.COVER_OPEN, false, ""),
                record(3, PrinterStatusTestCondition.COVER_OPEN, true, "12 16 12 12"),
            ),
        ).single()

        val normal = analysis.progress.first { it.condition == PrinterStatusTestCondition.NORMAL }
        val cover = analysis.progress.first { it.condition == PrinterStatusTestCondition.COVER_OPEN }
        val paperOut = analysis.progress.first { it.condition == PrinterStatusTestCondition.PAPER_OUT }

        assertEquals(1, normal.successCount)
        assertEquals("成功あり", normal.stateLabel)
        assertEquals(2, cover.totalCount)
        assertEquals(1, cover.successCount)
        assertEquals(1, cover.failureCount)
        assertEquals("成功あり", cover.stateLabel)
        assertEquals("未実施", paperOut.stateLabel)
        assertEquals(2, analysis.successfulConditionCount)
        assertEquals(7, analysis.requiredConditionCount)
    }

    @Test
    fun stableCandidateRequiresEachSideToBeInternallyConsistent() {
        val records = listOf(
            record(1, PrinterStatusTestCondition.NORMAL, true, "12 12"),
            record(2, PrinterStatusTestCondition.NORMAL, true, "12 12"),
            record(3, PrinterStatusTestCondition.COVER_OPEN, true, "12 16"),
            record(4, PrinterStatusTestCondition.COVER_OPEN, true, "12 16"),
        )

        val candidate = PrinterStatusProbeAnalysisPolicy.analyzeCondition(
            records,
            PrinterStatusTestCondition.COVER_OPEN,
        )

        assertFalse(candidate.sizeMismatch)
        assertEquals(2, candidate.normalSampleCount)
        assertEquals(2, candidate.conditionSampleCount)
        assertEquals(1, candidate.stableChanges.size)
        val change = candidate.stableChanges.single()
        assertEquals(1, change.byteIndex)
        assertEquals(2, change.bitIndex)
        assertEquals(0, change.normalValue)
        assertEquals(1, change.conditionValue)
        assertEquals("byte[1] bit2 (0x04): 0→1", change.label)
    }

    @Test
    fun unstableBitsAreNotPromotedToCandidates() {
        val records = listOf(
            record(1, PrinterStatusTestCondition.NORMAL, true, "12"),
            record(2, PrinterStatusTestCondition.NORMAL, true, "16"),
            record(3, PrinterStatusTestCondition.PAPER_OUT, true, "12"),
            record(4, PrinterStatusTestCondition.PAPER_OUT, true, "12"),
        )

        val candidate = PrinterStatusProbeAnalysisPolicy.analyzeCondition(
            records,
            PrinterStatusTestCondition.PAPER_OUT,
        )

        assertTrue(candidate.stableChanges.isEmpty())
        assertEquals(1, candidate.unstableBitCount)
    }

    @Test
    fun responseLengthMismatchStopsBitCalculation() {
        val candidate = PrinterStatusProbeAnalysisPolicy.analyzeCondition(
            listOf(
                record(1, PrinterStatusTestCondition.NORMAL, true, "12 12 12 12"),
                record(2, PrinterStatusTestCondition.CUTTER_ERROR, true, "12 12"),
            ),
            PrinterStatusTestCondition.CUTTER_ERROR,
        )

        assertTrue(candidate.sizeMismatch)
        assertTrue(candidate.stableChanges.isEmpty())
        assertTrue(candidate.note.contains("応答長"))
    }

    @Test
    fun recordsAreSeparatedByModelModeHostAndPreset() {
        val records = listOf(
            record(1, PrinterStatusTestCondition.NORMAL, true, "12", model = "TM-m30II"),
            record(2, PrinterStatusTestCondition.NORMAL, true, "12", model = "TM-T88VII"),
            record(3, PrinterStatusTestCondition.NORMAL, true, "12", model = "TM-m30II", host = "10.0.0.20"),
        )

        assertEquals(3, PrinterStatusProbeAnalysisPolicy.analyze(records).size)
    }

    @Test
    fun analysisCsvContainsCoverageCandidateAndSafetyWarning() {
        val analysis = PrinterStatusProbeAnalysisPolicy.analyze(
            listOf(
                record(1, PrinterStatusTestCondition.NORMAL, true, "12 12"),
                record(2, PrinterStatusTestCondition.COVER_OPEN, true, "12 16"),
            ),
        ).single()

        val csv = PrinterStatusProbeAnalysisCsv.render(analysis)

        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("試験条件,総数,成功,失敗"))
        assertTrue(csv.contains("カバー開,1,1,0,成功あり"))
        assertTrue(csv.contains("0x04,0,1"))
        assertTrue(csv.contains("確認完了を意味しません"))
    }

    private fun record(
        id: Long,
        condition: PrinterStatusTestCondition,
        success: Boolean,
        responseHex: String,
        model: String = "TM-m30II",
        host: String = "10.0.0.10",
    ) = PrinterStatusProbeHistoryRecord(
        id = id,
        startedAt = 1_000L + id,
        profile = PrinterProfile.EPSON_TM_JAPAN,
        preset = PrinterStatusProbePreset.EPSON_DLE_EOT_BATCH,
        verification = PrinterStatusVerification.VENDOR_DOCUMENTED,
        host = host,
        port = 9100,
        elapsedMillis = 10L,
        requestHex = "10 04 01",
        responseHex = responseHex,
        responseAscii = "",
        responseSize = PrinterStatusProbeComparisonPolicy.parseHex(responseHex).size,
        success = success,
        parsedLevel = null,
        parsedSummary = null,
        protocolValid = null,
        errorMessage = if (success) null else "timeout",
        actor = "test",
        createdAt = 2_000L + id,
        condition = condition,
        printerModel = model,
        emulationMode = "EPSON ESC/POS",
        memo = "",
    )
}
