package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.76 responsive line editing and stable reversal retry identity. */
class V176ReversalResponsiveRetryTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
    private val screen = source.substring(
        source.indexOf("private fun ReversalScreen("),
        source.indexOf("private val reversalQuantityInputRegex"),
    )

    @Test fun fontScalingUsesThreeStepFlow() {
        assertTrue(ReversalLayoutPolicyV175.useSteps(800))
        assertFalse(ReversalLayoutPolicyV175.useSteps(1250))
        assertTrue(ReversalLayoutPolicyV175.useSteps(1499, 1.2f))
        assertFalse(ReversalLayoutPolicyV175.useSteps(1500, 1.2f))
        assertTrue(ReversalLayoutPolicyV175.useSteps(1849, 1.5f))
        assertFalse(ReversalLayoutPolicyV175.useSteps(1850, 1.5f))
        assertTrue(screen.contains("LocalConfiguration.current.fontScale"))
    }

    @Test fun quantityListScrollsAndContinueButtonRemainsVisible() {
        assertTrue(screen.contains("LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp))"))
        assertTrue(screen.contains("Text(\"返金内容を確認\", fontWeight = FontWeight.Bold)"))
        assertTrue(screen.indexOf("onClick = { compactStepV175 = 2 }") >
            screen.indexOf("LazyColumn(Modifier.weight(1f)"))
    }

    @Test fun retryIdSurvivesRecreationAndSelectedTypeRetap() {
        assertTrue(source.contains("var requestId by rememberSaveable(initialSaleId)"))
        assertTrue(screen.contains("onExecute(saleId, type, quantityHundredths, reason, pin, requestId)"))
        assertTrue(screen.contains("if (type != ReversalType.RETURN)"))
        assertTrue(screen.contains("if (type != ReversalType.CANCEL)"))
    }
}
