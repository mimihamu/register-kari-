package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V135ManualRefundFallbackTest {
    @Test
    fun positiveOriginalPaymentBreakdownKeepsAutomaticAllocationPath() {
        assertFalse(
            ManualRefundFallbackPolicyV135.requiresManualSelection(
                listOf(PaymentTotal(PaymentMethod.CASH.name, 500)),
            ),
        )
    }

    @Test
    fun missingOrZeroOriginalPaymentBreakdownRequiresManagerSelection() {
        assertTrue(ManualRefundFallbackPolicyV135.requiresManualSelection(emptyList()))
        assertTrue(
            ManualRefundFallbackPolicyV135.requiresManualSelection(
                listOf(PaymentTotal(PaymentMethod.CARD.name, 0)),
            ),
        )
    }

    @Test
    fun durableSelectionMustMatchRefundAmountAndSupportedMethod() {
        val valid = SelectedManualRefundV135("req", 1_000, PaymentMethod.CASH.name, 1L)
        val wrongAmount = valid.copy(refundTotal = 999)
        val unsupported = valid.copy(method = "UNKNOWN")

        assertEquals(PaymentMethod.CASH.name, ManualRefundFallbackPolicyV135.validSelectedMethod(1_000, valid))
        assertNull(ManualRefundFallbackPolicyV135.validSelectedMethod(1_000, wrongAmount))
        assertNull(ManualRefundFallbackPolicyV135.validSelectedMethod(1_000, unsupported))
    }

    @Test
    fun legacyPureAllocationFallbackRemainsAvailableOutsideApprovedRuntimeContext() {
        val result = PartialReturnPolicy.allocateRefundPayments(
            refundTotal = 1_234,
            originalPayments = emptyList(),
            fallbackMethod = PaymentMethod.CASH.name,
        )

        assertEquals(listOf(PaymentTotal(PaymentMethod.CASH.name, 1_234)), result)
    }

    @Test
    fun sourceWiresManagerApprovedContextSelectionAuditAndPrivateActivity() {
        val root = File("..")
        val secure = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val partial = File("src/main/java/jp/co/tenposinfo/register/PartialReturn.kt").readText()
        val runtime = File("src/main/java/jp/co/tenposinfo/register/ManualRefundFallbackV135.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(secure.contains("ManualRefundFallbackRuntimeV135.withApprovedContext"))
        assertTrue(secure.contains("ApprovedRefundContextV135"))
        assertTrue(partial.contains("ManualRefundFallbackRuntimeV135.resolveMethodOrRequest"))
        assertTrue(runtime.contains("REFUND_METHOD_OVERRIDE_SELECTED"))
        assertTrue(runtime.contains("ManualRefundMethodActivityV135"))
        assertTrue(runtime.contains("TTL_MILLIS"))
        assertTrue(manifest.contains(".ManualRefundMethodActivityV135"))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":app:assembleDebug"))
    }
}
