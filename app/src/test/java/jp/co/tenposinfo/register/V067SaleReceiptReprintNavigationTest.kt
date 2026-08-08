package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V067SaleReceiptReprintNavigationTest {
    @Test
    fun exactExistingSaleMayOpenWithoutFallback() {
        val context = SaleReceiptNavigation.resolve(123L, saleExists = true)
        assertTrue(context.mayOpen)
        assertEquals(123L, context.saleId)
    }

    @Test
    fun missingInvalidOrUnknownSaleFailsClosed() {
        assertFalse(SaleReceiptNavigation.resolve(null, saleExists = true).mayOpen)
        assertFalse(SaleReceiptNavigation.resolve(0L, saleExists = true).mayOpen)
        assertFalse(SaleReceiptNavigation.resolve(123L, saleExists = false).mayOpen)
        assertEquals(null, SaleReceiptNavigation.resolve(123L, saleExists = false).saleId)
    }

    @Test
    fun sourceUsesExistingReceiptRendererAndSafeQueueWithoutChangingSale() {
        val root = File("..")
        val navigation = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptNavigation.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt").readText()
        val lookup = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.67_SALE_RECEIPT_REPRINT_NAVIGATION.md")
        val notes = File(root, "docs/V0.67_RELEASE_NOTES.md")

        assertTrue(navigation.contains("SaleReceiptReprintActivity::class.java"))
        assertTrue(navigation.contains("EXTRA_SALE_ID"))
        assertTrue(activity.contains("current.allows(RegisterPermission.VIEW_SALES)"))
        assertTrue(activity.contains("ReceiptFactory.fromSale"))
        assertTrue(activity.contains("ReceiptRenderer.render"))
        // v0.68 strengthens the v0.67 queue path by creating the print job atomically
        // with an append-only audit row. The cumulative v0.67 requirement is that a
        // confirmed reprint still enters the established print queue, not that the UI
        // must call RegisterDatabase.enqueueReprint() directly forever.
        assertTrue(
            activity.contains("database.enqueueReprint(detail.summary.id)") ||
                activity.contains("auditStore.request("),
        )
        assertTrue(activity.contains("AutomaticPrintScheduler.enqueueNow"))
        assertTrue(activity.contains("再印字を確認"))
        assertTrue(activity.contains("再印字を確定"))
        assertTrue(activity.contains("UnifiedPrintQueueActivity::class.java"))
        assertFalse(activity.contains("UPDATE sales"))
        assertFalse(activity.contains("deleteSale"))
        assertTrue(lookup.contains("SaleReceiptNavigation.intent(context, saleId)"))
        assertTrue(lookup.contains("通常レシート確認・再印字"))
        assertTrue(manifest.contains(".SaleReceiptReprintActivity"))
        assertTrue(build.contains("versionCode = 102"))
        assertTrue(build.contains("versionName = \"0.72.0-dev.1\""))
        assertTrue(workflow.contains("V067SaleReceiptReprintNavigationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.72.0_dev1_sale_receipt_reprint_custom_range_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
