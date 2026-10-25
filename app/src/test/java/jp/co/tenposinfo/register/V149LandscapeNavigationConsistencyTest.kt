package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard against landscape menus regressing to one long vertical stack. */
class V149LandscapeNavigationConsistencyTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(file: String) = File(root, file).readText()

    @Test fun landscapeCatalogAndRevisionHubsKeepMultipleColumns() {
        for (file in listOf("CatalogHubActivityV030.kt", "DynamicCatalogHubActivityV030.kt")) {
            val src = source(file)
            assertTrue(file, src.contains("val compact = maxWidth < 700.dp"))
            assertTrue(file, src.contains("val twoColumn = maxWidth < 1_080.dp"))
            assertTrue(file, src.contains("else if (twoColumn)"))
            assertFalse(file, src.contains("maxHeight < 600.dp"))
            assertFalse(file, src.contains("metrics.isCompact || maxWidth < 1_080.dp"))
            assertTrue(file, src.contains("onBack = onClose"))
        }
    }

    @Test fun settingsHomeIsActionFirstThreeToFourColumnAndKeepsErrorsVisible() {
        val src = source("AdminSettingsActivity.kt")
        assertTrue(src.contains("maxWidth < 540.dp -> 1"))
        assertTrue(src.contains("maxWidth < 1_050.dp -> 3"))
        assertTrue(src.contains("else -> 4"))
        assertTrue(src.contains("items(filteredDaily.chunked(columns))"))
        assertTrue(src.contains("items(filteredMaintenance.chunked(columns))"))
        assertTrue(src.contains("Text(\"設定異常："))
        assertFalse(src.contains("AsValueRow(\"未保存変更\""))
        assertTrue(src.contains("onBack = onClose"))
        assertTrue(src.contains("onClick = onLock"))
    }

    @Test fun majorSettingsAndManagementScreensHaveTopLeftBack() {
        val topBar = source("RegisterTopBarV141.kt")
        assertTrue(topBar.contains("onBack: (() -> Unit)? = null"))
        assertTrue(topBar.contains("if (onBack != null)"))
        assertTrue(topBar.contains("onClick = onBack"))
        assertTrue(topBar.contains("Text(\"戻る\""))
        for (file in listOf(
            "OperationsHubActivityV030.kt",
            "CatalogHubActivityV030.kt",
            "DynamicCatalogHubActivityV030.kt",
            "CatalogSettingsActivity.kt",
            "DynamicCatalogSettingsActivity.kt",
            "TaxPaymentSettingsHubActivity.kt",
            "TaxInvoiceSettingsActivity.kt",
            "PaymentSettingsActivity.kt",
            "ReceiptSettingsActivity.kt",
            "CustomerDisplaySettingsActivity.kt",
            "AutoBackupSettingsActivity.kt",
            "InitialReleaseSettingsV135.kt",
            "SyncSettingsActivity.kt",
        )) {
            assertTrue(file, source(file).contains("onBack = "))
        }
        assertFalse(source("CatalogHubActivityV030.kt").contains("CatalogHubBottomV030(metrics, onClose)"))
        assertFalse(source("DynamicCatalogHubActivityV030.kt").contains("DynamicHubBottomV030(metrics, onClose)"))
    }
}
