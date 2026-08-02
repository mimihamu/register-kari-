package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V028UiStabilityTest {
    private fun source(name: String) = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun lifecycleProviderDoesNotInjectOverlayButtons() {
        val provider = source("CatalogBootstrapProvider.kt")
        assertFalse(provider.contains("FrameLayout.LayoutParams"))
        assertFalse(provider.contains("installDynamicCatalogButton"))
        assertFalse(provider.contains("installRevisionEditorButton"))
        assertFalse(provider.contains("installTaxInvoiceButton"))
        assertFalse(provider.contains("installSyncButton"))
    }

    @Test
    fun navigationControlsArePartOfComposeLayout() {
        val catalog = source("CatalogSettingsActivity.kt")
        val dynamic = source("DynamicCatalogSettingsActivity.kt")
        assertTrue(catalog.contains("onDynamic"))
        assertTrue(catalog.contains("任意税率・メニュー改定"))
        assertTrue(dynamic.contains("onTaxInvoice"))
        assertTrue(dynamic.contains("onRevisionEditor"))
        assertTrue(dynamic.contains("onSync"))
    }

    @Test
    fun responsiveUiBlocksAreNotDuplicated() {
        val main = source("MainActivity.kt")
        val catalog = source("CatalogSettingsActivity.kt")
        val dynamic = source("DynamicCatalogSettingsActivity.kt")
        assertEquals(1, main.lineSequence().count {
            it.trim() == "val columnGap = if (compact) resolvedRowGapDp.dp else 8.dp"
        })
        assertEquals(1, Regex("Text\\(\\\"任意税率・メニュー改定\\\"").findAll(catalog).count())
        assertEquals(1, Regex("Text\\(\\\"税・インボイス\\\"").findAll(dynamic).count())
        assertEquals(1, Regex("Text\\(\\\"改定内容編集\\\"").findAll(dynamic).count())
        assertEquals(1, Regex("Text\\(\\\"同期基盤\\\"").findAll(dynamic).count())
    }

    @Test
    fun compactKeypadUsesLargerEqualWidthKeys() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("COMPACT_VALUE_HEIGHT_DP = 46"))
        assertTrue(main.contains("COMPACT_KEY_HEIGHT_DP = 42"))
        assertTrue(main.contains("COMPACT_KEY_GAP_DP = 5"))
        assertTrue(main.contains("COMPACT_FUNCTION_HEIGHT_DP = 40"))
        assertTrue(main.contains("BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1f).height(buttonHeight))"))
        assertFalse(main.contains("Modifier.weight(1.4f).height(buttonHeight)"))
    }

    @Test
    fun formalBrandAndFixedChromeAreUsed() {
        val sources = listOf(
            "MainActivity.kt",
            "CatalogSettingsActivity.kt",
            "DynamicCatalogSettingsActivity.kt",
            "MenuRevisionEditorActivity.kt",
            "SyncSettingsActivity.kt",
            "UnifiedPrintQueueActivity.kt",
        ).map(::source)
        assertTrue(sources.all { !it.contains("Text(\"REGISTER\"") })
        assertTrue(sources.all { it.contains("configureRegisterSystemBars(window)") })
    }
}
