package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.35 COR-001 numeric correction behavior and source wiring gates. */
class V135NumericCorrectionTest {
    @Test
    fun pendingNumericInputMustBeClearedBeforeLineCorrection() {
        assertTrue(NumericCorrectionPolicyV135.shouldClearInput("1"))
        assertTrue(NumericCorrectionPolicyV135.shouldClearInput("00012"))
        assertFalse(NumericCorrectionPolicyV135.shouldClearInput(""))
        assertFalse(NumericCorrectionPolicyV135.shouldClearInput("   "))
    }

    @Test
    fun salesCorrectionButtonPrioritizesNumericClearAndDoesNotDirectlyRemoveLine() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val salesScreen = source
            .substringAfter("private fun SalesScreen(")
            .substringBefore("@Composable\nprivate fun LineEditScreen(")

        val policyCheck = "if (NumericCorrectionPolicyV135.shouldClearInput(numericInput))"
        val policyIndex = salesScreen.indexOf(policyCheck)
        val clearIndex = salesScreen.indexOf("numericInput = \"\"", startIndex = policyIndex)
        val removeIndex = salesScreen.indexOf("onRemove()", startIndex = policyIndex)

        assertTrue("COR-001 policy check is missing from SalesScreen", policyIndex >= 0)
        assertTrue("numeric input clear must follow the COR-001 policy check", clearIndex > policyIndex)
        assertTrue("line correction fallback must execute only after numeric clear branch", removeIndex > clearIndex)
        assertFalse("訂正 must not directly invoke line removal while numeric input may exist", salesScreen.contains("onClick = onRemove"))
    }
}
