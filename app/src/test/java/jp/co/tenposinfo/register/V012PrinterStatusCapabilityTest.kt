package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusCapabilityTest {
    @Test
    fun epsonAllowsAutomaticMonitoringPreflightAndSoakTest() {
        val capability = PrinterStatusCapabilityRegistry.forProfile(PrinterProfile.EPSON_TM_JAPAN)

        assertEquals(PrinterStatusVerification.VENDOR_DOCUMENTED, capability.verification)
        assertTrue(capability.automaticQueryAllowed)
        assertTrue(capability.soakTestAllowed)
        assertEquals(
            PrinterStatusCheckDecision.ALLOWED,
            capability.decision(PrinterStatusCheckPurpose.AUTOMATIC_PREFLIGHT),
        )
        assertEquals(
            PrinterStatusCheckDecision.ALLOWED,
            capability.decision(PrinterStatusCheckPurpose.SOAK_TEST),
        )
    }

    @Test
    fun starManualQueryRequiresExplicitConfirmationAndAutomaticChecksAreDenied() {
        val capability = PrinterStatusCapabilityRegistry.forProfile(PrinterProfile.STAR_ESC_POS)

        assertEquals(PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY, capability.verification)
        assertFalse(capability.automaticQueryAllowed)
        assertFalse(capability.soakTestAllowed)
        assertEquals(
            PrinterStatusCheckDecision.REQUIRES_EXPLICIT_CONFIRMATION,
            capability.decision(PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC),
        )
        assertEquals(
            PrinterStatusCheckDecision.ALLOWED,
            capability.decision(
                PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC,
                experimentalConfirmed = true,
            ),
        )
        assertEquals(
            PrinterStatusCheckDecision.DENIED,
            capability.decision(PrinterStatusCheckPurpose.SALES_MONITORING),
        )
        assertEquals(
            PrinterStatusCheckDecision.DENIED,
            capability.decision(PrinterStatusCheckPurpose.SAFE_PRINT),
        )
    }

    @Test
    fun genericStatusIsAlsoExperimentalAndCannotDriveContinuousTest() {
        val capability = PrinterStatusCapabilityRegistry.forProfile(PrinterProfile.GENERIC_ESC_POS)

        assertEquals(PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY, capability.verification)
        assertEquals(
            PrinterStatusCheckDecision.DENIED,
            capability.decision(PrinterStatusCheckPurpose.SOAK_TEST),
        )
        assertTrue(
            PrinterStatusCapabilityRegistry.denialMessage(
                PrinterProfile.GENERIC_ESC_POS,
                PrinterStatusCheckPurpose.SOAK_TEST,
            ).contains("連続印刷試験"),
        )
    }
}
