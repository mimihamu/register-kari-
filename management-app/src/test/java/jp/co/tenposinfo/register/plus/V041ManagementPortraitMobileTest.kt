package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V041ManagementPortraitMobileTest {
    @Test
    fun mobileNavigationPolicyIsStable() {
        assertEquals(ManagementSection.SALES, ManagementMobileUiPolicy.defaultSection)
        assertEquals(
            listOf("売上", "取引", "取込"),
            ManagementSection.entries.map(ManagementSection::label),
        )
        assertEquals(50, ManagementMobileUiPolicy.TRANSACTION_VISIBLE_LIMIT)
        assertEquals(1_200, ManagementMobileUiPolicy.PAYLOAD_PREVIEW_CHARACTERS)
    }

    @Test
    fun portraitManifestMobileScreenDocsAndWorkflowStayConnected() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = File(sourceRoot, "MainActivity.kt").readText()
        val mobileScreen = File(sourceRoot, "ManagementMobileScreen.kt").readText()
        val plusBuild = File("build.gradle.kts").readText()
        val registerBuild = File(root, "app/build.gradle.kts").readText()
        val registerManifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.41_TSUGUREGI_PLUS_MOBILE_PORTRAIT.md").readText()
        val notes = File(root, "docs/V0.41_RELEASE_NOTES.md").readText()

        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))
        assertFalse(manifest.contains("android:screenOrientation=\"landscape\""))
        assertTrue(registerManifest.contains("android:screenOrientation=\"landscape\""))
        assertTrue(activity.contains("TsuguRegiPlusFolderSyncScreen"))

        for (token in listOf(
            "NavigationBar",
            "ManagementSection.SALES",
            "ManagementSection.TRANSACTIONS",
            "ManagementSection.IMPORTS",
            "SalesOverviewScreen",
            "TransactionsScreen",
            "ImportOperationsScreen",
            "LazyColumn",
            "MobileFilterMenu",
            "JSON取込",
            "スマホ縦画面",
        )) assertTrue(mobileScreen.contains(token))
        assertFalse(mobileScreen.contains("verticalScroll(rememberScrollState())"))

        assertTrue(registerBuild.contains("versionCode = 88"))
        assertTrue(registerBuild.contains("versionName = \"0.58.0-dev.1\""))
        assertTrue(plusBuild.contains("versionCode = 14"))
        assertTrue(plusBuild.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(workflow.contains("Verify launcher and orientation configuration"))
        assertTrue(workflow.contains("android:screenOrientation=\"sensorPortrait\""))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
        assertTrue(docs.contains("sensorPortrait"))
        assertTrue(docs.contains("売上・取引・取込"))
        assertTrue(notes.contains("スマホ縦画面"))
        assertFalse(File(root, "tools/v041_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v041-apply.yml").exists())
    }
}