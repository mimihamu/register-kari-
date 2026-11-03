package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.77: fixed one-to-one retry ID and visible no-original reversal choice. */
class V177ReversalEntryAndDeepLinkRetryTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
    private val screen = source.substring(
        source.indexOf("private fun ReversalScreen("),
        source.indexOf("private val reversalQuantityInputRegex"),
    )
    private val initialization = screen.substringAfter("LaunchedEffect(initialSaleId) {")
        .substringBefore("Column(Modifier.fillMaxSize())")

    @Test fun deepLinkedValidSaleDoesNotRotateRetryIdOnRecreation() {
        val validSale = initialization.substringAfter("context.mayOpenLocked && sale != null -> {")
        assertFalse(validSale.contains("requestId = UUID.randomUUID().toString()"))
        assertTrue(screen.contains("var requestId by rememberSaveable(initialSaleId)"))
        assertTrue(screen.contains("onExecute(saleId, type, quantityHundredths, reason, pin, requestId)"))
    }

    @Test fun missingOrCompletedDeepLinkResetsToSearchInsteadOfEmptyProductStep() {
        val missing = initialization.substringAfter("!context.saleExists -> {").substringBefore("context.alreadyCompleted -> {")
        val completed = initialization.substringAfter("context.alreadyCompleted -> {").substringBefore("context.mayOpenLocked &&")
        assertTrue(missing.contains("compactStepV175 = 0"))
        assertTrue(completed.contains("compactStepV175 = 0"))
        assertTrue(screen.contains("var compactStepV175 by rememberSaveable(initialSaleId)"))
    }

    @Test fun noOriginalReturnIsVisibleOnTheFirstStepWithoutDuplicateButtons() {
        val search = screen.substringAfter("Text(\"元売上を検索・選択\"")
            .substringBefore("if (contextSaleLocked && selectedSaleId != null)")
        assertTrue(search.contains("onClick = onManualReturn"))
        assertTrue(search.contains("Text(\"元取引なし → レジマイナス\""))
        assertEquals(1, screen.split("Text(\"元取引なし → レジマイナス\"").size - 1)
        assertTrue(screen.contains("onClick = { compactStepV175 = 0 }"))
        assertTrue(screen.contains("Text(\"元売上を選ぶ\")"))
    }
}
