package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Native refund must never commit a return immediately from the screen button. */
class V155ManualReturnModalContractTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(root, name).readText()

    @Test fun nativeRefundRequiresDeliberateCommitAfterAmountAndMethodPreview() {
        val s = src("ManualReturnActivityV135.kt")
        assertTrue(s.contains("setOnClickListener { showReturnConfirmationV155() }"))
        assertTrue(s.contains("private fun showReturnConfirmationV155()"))
        assertTrue(s.contains("ManualReturnPolicyV135.toPositiveCartItems("))
        assertTrue(s.contains("TaxEngine.calculate(items).grossAmount"))
        assertTrue(s.contains("if (submissionGate.isSubmitting()) return"))
        assertTrue(s.contains(".setCancelable(false)"))
        assertTrue(s.contains("confirm.setCanceledOnTouchOutside(false)"))
        assertTrue(s.contains(".setNegativeButton(\"キャンセル\", null)"))
        assertTrue(s.contains(".setPositiveButton(\"返品を確定\") { _, _ -> executeReturn() }"))
        assertTrue(s.contains("android.graphics.Color.rgb(180, 35, 24)"))
        assertTrue(s.contains("text = \"つぐレジ  元取引なし返品\""))
        assertTrue(s.contains("contentDescription = \"返品画面を閉じる\""))
        assertFalse(s.contains("setOnClickListener { executeReturn() }"))
    }

    @Test fun doubleSubmissionGateAndOptionalReasonRemainIntact() {
        val source = src("ManualReturnActivityV135.kt")
        val gate = src("ManualReturnSubmissionGateV135.kt")
        val policy = src("ManualReturnV135.kt")
        assertTrue(source.contains("if (!submissionGate.tryStart()) return"))
        assertTrue(source.contains("submissionGate.release()"))
        assertTrue(source.contains("coordinator.create(request, managerPinInput.text.toString())"))
        assertTrue(policy.contains("fun isReasonRequired(): Boolean = false"))
        assertTrue(gate.contains("AtomicBoolean(false)"))
    }
}
