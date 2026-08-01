package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusValidationFinalizationTest {
    @Test
    fun reproducibilityAndOutlierExtraction() {
        val records = listOf(
            successRecord(1, PrinterStatusTestCondition.NORMAL, "12 12 12 12"),
            successRecord(2, PrinterStatusTestCondition.NORMAL, "12 12 12 12"),
            successRecord(3, PrinterStatusTestCondition.NORMAL, "12 12 12 12"),
            successRecord(4, PrinterStatusTestCondition.NORMAL, "12 12 12 10"),
        )

        val cluster = PrinterStatusValidationPolicy.responseCluster(
            records,
            PrinterStatusTestCondition.NORMAL,
        )

        assertEquals(4, cluster.validSampleCount)
        assertEquals(3, cluster.dominantCount)
        assertEquals(1, cluster.outlierCount)
        assertEquals(listOf(4L), cluster.outlierRecordIds)
        assertEquals(75, cluster.agreementPercent)
        assertEquals("12 12 12 12", cluster.dominantResponseHex)
    }

    @Test
    fun singleSampleNeverBecomesHighConfidence() {
        val analysis = analysisOf(
            listOf(successRecord(1, PrinterStatusTestCondition.NORMAL, "12 12 12 12")),
        )
        val report = PrinterStatusValidationPolicy.build(analysis)
        val normal = report.evidence.single { it.condition == PrinterStatusTestCondition.NORMAL }

        assertEquals(PrinterEvidenceConfidence.LOW, normal.confidence)
        assertFalse(normal.ready)
        assertEquals(PrinterEvidenceConfidence.NOT_READY, report.overallConfidence)
    }

    @Test
    fun threeStableSamplesProduceMediumEvidenceAndFiveProduceHigh() {
        val medium = PrinterStatusValidationPolicy.build(analysisOf(completeEvidence(sampleCount = 3, failureCount = 3)))
        val high = PrinterStatusValidationPolicy.build(analysisOf(completeEvidence(sampleCount = 5, failureCount = 5)))

        assertTrue(medium.evidenceReadyForReview)
        assertEquals(PrinterEvidenceConfidence.MEDIUM, medium.overallConfidence)
        assertTrue(high.evidenceReadyForReview)
        assertEquals(PrinterEvidenceConfidence.HIGH, high.overallConfidence)
    }

    @Test
    fun responseLengthMismatchBlocksReview() {
        val records = completeEvidence(sampleCount = 3, failureCount = 3).toMutableList()
        val index = records.indexOfFirst { it.condition == PrinterStatusTestCondition.COVER_OPEN }
        records[index] = records[index].copy(responseHex = "12 16 12", responseSize = 3)

        val report = PrinterStatusValidationPolicy.build(analysisOf(records))
        val cover = report.evidence.single { it.condition == PrinterStatusTestCondition.COVER_OPEN }

        assertTrue(cover.cluster?.responseLengthMismatch == true || cover.candidate?.sizeMismatch == true)
        assertFalse(cover.ready)
        assertFalse(report.evidenceReadyForReview)
        assertTrue(report.blockers.any { it.contains("応答長") })
    }

    @Test
    fun powerOffAndLanDisconnectRequireRepeatedFailures() {
        val insufficient = PrinterStatusValidationPolicy.build(
            analysisOf(completeEvidence(sampleCount = 3, failureCount = 1)),
        )
        val sufficient = PrinterStatusValidationPolicy.build(
            analysisOf(completeEvidence(sampleCount = 3, failureCount = 2)),
        )

        assertFalse(insufficient.evidence.single {
            it.condition == PrinterStatusTestCondition.POWER_OFF
        }.ready)
        assertFalse(insufficient.evidence.single {
            it.condition == PrinterStatusTestCondition.LAN_DISCONNECTED
        }.ready)
        assertTrue(sufficient.evidence.single {
            it.condition == PrinterStatusTestCondition.POWER_OFF
        }.ready)
        assertTrue(sufficient.evidence.single {
            it.condition == PrinterStatusTestCondition.LAN_DISCONNECTED
        }.ready)
    }

    @Test
    fun successfulCommunicationDuringFailureConditionBlocksReview() {
        val records = completeEvidence(sampleCount = 3, failureCount = 2).toMutableList()
        records += successRecord(999, PrinterStatusTestCondition.POWER_OFF, "12 12 12 12")

        val report = PrinterStatusValidationPolicy.build(analysisOf(records))
        val powerOff = report.evidence.single { it.condition == PrinterStatusTestCondition.POWER_OFF }

        assertFalse(powerOff.ready)
        assertTrue(powerOff.reason.contains("成功記録"))
        assertFalse(report.evidenceReadyForReview)
    }

    @Test
    fun finalCsvHasBomSafetyNoticeAndSourceHistoryIds() {
        val report = PrinterStatusValidationPolicy.build(
            analysisOf(completeEvidence(sampleCount = 3, failureCount = 2)),
        )

        val csv = PrinterStatusValidationCsv.render(report)

        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("元履歴ID"))
        assertTrue(csv.contains(report.sourceRecordIds.first().toString()))
        assertTrue(csv.contains("CI成功は実機確認済みを意味しません"))
        assertTrue(csv.contains("STAR／汎用DLE EOT互換をメーカー仕様確認済みとは表現しません"))
        assertTrue(csv.contains("自動適用しません"))
    }

    private fun completeEvidence(sampleCount: Int, failureCount: Int): List<PrinterStatusProbeHistoryRecord> {
        var id = 1L
        val result = mutableListOf<PrinterStatusProbeHistoryRecord>()
        val responses = mapOf(
            PrinterStatusTestCondition.NORMAL to "12 12 12 12",
            PrinterStatusTestCondition.COVER_OPEN to "12 16 12 12",
            PrinterStatusTestCondition.PAPER_NEAR_END to "12 12 12 1E",
            PrinterStatusTestCondition.PAPER_OUT to "12 12 12 72",
            PrinterStatusTestCondition.CUTTER_ERROR to "12 12 1A 12",
        )
        responses.forEach { (condition, hex) ->
            kotlin.repeat(sampleCount) { result += successRecord(id++, condition, hex) }
        }
        kotlin.repeat(failureCount) { result += failureRecord(id++, PrinterStatusTestCondition.POWER_OFF) }
        kotlin.repeat(failureCount) { result += failureRecord(id++, PrinterStatusTestCondition.LAN_DISCONNECTED) }
        return result
    }

    private fun analysisOf(records: List<PrinterStatusProbeHistoryRecord>): PrinterStatusDeviceAnalysis {
        val key = PrinterStatusProbeDeviceKey(
            profile = PrinterProfile.EPSON_TM_JAPAN,
            preset = PrinterStatusProbePreset.TCP_CONNECT_ONLY,
            host = "192.0.2.10",
            port = 9100,
            printerModel = "TM-m30II",
            emulationMode = "EPSON ESC/POS",
        )
        return PrinterStatusProbeAnalysisPolicy.analyzeDevice(key, records)
    }

    private fun successRecord(
        id: Long,
        condition: PrinterStatusTestCondition,
        hex: String,
    ): PrinterStatusProbeHistoryRecord = record(
        id = id,
        condition = condition,
        success = true,
        responseHex = hex,
    )

    private fun failureRecord(
        id: Long,
        condition: PrinterStatusTestCondition,
    ): PrinterStatusProbeHistoryRecord = record(
        id = id,
        condition = condition,
        success = false,
        responseHex = "",
    )

    private fun record(
        id: Long,
        condition: PrinterStatusTestCondition,
        success: Boolean,
        responseHex: String,
    ): PrinterStatusProbeHistoryRecord = PrinterStatusProbeHistoryRecord(
        id = id,
        startedAt = 1_700_000_000_000L + id,
        profile = PrinterProfile.EPSON_TM_JAPAN,
        preset = PrinterStatusProbePreset.TCP_CONNECT_ONLY,
        verification = PrinterStatusVerification.VENDOR_DOCUMENTED,
        host = "192.0.2.10",
        port = 9100,
        elapsedMillis = 10,
        requestHex = "",
        responseHex = responseHex,
        responseAscii = "",
        responseSize = PrinterStatusProbeComparisonPolicy.parseHex(responseHex).size,
        success = success,
        parsedLevel = null,
        parsedSummary = null,
        protocolValid = null,
        errorMessage = if (success) null else "connection failed",
        actor = "tester",
        createdAt = 1_700_000_000_000L + id,
        condition = condition,
        printerModel = "TM-m30II",
        emulationMode = "EPSON ESC/POS",
        memo = "",
        annotatedAt = 1_700_000_000_000L + id,
        annotatedBy = "tester",
    )
}
