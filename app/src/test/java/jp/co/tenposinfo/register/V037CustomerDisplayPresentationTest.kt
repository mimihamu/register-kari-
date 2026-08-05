package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V037CustomerDisplayPresentationTest {
    @Test
    fun presentationRoundTripAndDefaultsRemainBackwardCompatible() {
        val presentation = CustomerDisplayPresentation(
            theme = CustomerDisplayTheme.WARM,
            showLogo = false,
            logoText = "つぐ",
            textScalePercent = 125,
            maxVisibleRows = 10,
            rowSpacingDp = 12,
            showCancelledItems = false,
            showTaxSymbol = true,
            standbyMessage = "本日もありがとうございます",
        )
        val restored = CustomerDisplayPresentation.fromJsonObject(presentation.toJsonObject())
        assertEquals(presentation, restored)
        assertEquals(CustomerDisplayPresentation(), CustomerDisplayPresentation.fromJsonObject(null))
    }

    @Test
    fun snapshotPublishesPresentationAndTaxSymbolWithoutBreakingSchemaV1() {
        val item = CartItem(
            product = Product(
                id = "P1",
                name = "軽減商品",
                unitPrice = 108,
                taxCategory = TaxCategory.INCLUDED_8,
                displayOrder = 1,
            ),
            quantity = 1,
        )
        val snapshot = CustomerDisplaySnapshotFactory.sales(
            items = listOf(item),
            storeName = "テスト店",
            presentation = CustomerDisplayPresentation(showTaxSymbol = true),
        )
        val json = snapshot.toJson()

        assertEquals(1, snapshot.schemaVersion)
        assertTrue(json.contains("\"presentation\""))
        assertTrue(json.contains("\"taxSymbol\":\"内※\""))
        assertTrue(json.contains("\"showTaxSymbol\":true"))
    }

    @Test
    fun registerSettingsRuntimeAndCdContractStayConnected() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val settings = File(root, "CustomerDisplaySettingsStore.kt").readText()
        val screen = File(root, "CustomerDisplaySettingsActivity.kt").readText()
        val runtime = File(root, "CustomerDisplayRuntime.kt").readText()
        val protocol = File(root, "CustomerDisplayProtocol.kt").readText()
        val cdRoot = File("../customer-display/src/main/java/jp/co/tenposinfo/register/cd")
        val cdModel = File(cdRoot, "CustomerDisplayModel.kt").readText()
        val cdScreen = File(cdRoot, "MainActivity.kt").readText()
        val cdCache = File(cdRoot, "CustomerDisplaySnapshotStore.kt").readText()
        val build = File("build.gradle.kts").readText()
        val cdBuild = File("../customer-display/build.gradle.kts").readText()

        assertTrue(settings.contains("presentation = CustomerDisplayPresentation"))
        assertTrue(screen.contains("表示デザイン（つぐレジ CDへ自動同期）"))
        assertTrue(runtime.contains("config.presentation"))
        assertTrue(protocol.contains("put(\"presentation\""))
        assertTrue(protocol.contains("taxSymbol = item.product.taxSymbol"))
        assertTrue(cdModel.contains("root.optJSONObject(\"presentation\")"))
        assertTrue(cdScreen.contains("CustomerDisplayPresentationPolicy.visibleItems"))
        assertTrue(cdScreen.contains("snapshotStore.save(snapshot)"))
        assertTrue(cdCache.contains("MAX_AGE_MS"))
        assertTrue(build.contains("versionCode = 67"))
        assertTrue(build.contains("versionName = \"0.37.0-dev.1\""))
        assertTrue(cdBuild.contains("versionCode = 7"))
        assertTrue(cdBuild.contains("versionName = \"0.14.0-dev.1\""))
        assertFalse(File("../tools/v037_apply.py").exists())
    }
}
