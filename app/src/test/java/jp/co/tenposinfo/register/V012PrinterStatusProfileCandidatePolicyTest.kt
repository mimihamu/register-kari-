package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusProfileCandidatePolicyTest {
    @Test
    fun reviewableEvidenceCanCreateCandidate() {
        val report = report(sourceIds = listOf(10L, 11L, 12L))

        assertTrue(PrinterStatusProfileCandidatePolicy.canCreate(report))
        assertEquals(null, PrinterStatusProfileCandidatePolicy.creationError(report))
    }

    @Test
    fun unreviewableOrUnsavedEvidenceCannotCreateCandidate() {
        val unreviewable = report(sourceIds = listOf(10L, 11L)).copy(
            evidenceReadyForReview = false,
            blockers = listOf("サンプル不足"),
        )
        val unsaved = report(sourceIds = listOf(0L, 11L))

        assertFalse(PrinterStatusProfileCandidatePolicy.canCreate(unreviewable))
        assertTrue(PrinterStatusProfileCandidatePolicy.creationError(unreviewable)!!.contains("サンプル不足"))
        assertFalse(PrinterStatusProfileCandidatePolicy.canCreate(unsaved))
        assertTrue(PrinterStatusProfileCandidatePolicy.creationError(unsaved)!!.contains("未保存"))
    }

    @Test
    fun sameEvidenceProducesSameKeyAndDifferentEvidenceProducesDifferentKey() {
        val first = report(sourceIds = listOf(12L, 10L, 11L))
        val reordered = report(sourceIds = listOf(11L, 12L, 10L))
        val different = report(sourceIds = listOf(10L, 11L, 13L))

        assertEquals(
            PrinterStatusProfileCandidatePolicy.evidenceKey(first),
            PrinterStatusProfileCandidatePolicy.evidenceKey(reordered),
        )
        assertNotEquals(
            PrinterStatusProfileCandidatePolicy.evidenceKey(first),
            PrinterStatusProfileCandidatePolicy.evidenceKey(different),
        )
    }

    @Test
    fun approvalAndRejectionRequireReason() {
        runCatching { PrinterStatusProfileCandidatePolicy.requireReviewNote("   ") }
            .onSuccess { throw AssertionError("blank reason must fail") }
        assertEquals(
            "実機証跡を確認したため",
            PrinterStatusProfileCandidatePolicy.requireReviewNote("  実機証跡を確認したため  "),
        )
    }

    @Test
    fun approvalNeverMarksRuntimeApplied() {
        assertFalse(PrinterStatusProfileCandidatePolicy.runtimeAppliedAfterReview())
        val record = PrinterStatusProfileCandidateRecord(
            id = 1,
            createdAt = 1,
            profile = PrinterProfile.EPSON_TM_JAPAN,
            preset = PrinterStatusProbePreset.TCP_CONNECT_ONLY,
            host = "192.0.2.10",
            port = 9100,
            printerModel = "TM-m30II",
            emulationMode = "EPSON ESC/POS",
            confidence = PrinterEvidenceConfidence.HIGH,
            sourceRecordIds = "10,11,12",
            stableChangeCount = 1,
            evidenceKey = "abc",
            analysisSnapshot = "snapshot",
            payloadCsv = "\uFEFFcsv",
            status = PrinterStatusProfileCandidateStatus.APPROVED,
            createdBy = "creator",
            reviewedAt = 2,
            reviewedBy = "reviewer",
            reviewNote = "証跡確認",
            runtimeApplied = PrinterStatusProfileCandidatePolicy.runtimeAppliedAfterReview(),
        )

        assertEquals(PrinterStatusProfileCandidateStatus.APPROVED, record.status)
        assertFalse(record.runtimeApplied)
    }

    @Test
    fun snapshotContainsSourceAndNoRuntimeApplicationClaim() {
        val report = report(sourceIds = listOf(10L, 11L, 12L))
        val snapshot = PrinterStatusProfileCandidatePolicy.analysisSnapshot(report)

        assertTrue(snapshot.contains("sourceRecordIds=10,11,12"))
        assertTrue(snapshot.contains("confidence=MEDIUM"))
        assertTrue(snapshot.contains("stableChangeCount=1"))
    }

    private fun report(sourceIds: List<Long>): PrinterStatusValidationReport {
        val records = sourceIds.map { id ->
            PrinterStatusProbeHistoryRecord(
                id = id,
                startedAt = id,
                profile = PrinterProfile.EPSON_TM_JAPAN,
                preset = PrinterStatusProbePreset.TCP_CONNECT_ONLY,
                verification = PrinterStatusVerification.VENDOR_DOCUMENTED,
                host = "192.0.2.10",
                port = 9100,
                elapsedMillis = 10,
                requestHex = "",
                responseHex = "12 12 12 12",
                responseAscii = "",
                responseSize = 4,
                success = true,
                parsedLevel = null,
                parsedSummary = null,
                protocolValid = true,
                errorMessage = null,
                actor = "tester",
                createdAt = id,
                condition = PrinterStatusTestCondition.NORMAL,
                printerModel = "TM-m30II",
                emulationMode = "EPSON ESC/POS",
                memo = "",
                annotatedAt = id,
                annotatedBy = "tester",
            )
        }
        val key = PrinterStatusProbeDeviceKey(
            profile = PrinterProfile.EPSON_TM_JAPAN,
            preset = PrinterStatusProbePreset.TCP_CONNECT_ONLY,
            host = "192.0.2.10",
            port = 9100,
            printerModel = "TM-m30II",
            emulationMode = "EPSON ESC/POS",
        )
        val stableChange = PrinterStatusStableBitChange(
            byteIndex = 1,
            bitIndex = 2,
            normalValue = 0,
            conditionValue = 1,
        )
        val bitCandidate = PrinterStatusConditionBitCandidate(
            condition = PrinterStatusTestCondition.COVER_OPEN,
            normalSampleCount = 3,
            conditionSampleCount = 3,
            responseSize = 4,
            stableChanges = listOf(stableChange),
            unstableBitCount = 0,
            sizeMismatch = false,
            note = "stable",
        )
        val analysis = PrinterStatusDeviceAnalysis(
            key = key,
            records = records,
            progress = emptyList(),
            candidates = listOf(bitCandidate),
        )
        val evidence = listOf(
            PrinterStatusConditionEvidence(
                condition = PrinterStatusTestCondition.COVER_OPEN,
                expectation = PrinterEvidenceExpectation.RESPONSE_PATTERN,
                totalCount = 3,
                successCount = 3,
                failureCount = 0,
                cluster = PrinterStatusResponseCluster(
                    condition = PrinterStatusTestCondition.COVER_OPEN,
                    validSampleCount = 3,
                    distinctResponseCount = 1,
                    dominantResponseHex = "12 16 12 12",
                    dominantCount = 3,
                    outlierCount = 0,
                    agreementRate = 1.0,
                    responseLengths = listOf(4),
                    sourceRecordIds = sourceIds.sorted(),
                ),
                candidate = bitCandidate,
                confidence = PrinterEvidenceConfidence.MEDIUM,
                ready = true,
                reason = "成立",
                sourceRecordIds = sourceIds.sorted(),
            ),
        )
        return PrinterStatusValidationReport(
            analysis = analysis,
            evidence = evidence,
            overallConfidence = PrinterEvidenceConfidence.MEDIUM,
            evidenceReadyForReview = true,
            blockers = emptyList(),
        )
    }
}
