package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrinterProfileContractTest {
    @Test
    fun formalProfileSnapshotContainsAllRequiredFieldsFor58mm() {
        val configuration = PrinterConfiguration(
            name = "厨房前レシート",
            host = "10.0.0.20",
            port = 9100,
            paperWidthMm = 58,
            profile = PrinterProfile.EPSON_TM_JAPAN,
            cutMode = PrinterCutMode.PARTIAL,
            drawerEnabled = true,
            drawerPort = 1,
        )

        val snapshot = PrinterProfileContractV136.snapshot(configuration)

        assertEquals("printer-1", snapshot.printerId)
        assertEquals("厨房前レシート", snapshot.name)
        assertEquals(PrinterConnectionTypeV136.TCP_9100, snapshot.connectionType)
        assertEquals("10.0.0.20:9100", snapshot.address)
        assertEquals(58, snapshot.paperWidthMm)
        assertEquals(384, snapshot.printableDotWidth)
        assertEquals(32, snapshot.logicalColumns)
        assertEquals("Shift_JIS", snapshot.encoding)
        assertTrue(snapshot.supportsCut)
        assertEquals(PrinterCutMode.PARTIAL, snapshot.cutMode)
        assertEquals(5, snapshot.feedLines)
        assertEquals(1, snapshot.drawerPort)
        assertEquals(PrinterStatusProtocol.EPSON_DLE_EOT, snapshot.statusCapability)
        assertTrue(PrinterProfileContractV136.isInternallyConsistent(snapshot))
    }

    @Test
    fun formalProfileSnapshotUses80mmStandardGeometry() {
        val snapshot = PrinterProfileContractV136.snapshot(
            PrinterConfiguration(
                host = "printer.local",
                paperWidthMm = 80,
                profile = PrinterProfile.GENERIC_ESC_POS,
            ),
        )

        assertEquals(80, snapshot.paperWidthMm)
        assertEquals(576, snapshot.printableDotWidth)
        assertEquals(48, snapshot.logicalColumns)
        assertEquals("MS932", snapshot.encoding)
        assertTrue(PrinterProfileContractV136.isInternallyConsistent(snapshot))
    }

    @Test
    fun cutDisabledAndDrawerDisabledAreRepresentedExplicitly() {
        val snapshot = PrinterProfileContractV136.snapshot(
            PrinterConfiguration(
                host = "10.0.0.30",
                cutMode = PrinterCutMode.NONE,
                drawerEnabled = false,
            ),
        )

        assertFalse(snapshot.supportsCut)
        assertEquals(PrinterCutMode.NONE, snapshot.cutMode)
        assertNull(snapshot.drawerPort)
        assertTrue(PrinterProfileContractV136.isInternallyConsistent(snapshot))
    }

    @Test
    fun unconfiguredEndpointCanStillBeRepresentedWithoutInventingAddress() {
        val snapshot = PrinterProfileContractV136.snapshot(PrinterConfiguration())
        assertEquals("", snapshot.address)
        assertTrue(PrinterProfileContractV136.isInternallyConsistent(snapshot))
    }
}
