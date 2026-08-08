package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V064SaleDetailReversalNavigationTest {
    @Test
    fun reversalSaleContextOpensLockedOnlyForExistingIncompleteSale() {
        assertTrue(ReversalNavigation.resolve(10L, saleExists = true, alreadyCompleted = false).mayOpenLocked)
        assertFalse(ReversalNavigation.resolve(10L, saleExists = false, alreadyCompleted = false).mayOpenLocked)
        assertFalse(ReversalNavigation.resolve(10L, saleExists = true, alreadyCompleted = true).mayOpenLocked)
        assertFalse(ReversalNavigation.resolve(null, saleExists = true, alreadyCompleted = false).mayOpenLocked)
    }

    @Test
    fun sourceRechecksPermissionAndLocksExactSaleUntilExplicitUnlock() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val secure = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(main.contains("canReverse = currentOperator?.allows(RegisterPermission.REVERSAL) == true"))
        assertTrue(main.contains("ReversalNavigation.intent(context, detail.summary.id)"))
        assertTrue(main.contains("この売上を返品・取消"))
        assertTrue(operations.contains("ReversalNavigation.requestedSaleId(intent)"))
        assertTrue(operations.contains("current?.allows(RegisterPermission.REVERSAL) == true"))
        assertTrue(operations.contains("返品・取消の権限が失効したため管理メニューへ戻りました"))
        assertTrue(operations.contains("initialSaleId = reversalContextSaleId"))
        assertTrue(operations.contains("contextSaleLocked"))
        assertTrue(operations.contains("元売上を固定しています"))
        assertTrue(operations.contains("別売上を検索"))
        assertTrue(operations.contains("enabled = !contextSaleLocked"))
        assertTrue(operations.contains("!completed && !contextSaleLocked"))
        assertTrue(operations.contains("secureStore.createReversal"))
        assertTrue(secure.contains("RegisterPermission.REVERSAL"))
        assertTrue(workflow.contains("V064SaleDetailReversalNavigationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.74.0_dev1_sale_receipt_reprint_matching_new_items_debug.apk"))
    }
}
