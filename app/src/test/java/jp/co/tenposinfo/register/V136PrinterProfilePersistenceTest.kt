package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrinterProfilePersistenceTest {
    private fun source(name: String) =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun defaultsFollowFormal58And80Geometry() {
        val mm58 = PrinterConfiguration(paperWidthMm = 58)
        val mm80 = PrinterConfiguration(paperWidthMm = 80)

        assertEquals(384, mm58.printableDotWidth)
        assertEquals(576, mm80.printableDotWidth)
        assertEquals(5, mm58.feedLines)
        assertEquals(5, mm80.feedLines)
    }

    @Test
    fun snapshotUsesPersistedDotWidthAndFeedLines() {
        val configuration = PrinterConfiguration(
            host = "10.0.0.50",
            paperWidthMm = 58,
            printableDotWidth = 384,
            feedLines = 7,
        )

        PrinterProfileContractV136.validatePersistedConfiguration(configuration)
        val snapshot = PrinterProfileContractV136.snapshot(configuration)

        assertEquals(384, snapshot.printableDotWidth)
        assertEquals(7, snapshot.feedLines)
        assertTrue(PrinterProfileContractV136.isInternallyConsistent(snapshot))
    }

    @Test
    fun paperAndDotWidthMismatchIsRejected() {
        val invalid = PrinterConfiguration(
            paperWidthMm = 58,
            printableDotWidth = 576,
        )

        val result = runCatching { PrinterProfileContractV136.validatePersistedConfiguration(invalid) }
        assertTrue(result.isFailure)
    }

    @Test
    fun feedLinesOutsideFormalRangeAreRejected() {
        for (feed in listOf(2, 8)) {
            val invalid = PrinterConfiguration(feedLines = feed)
            val result = runCatching { PrinterProfileContractV136.validatePersistedConfiguration(invalid) }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun printerCommandEncoderUsesConfiguredFeedLinesBeforeCut() {
        val configuration = PrinterConfiguration(
            cutMode = PrinterCutMode.NONE,
            feedLines = 7,
        )
        val payload = PrinterCommandEncoder.encodeText(
            text = "X",
            configuration = configuration,
            appendCut = true,
        )

        var trailingLf = 0
        for (index in payload.indices.reversed()) {
            if (payload[index] == 0x0A.toByte()) trailingLf++ else break
        }
        assertEquals(7, trailingLf)
    }

    @Test
    fun appendCutFalseDoesNotAppendFeedLines() {
        val configuration = PrinterConfiguration(
            cutMode = PrinterCutMode.NONE,
            feedLines = 7,
        )
        val payload = PrinterCommandEncoder.encodeText(
            text = "X",
            configuration = configuration,
            appendCut = false,
        )

        assertFalse(payload.last() == 0x0A.toByte())
    }

    @Test
    fun printerSettingsSchemaLoadSaveMigrationAndUiContainFormalFields() {
        val store = source("AdminSettingsStore.kt")
        val ui = source("AdminSettingsActivity.kt")

        assertTrue(store.contains("printable_dot_width INTEGER NOT NULL DEFAULT 576"))
        assertTrue(store.contains("feed_lines INTEGER NOT NULL DEFAULT 5"))
        assertTrue(store.contains("ensurePrinterColumn(\"printable_dot_width\", \"INTEGER NOT NULL DEFAULT 0\")"))
        assertTrue(store.contains("ensurePrinterColumn(\"feed_lines\", \"INTEGER NOT NULL DEFAULT 5\")"))
        assertTrue(store.contains("put(\"printable_dot_width\", configuration.printableDotWidth)"))
        assertTrue(store.contains("put(\"feed_lines\", configuration.feedLines)"))
        assertTrue(store.contains("printableDotWidth = cursor.getInt(4)"))
        assertTrue(store.contains("feedLines = cursor.getInt(5)"))
        assertTrue(store.contains("SET printable_dot_width = CASE paper_width_mm"))
        assertTrue(ui.contains("印字可能幅 dot（用紙幅連動）"))
        assertTrue(ui.contains("カット前紙送り行数"))
        assertTrue(ui.contains("store.loadPrinterConfiguration()"))
        assertTrue(ui.contains("保存し、再読込を確認しました"))
    }
}
