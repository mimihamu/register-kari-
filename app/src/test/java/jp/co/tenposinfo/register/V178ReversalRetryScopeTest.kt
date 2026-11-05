package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.78: tapping the same original sale must not mint another refund ID. */
class V178ReversalRetryScopeTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
    private val screen = source.substring(
        source.indexOf("private fun ReversalScreen("),
        source.indexOf("private val reversalQuantityInputRegex"),
    )

    @Test fun requestIdChangesOnlyWhenActualSaleChanges() {
        assertTrue(ReversalRetryScopeV178.requiresNewId(null, 123))
        assertFalse(ReversalRetryScopeV178.requiresNewId(123, 123))
        assertTrue(ReversalRetryScopeV178.requiresNewId(123, 124))
        val guard = "ReversalRetryScopeV178.requiresNewId(requestScopeSaleId, sale.id)"
        assertTrue(screen.split(guard).size - 1 == 2)
        assertTrue(screen.contains("var requestScopeSaleId by rememberSaveable(initialSaleId)"))
    }

    @Test fun explicitlyUnlockingSearchKeepsOriginalRequestScope() {
        val unlock = screen.substringAfter(
            "contextSaleLocked = false\n                                    selectedSaleId = null"
        ).substringBefore("localMessage = \"元売上固定を解除しました")
        assertFalse(unlock.contains("requestId = UUID.randomUUID()"))
        assertFalse(unlock.contains("requestScopeSaleId = null"))
    }

    @Test fun generalMenuRecreatesSelectedSaleAndPendingInputs() {
        assertTrue(screen.contains("var selectedSaleId by rememberSaveable(initialSaleId)"))
        assertTrue(screen.contains("var quantityInputs by rememberSaveable(initialSaleId)"))
        assertTrue(screen.contains("var type by rememberSaveable(initialSaleId)"))
        assertTrue(screen.contains("var reason by rememberSaveable(initialSaleId)"))
        val rehydrate = screen.substringAfter("LaunchedEffect(initialSaleId, selectedSaleId)")
            .substringBefore("Column(Modifier.fillMaxSize())")
        assertTrue(rehydrate.contains("lines = restoredLines"))
        assertTrue(rehydrate.contains("directSaleOverride = restoredSale.takeIf"))
        val good = rehydrate.substringAfter("} else {")
        assertFalse(good.contains("requestId = UUID.randomUUID().toString()"))
    }

    @Test fun existingSaleDeepLinkAlsoKeepsItsRetryIdentity() {
        val initializer = screen.substringAfter("LaunchedEffect(initialSaleId) {")
            .substringBefore("LaunchedEffect(initialSaleId, selectedSaleId)")
        val valid = initializer.substringAfter("context.mayOpenLocked && sale != null -> {")
        assertFalse(valid.contains("requestId = UUID.randomUUID().toString()"))
        assertTrue(valid.contains("lines = runCatching { loadLines(sale.id) }"))
    }
}
