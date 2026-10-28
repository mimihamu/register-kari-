package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** A negative register transaction requires a deliberate confirmation and manager authorization. */
class V155ManualReturnModalContractTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(root, name).readText()

    @Test fun minusCheckoutShowsItemsAmountAndRefundMethodBeforeCommit() {
        val s = src("ManualReturnActivityV135.kt")
        assertTrue(s.contains("RegisterMinusSalesV158("))
        assertTrue(s.contains("RegisterModalV154("))
        assertTrue(s.contains("label = \"マイナスを確定\""))
        assertTrue(s.contains("destructive = true"))
        assertTrue(s.contains("ManualReturnPolicyV135.toPositiveCartItems(request, reasonRequired = false)"))
        assertTrue(s.contains("coordinator.create(request, managerPin)"))
        assertTrue(s.contains("if (submissionGate.tryStart())"))
        assertTrue(s.contains("submissionGate.release()"))
        assertTrue(s.contains("ManagerApprovalV145.canUseCurrentManagerSession"))
        assertTrue(s.contains("onExit = ::finish"))
        assertFalse(s.contains("database.saveSale("))
    }

    @Test fun returnReasonIsOptionalAndDoubleCommitIsPrevented() {
        val source = src("ManualReturnActivityV135.kt")
        val gate = src("ManualReturnSubmissionGateV135.kt")
        val policy = src("ManualReturnV135.kt")
        assertTrue(source.contains("ManualReturnRequestV135(lines, reason, refundMethod)"))
        assertTrue(policy.contains("fun isReasonRequired(): Boolean = false"))
        assertTrue(gate.contains("AtomicBoolean(false)"))
    }
}
