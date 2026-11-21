package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard against reintroducing white bottom strips or unreadable top-bar actions. */
class V159UnifiedChromeContractTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun fifteenOperationsAndSettingsScreensUseSharedActionBarTint() {
        val files = listOf(
            "OperationsActivity.kt",
            "AdminSettingsActivity.kt",
            "ReceiptVoucherLedgerActivity.kt",
            "BusinessStartActivityV030.kt",
            "SettlementActivityV030.kt",
            "AutoBackupSettingsActivity.kt",
            "BusinessDateSalesLookupActivity.kt",
            "PrinterStatusActivity.kt",
            "OutboxDeliverySettingsActivity.kt",
            "ExternalBackupSettingsActivity.kt",
            "PrinterToolsHubActivity.kt",
            "CatalogHubActivityV030.kt",
            "DynamicCatalogHubActivityV030.kt",
            "DataProtectionActivity.kt",
            "UnifiedPrintQueueActivity.kt",
        )
        for (name in files) {
            val s = source(name)
            assertTrue("$name must use a common background tint", s.contains(".background(RegisterSecondaryActionBarColorV159)"))
            assertFalse("$name must not have a white full-width bottom strip", s.contains(".background(Color.White)"))
        }
        val chrome = source("RegisterTopBarV141.kt")
        assertTrue(chrome.contains("internal val RegisterSecondaryActionBarColorV159 = Color(0xFFE3EDF7)"))
        assertTrue(chrome.contains("internal val RegisterTopBarColorV141 = Color(0xFF173F6B)"))
    }

    @Test fun registerManagementAndReceiptUseWhiteTextOnNavyWithoutWhiteButtons() {
        val management = source("OperationsHubActivityV030.kt")
        val receipt = source("ReceiptSettingsActivity.kt")
        for (s in listOf(management, receipt)) {
            assertTrue(s.contains("RegisterTopBarV141("))
            assertTrue(s.contains("border = BorderStroke(1.dp, Color.White)"))
            assertTrue(s.contains("containerColor = Color.Transparent"))
            assertTrue(s.contains("contentColor = Color.White"))
        }
        assertTrue(management.contains("Text(\"各種設定\", color = Color.White"))
        assertTrue(receipt.contains("if (showDetails) \"基本設定\" else \"詳細設定\""))
    }

    @Test fun businessDateLookupUsesStandardHeaderAndPreservesReadOnlyQueries() {
        val lookup = source("BusinessDateSalesLookupActivity.kt")
        assertTrue(lookup.contains("BusinessDateLookupHeader(onClose)"))
        assertTrue(lookup.contains("RegisterTopBarV141(title = \"営業日別 売上検索\", onBack = onBack)"))
        assertFalse(lookup.contains("Text(\"SCR-415"))
        assertFalse(lookup.contains("SQLite直接検索 / business_date基準"))
        assertTrue(lookup.contains("database.readableDatabase"))
        assertTrue(lookup.contains("selectionArgs"))
        assertFalse(lookup.contains("UPDATE sales"))
        assertFalse(lookup.contains("DELETE FROM sales"))
    }
}
