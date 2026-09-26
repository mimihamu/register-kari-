package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136CatalogScreenIdContractTest {
    private fun source(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun catalogBaseEditorsUseV25CanonicalIds() {
        val hub = source("CatalogHubActivityV030.kt")
        val settings = source("CatalogSettingsActivity.kt")

        listOf(
            "SCR-600" to "商品マスター",
            "SCR-610" to "部門マスター",
            "SCR-610" to "グループマスター",
            "SCR-620" to "商品ボタン配置",
            "SCR-630A" to "税区分マスター",
            "SCR-631" to "販売プロファイル",
        ).forEach { (id, title) ->
            assertTrue("catalog hub missing $id $title", hub.contains("\"$id\", \"$title\""))
            assertTrue("catalog editor missing $id $title", settings.contains("\"$id\", \"$title\""))
        }

        assertTrue(hub.contains("商品・部門・税・販売条件"))
        assertTrue(settings.contains("CatalogHeader(\"\", \"商品・部門・税・販売条件\""))
        assertTrue(settings.contains("if (screenId.isBlank()) title else \"\$screenId  \$title\""))
    }

    @Test
    fun legacyBaseCatalogIdsAreNotShownByBaseCatalogUi() {
        val hub = source("CatalogHubActivityV030.kt")
        val settings = source("CatalogSettingsActivity.kt")

        listOf("SCR-200", "SCR-210", "SCR-220", "SCR-230", "SCR-240", "SCR-250", "SCR-260").forEach { legacy ->
            assertFalse("legacy base catalog id leaked in hub: $legacy", hub.contains("\"$legacy\""))
            assertFalse("legacy base catalog id leaked in editor: $legacy", settings.contains("\"$legacy\""))
        }

        // 旧270系は正式仕様への一対一対応が未確定のため、この変更では割り当て直さない。
        assertTrue(hub.contains("SCR-270  任意税率・メニュー改定"))
    }
}
