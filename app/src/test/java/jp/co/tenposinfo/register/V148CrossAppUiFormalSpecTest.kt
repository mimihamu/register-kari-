package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.5 UI-AT-03 / UI-AT-06 / SAL-012: screen chrome and selected-sale affordance. */
class V148CrossAppUiFormalSpecTest {
    private val pos = File("src/main/java/jp/co/tenposinfo/register")
    private val plus = File("../management-app/src/main/java/jp/co/tenposinfo/register/plus")
    private val cd = File("../customer-display/src/main/java/jp/co/tenposinfo/register/cd")

    private fun text(dir: File, name: String) = File(dir, name).readText()

    @Test fun everyPosActivityKeepsSystemInsetsAndReadableBars() {
        val screens = pos.listFiles().orEmpty()
            .filter { it.name.contains("Activity") && it.extension == "kt" }
            .filter { it.readText().contains("setContent {") || it.readText().contains("setContentView(") }
        assertTrue("POS activities were not discovered", screens.size >= 40)
        screens.forEach { screen ->
            val src = screen.readText()
            if (src.contains("setContent {")) {
                assertTrue("${screen.name} system colors", src.contains("configureRegisterSystemBars(window)"))
                assertTrue("${screen.name} system padding", src.contains(if (screen.name == "ManualReturnActivityV135.kt")
                    "RegisterSafeAreaV141(topBarColor = Color(0xFFB42318))" else "RegisterSafeAreaV141 {"))
            } else if (screen.name == "AppDistributionUpdateActivityV146.kt") {
                assertTrue(src.contains("WindowInsetsCompat.Type.systemBars()"))
                assertTrue(src.contains("ViewCompat.setOnApplyWindowInsetsListener(scroll)"))
                assertTrue(src.contains("isAppearanceLightStatusBars = false"))
            } else {
                assertTrue("${screen.name} native insets", src.contains("WindowInsetsCompat.Type.systemBars()"))
                assertTrue("${screen.name} dark status bar", src.contains("configureRegisterSystemBars(window)"))
            }
        }
    }

    @Test fun everyPlusActivityHasSharedSystemBarContract() {
        val activities = plus.listFiles().orEmpty().filter { it.extension == "kt" && it.readText().contains("class ") && it.readText().contains("setContent {") }
        assertTrue("plus Compose activities were not discovered", activities.size >= 8)
        activities.forEach { file ->
            val src = file.readText()
            if (file.name == "MainActivity.kt") {
                assertTrue(src.contains("WindowInsets.safeDrawing"))
                assertTrue(src.contains("isAppearanceLightStatusBars = false"))
            } else {
                assertTrue("${file.name} navy bars", src.contains("configurePlusSystemBarsV148(window)"))
                assertTrue("${file.name} safe drawing", src.contains("PlusSafeAreaV148 {"))
            }
        }
        val updater = text(plus, "AppDistributionUpdateActivityV146.kt")
        assertTrue(updater.contains("WindowInsetsCompat.Type.systemBars()"))
        assertTrue(updater.contains("setContentView(scroll)"))
        val folder = text(plus, "ManagementFolderSyncScreen.kt")
        assertTrue(folder.contains("Color(0xFF173F6B)"))
        assertTrue(folder.contains("text = \"つぐレジ＋\""))
        val mobile = text(plus, "ManagementMobileScreen.kt")
        assertFalse("duplicate helper caption", mobile.contains("スマホ縦画面で売上確認・取引確認・JSON取込"))
    }

    @Test fun cdKeepsDarkPresentationAndSafeInsetWithoutAddingCustomerToolbar() {
        val main = text(cd, "MainActivity.kt")
        assertTrue(main.contains("WindowInsets.safeDrawing"))
        assertTrue(main.contains("isAppearanceLightStatusBars = false"))
        assertTrue(main.contains("modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)"))
        val updater = text(cd, "AppDistributionUpdateActivityV146.kt")
        assertTrue(updater.contains("WindowInsetsCompat.Type.systemBars()"))
        assertTrue(updater.contains("setContentView(scroll)"))
    }

    @Test fun allUpdateActivitiesAllowScrollingAndRetainInstallSafety() {
        listOf(pos, plus, cd).forEach { dir ->
            val src = text(dir, "AppDistributionUpdateActivityV146.kt")
            assertTrue(src.contains("ScrollView(this)"))
            assertTrue(src.contains("ViewCompat.requestApplyInsets(scroll)"))
            assertTrue(src.contains("isAppearanceLightNavigationBars = false"))
            assertTrue(src.contains("verifyApk(partial, item)"))
            assertTrue(src.contains("packageManager.canRequestPackageInstalls()"))
            assertTrue(src.contains("FileProvider.getUriForFile"))
        }
    }

    @Test fun sal012SelectedSaleLineHasRedBorderBackgroundAndText() {
        val src = text(pos, "MainActivity.kt")
        val start = src.indexOf("itemsIndexed(cart) { index, item ->")
        assertTrue("sale rows exist", start >= 0)
        val lineUi = src.substring(start).take(2_000)
        assertTrue("selected background", lineUi.contains("if (selected) PaleBlue else Color.Transparent"))
        assertTrue("red border", lineUi.contains("color = if (selected) Danger else Color.Transparent"))
        assertTrue("visible selection name", lineUi.contains("\"選択中\""))
        assertTrue("selection uses a border", lineUi.contains(".border("))
    }
}
