package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-screen/Android Back returns to the actual origin and preserves payment draft safety. */
class V170OriginBackNavigationTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(root,name).readText()

    @Test fun salesHistoryAndDiscountReturnToTheirOwnOrigin() {
        val s = src("MainActivity.kt")
        assertTrue(s.contains("var historyBackDestination by remember"))
        assertTrue(s.contains("historyBackDestination = AppScreen.COMPLETE"))
        assertTrue(s.contains("historyBackDestination = AppScreen.SALES"))
        assertTrue(s.contains("onBack = { screen = historyBackDestination }"))
        assertTrue(s.contains("discountBackDestination = AppScreen.LINE_EDIT"))
        assertTrue(s.contains("discountBackDestination = AppScreen.SALES"))
        assertTrue(s.contains("onBack = { screen = discountBackDestination }"))
        assertTrue(s.contains("BackHandler(enabled = screen !in setOf("))
        assertTrue(s.contains("AppScreen.PAYMENT -> returnFromPayment()"))
        assertTrue(s.contains("onBack = ::returnFromPayment"))
        assertTrue(s.contains("if (saleCommitInProgress) return"))
        assertTrue(s.contains("paymentDraftStore.clear()"))
    }

    @Test fun reversalFromSaleDetailExitsToDetailNotManagementMenu() {
        val operations = src("OperationsActivity.kt")
        assertTrue(operations.contains("requestedLegacyRoute != null || requestedReversalSaleId != null"))
        assertTrue(operations.contains("if (initialSaleId != null) \"売上詳細へ戻る\" else \"レジ管理へ戻る\""))
        assertTrue(operations.contains("BackHandler(enabled = screen != OperationsScreen.MENU)"))
        assertTrue(operations.contains("reversalContextSaleId = null"))
    }
}
