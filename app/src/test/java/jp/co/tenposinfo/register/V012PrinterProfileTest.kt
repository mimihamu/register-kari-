package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterProfileTest {
    @Test
    fun drawerPulseUsesSelectedPortAndTwoMillisecondUnits() {
        assertArrayEquals(
            byteArrayOf(0x1B, 0x70, 0x01, 0x32, 0xFA.toByte()),
            PrinterPulsePolicy.command(port = 1, onMillis = 100, offMillis = 500),
        )
    }

    @Test
    fun textEncodingUsesSelectedProfileAndPartialCut() {
        val config = PrinterConfiguration(
            profile = PrinterProfile.EPSON_TM_JAPAN,
            cutMode = PrinterCutMode.PARTIAL,
        )
        val payload = PrinterCommandEncoder.encodeText("印字テスト", config)
        assertTrue(payload.startsWith(byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x01)))
        assertTrue(payload.endsWith(byteArrayOf(0x1D, 0x56, 0x42, 0x00)))
    }

    @Test
    fun noCutProfileDoesNotAppendCutCommand() {
        val config = PrinterConfiguration(cutMode = PrinterCutMode.NONE)
        val payload = PrinterCommandEncoder.encodeText("TEST", config)
        assertFalse(payload.containsSequence(byteArrayOf(0x1D, 0x56, 0x42, 0x00)))
        assertFalse(payload.containsSequence(byteArrayOf(0x1D, 0x56, 0x41, 0x00)))
    }

    @Test
    fun drawerCommandIsOnlyIncludedWhenRequestedAndEnabled() {
        val enabled = PrinterConfiguration(
            drawerEnabled = true,
            drawerPort = 0,
            drawerOnMillis = 100,
            drawerOffMillis = 500,
        )
        val disabled = enabled.copy(drawerEnabled = false)
        val commandPrefix = byteArrayOf(0x1B, 0x70, 0x00)

        assertTrue(
            PrinterCommandEncoder.encodeText("CASH", enabled, openDrawer = true)
                .containsSequence(commandPrefix),
        )
        assertFalse(
            PrinterCommandEncoder.encodeText("CASH", enabled, openDrawer = false)
                .containsSequence(commandPrefix),
        )
        assertFalse(
            PrinterCommandEncoder.encodeText("CASH", disabled, openDrawer = true)
                .containsSequence(commandPrefix),
        )
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) return false
    val offset = size - suffix.size
    return suffix.indices.all { this[offset + it] == suffix[it] }
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    if (sequence.isEmpty() || sequence.size > size) return false
    return (0..size - sequence.size).any { start ->
        sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
    }
}
