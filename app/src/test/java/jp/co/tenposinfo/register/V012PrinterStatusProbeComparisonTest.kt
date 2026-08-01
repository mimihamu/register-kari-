package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusProbeComparisonTest {
    @Test
    fun selectionIsDistinctAndLimitedToFour() {
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            PrinterStatusProbeComparisonPolicy.normalizeSelection(listOf(1, 1, 2, 3, 4, 5).map(Int::toLong)),
        )
    }

    @Test
    fun comparisonReportsChangedPositionsAndLengthDifference() {
        val records = listOf(
            record(id = 1, responseHex = "12 12 12 12"),
            record(id = 2, responseHex = "12 16 12"),
        )

        val comparison = PrinterStatusProbeComparisonPolicy.compare(records).single()

        assertFalse(comparison.sameResponse)
        assertEquals(4, comparison.baseSize)
        assertEquals(3, comparison.comparedSize)
        assertEquals(listOf(1, 3), comparison.changedPositions)
        assertEquals(2, comparison.differentByteCount)
    }

    @Test
    fun identicalResponsesHaveNoDifferences() {
        val comparison = PrinterStatusProbeComparisonPolicy.compare(
            listOf(
                record(id = 1, responseHex = "12 12"),
                record(id = 2, responseHex = "12 12"),
            ),
        ).single()

        assertTrue(comparison.sameResponse)
        assertEquals("差分なし", PrinterStatusProbeComparisonPolicy.changedPositionLabel(comparison))
    }

    @Test
    fun multiCsvContainsRawDataConditionModelMemoAndComparisonSection() {
        val csv = PrinterStatusProbeMultiCsv.render(
            listOf(
                record(
                    id = 10,
                    responseHex = "12 12 12 12",
                    condition = PrinterStatusTestCondition.NORMAL,
                ),
                record(
                    id = 11,
                    responseHex = "12 12 16 12",
                    condition = PrinterStatusTestCondition.COVER_OPEN,
                ),
            ),
        )

        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("試験条件"))
        assertTrue(csv.contains("プリンター実機型番"))
        assertTrue(csv.contains("STAR mC-Print3"))
        assertTrue(csv.contains("Star ESC/POS"))
        assertTrue(csv.contains("カバーを開けて採取"))
        assertTrue(csv.contains("受信HEX"))
        assertTrue(csv.contains("12 12 16 12"))
        assertTrue(csv.contains("比較基準ID"))
        assertTrue(csv.contains("10,正常,11,カバー開,差分あり"))
    }

    private fun record(
        id: Long,
        responseHex: String,
        condition: PrinterStatusTestCondition = PrinterStatusTestCondition.NORMAL,
    ) = PrinterStatusProbeHistoryRecord(
        id = id,
        startedAt = 1_000L + id,
        profile = PrinterProfile.STAR_ESC_POS,
        preset = PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH,
        verification = PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY,
        host = "192.168.1.10",
        port = 9100,
        elapsedMillis = 10L,
        requestHex = "10 04 01",
        responseHex = responseHex,
        responseAscii = "....",
        responseSize = PrinterStatusProbeComparisonPolicy.parseHex(responseHex).size,
        success = true,
        parsedLevel = null,
        parsedSummary = null,
        protocolValid = null,
        errorMessage = null,
        actor = "test",
        createdAt = 2_000L + id,
        condition = condition,
        printerModel = "STAR mC-Print3",
        emulationMode = "Star ESC/POS",
        memo = if (condition == PrinterStatusTestCondition.COVER_OPEN) "カバーを開けて採取" else "正常状態",
        annotatedAt = 3_000L + id,
        annotatedBy = "tester",
    )
}
