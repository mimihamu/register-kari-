package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V091DataProtectionResponsiveTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun compactPolicyCoversLowHeightAndLargeFont() {
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(widthDp = 800, heightDp = 480, fontScale = 1f),
        )
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(widthDp = 1280, heightDp = 720, fontScale = 1.30f),
        )
        assertEquals(
            RegisterWindowClass.MEDIUM,
            RegisterResponsiveLayoutPolicy.classify(widthDp = 1280, heightDp = 720, fontScale = 1f),
        )
    }

    @Test
    fun scr767UsesSharedResponsiveMetricsAndIndependentCompactScrolls() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt",
        ).readText()

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val leftScroll = rememberScrollState()"))
        assertTrue(source.contains("val rightScroll = rememberScrollState()"))
        assertTrue(source.contains("if (responsive.isCompact) Modifier.verticalScroll(leftScroll)"))
        assertTrue(source.contains("if (responsive.isCompact) Modifier.verticalScroll(rightScroll)"))
        assertTrue(source.contains("Modifier.width(if (responsive.isCompact) 360.dp else 470.dp)"))
        assertTrue(source.contains("heightIn(min = 80.dp, max = 150.dp)"))
        assertTrue(source.contains("responsive.headerHeightDp.dp"))
        assertTrue(source.contains("responsive.bottomBarHeightDp.dp"))
        assertTrue(source.contains("responsive.screenPaddingDp.dp"))
        assertTrue(source.contains("responsive.cardPaddingDp.dp"))
    }

    @Test
    fun compactFallbackKeepsCriticalRestoreControlsReachable() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt",
        ).readText()

        assertTrue(source.contains("復元・取消用 責任者PIN"))
        assertTrue(source.contains("次回起動時に復元"))
        assertTrue(source.contains("予約取消"))
        assertTrue(source.contains("ロールバック再検証"))
        assertTrue(source.contains("外部から取込"))
        assertTrue(source.contains("AppUpdateDiagnosticsPanelV090(appContext)"))
    }

    @Test
    fun dataProtectionBusinessSafetyLogicIsNotReplacedByResponsiveCode() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt",
        ).readText()

        assertTrue(source.contains("manager.createBackup(actor)"))
        assertTrue(source.contains("manager.verifyBackup(file)"))
        assertTrue(source.contains("manager.stageRestore(file, pin)"))
        assertTrue(source.contains("manager.cancelPendingRestore(pin)"))
        assertTrue(source.contains("report?.restoreReady == true"))
        assertTrue(source.contains("RestoreRollbackSafetyV086.inventory(appContext)"))
        assertFalse(source.contains("DELETE FROM sales", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
    }

    @Test
    fun realDeviceChecksAreDeferredToFinalAcceptance() {
        val releaseNotes = File(root, "docs/V0.91_RELEASE_NOTES.md")
        val requirements = File(root, "docs/V0.91_DATA_PROTECTION_RESPONSIVE.md")
        assertTrue(releaseNotes.isFile)
        assertTrue(requirements.isFile)
        assertTrue(releaseNotes.readText().contains("最終総合実機試験へ繰越"))
        assertTrue(requirements.readText().contains("最終総合実機試験"))

        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
    }
}
