package jp.co.tenposinfo.register

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V063ReversalSaleLookupTest {
    @Test
    fun reversalUiUsesExpandedReadOnlyLookupWithoutReplacingSafetyEngine() {
        val root = File("..")
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val secure = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(activity.contains("registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT)"))
        assertTrue(activity.contains("lookupSale = { saleId -> registerDatabase.loadSaleDetail(saleId)?.summary }"))
        assertTrue(activity.contains("SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = saleQuery))"))
        assertTrue(activity.contains("売上No.直接指定"))
        assertTrue(activity.contains("全量返品・取消済みです"))
        assertTrue(activity.contains("元売上の選択を解除しました"))
        assertTrue(activity.contains("directSaleOverride"))
        assertTrue(activity.contains("secureStore.createReversal"))
        assertTrue(store.contains("PartialReturnPolicy.select"))
        assertTrue(store.contains("line_tax_snapshots"))
        assertTrue(store.contains("reversal_items"))
        assertTrue(store.contains("claimOperationKey"))
        assertTrue(secure.contains("RegisterPermission.REVERSAL"))
        assertTrue(workflow.contains("V063ReversalSaleLookupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.64.0_dev1_sale_detail_reversal_navigation_debug.apk"))
    }
}
