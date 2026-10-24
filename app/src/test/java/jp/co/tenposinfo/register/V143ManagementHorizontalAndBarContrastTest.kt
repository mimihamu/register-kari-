package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: 800×480 landscape must never become a single tall management menu. */
class V143ManagementHorizontalAndBarContrastTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun landscapeTabletsAlwaysShowStatusAndActionsSideBySide() {
        assertFalse(HubManagementLayoutV143.twoPane(600))
        assertFalse(HubManagementLayoutV143.twoPane(699))
        for (width in listOf(700, 800, 960, 1280, 1920)) {
            assertTrue("width=$width", HubManagementLayoutV143.twoPane(width))
        }
        assertEquals(2, HubManagementLayoutV143.columns(460))
        assertEquals(2, HubManagementLayoutV143.columns(719))
        assertEquals(3, HubManagementLayoutV143.columns(720))
        assertEquals(3, HubManagementLayoutV143.columns(1000))
        assertTrue(HubManagementLayoutV143.tileHeightDp(350) >= 56)
        assertTrue(HubManagementLayoutV143.tileHeightDp(720) >= 56)
    }

    @Test fun managementUsesIndependentScrollAndCompactMultiColumnActions() {
        val hub = source("OperationsHubActivityV030.kt")
        assertTrue(hub.contains("HubManagementLayoutV143.twoPane(maxWidth.value.toInt())"))
        assertTrue(hub.contains("HubStatusPanelV030("))
        assertTrue(hub.contains("Modifier.weight(0.85f).fillMaxHeight()"))
        assertTrue(hub.contains("Modifier.weight(1.55f).fillMaxHeight()"))
        assertTrue(hub.contains("actions.chunked(columns)"))
        assertTrue(hub.contains("modifier.verticalScroll(rememberScrollState())"))
        assertFalse(hub.contains("metrics.isCompact || maxWidth < 1_050.dp"))
        for (label in listOf(
            "営業開始・状態", "当日売上", "入出金", "X点検", "Z精算",
            "返品・取消", "点検・精算履歴", "営業日別 売上検索",
            "領収書発行", "レシート再印字台帳", "元取引なし返品"
        )) {
            assertTrue("Missing management action: $label", hub.contains("\"$label\""))
        }
        assertTrue(hub.contains("OperationsLegacyRouteV140.DAILY_SALES"))
        assertTrue(hub.contains("OperationsLegacyRouteV140.CASH_MOVEMENT"))
        assertTrue(hub.contains("OperationsLegacyRouteV140.REVERSAL"))
    }

    @Test fun headerSettingsAndNavigationBarHaveReadableContrast() {
        val hub = source("OperationsHubActivityV030.kt")
        val safeArea = source("RegisterTopBarV141.kt")
        val chrome = source("RegisterUiChromeV028.kt")
        assertTrue(hub.contains("containerColor = Color.White"))
        assertTrue(hub.contains("Text(\"各種設定\", color = HubNavyV030"))
        assertTrue(hub.contains("HubBottomV030(metrics, onClose)"))
        assertTrue(hub.contains(".background(HubNavyV030)"))
        assertTrue(safeArea.contains("Box(Modifier.fillMaxSize().background(RegisterTopBarColorV141))"))
        assertTrue(safeArea.contains("WindowInsets.safeDrawing"))
        assertTrue(chrome.contains("isAppearanceLightNavigationBars = false"))
        assertTrue(chrome.contains("window.navigationBarColor = Color.rgb(23, 63, 107)"))
    }
}
