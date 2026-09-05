package jp.co.tenposinfo.register

import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrinterProfilePrintabilityTest {
    @Test
    fun formalTestMatrixContainsRequiredCases() {
        val text = PrinterProfilePrintabilityV136.diagnosticText()

        listOf(
            "全角：つぐレジ 印刷確認",
            "半角/英数字: ABC abc 1234567890",
            "記号:",
            "最大桁金額: ¥9,999,999,999",
            "10%対象:",
            "8%対象※:",
            "非課税:",
            "値引: -¥100",
            "負数: -¥9,999,999",
            "QR: ${PrinterProfilePrintabilityV136.QR_PAYLOAD}",
        ).forEach { required -> assertTrue("missing $required", text.contains(required)) }
    }

    @Test
    fun diagnosticQrBecomesRealEscPosGsKSequenceBeforeCut() {
        val configuration = PrinterConfiguration(
            host = "10.0.0.50",
            paperWidthMm = 80,
            printableDotWidth = 576,
            feedLines = 5,
            cutMode = PrinterCutMode.PARTIAL,
        )
        val text = PrinterPaperWidthTestV136.buildAll(ReceiptPaper.MM80, "2026-08-23T00:00:00Z")
        val payload = PrinterCommandEncoder.encodeText(text, configuration, appendCut = true)

        val qrStorePrefix = byteArrayOf(0x1D, 0x28, 0x6B, 0x19, 0x00, 0x31, 0x50, 0x30)
        val qrPrint = byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)
        val partialCut = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

        assertTrue(payload.containsSequence(qrStorePrefix))
        assertTrue(payload.containsSequence(PrinterProfilePrintabilityV136.QR_PAYLOAD.toByteArray(Charsets.US_ASCII)))
        assertTrue(payload.containsSequence(qrPrint))
        assertTrue(payload.takeLast(partialCut.size).toByteArray().contentEquals(partialCut))
    }

    @Test
    fun controlSequenceSurvivesSupportedPrinterCharsets() {
        val raw = PrinterProfilePrintabilityV136.qrControlSequence(PrinterProfilePrintabilityV136.QR_PAYLOAD)
        PrinterProfile.entries.forEach { profile ->
            val encoded = raw.toByteArray(Charset.forName(profile.charsetName))
            assertTrue(encoded.containsSequence(byteArrayOf(0x1D, 0x28, 0x6B)))
            assertTrue(encoded.containsSequence(PrinterProfilePrintabilityV136.QR_PAYLOAD.toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun softwarePreflightValidatesEndpointProfileAndGeometry() {
        val valid = PrinterConfiguration(
            host = "192.0.2.10",
            port = 9100,
            paperWidthMm = 58,
            printableDotWidth = 384,
            feedLines = 5,
        )
        PrinterProfilePrintabilityV136.validateSoftwarePreflight(valid)

        val missingHost = runCatching {
            PrinterProfilePrintabilityV136.validateSoftwarePreflight(valid.copy(host = ""))
        }
        assertTrue(missingHost.isFailure)
    }

    @Test
    fun existingPrinterTestUsesSelectedProfileSameEncoderAndTcpGateway() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt").readText()
        assertTrue(source.contains("configuration.profile.displayName"))
        assertTrue(source.contains("configuration.printableDotWidth"))
        assertTrue(source.contains("configuration.feedLines"))
        assertTrue(source.contains("PrinterPaperWidthTestV136.buildAll"))
        assertTrue(source.contains("PrinterCommandEncoder.encodeText"))
        assertTrue(source.contains("printerGateway(configuration).send(payload)"))
        assertTrue(source.contains("PRINTER_TEST_SUCCEEDED"))
        assertTrue(source.contains("PRINTER_TEST_FAILED"))
        assertFalse(source.contains("実機印字確認済み"))
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        for (start in 0..size - sequence.size) {
            var matches = true
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
