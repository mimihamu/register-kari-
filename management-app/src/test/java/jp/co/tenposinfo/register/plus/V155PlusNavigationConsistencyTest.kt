package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Cross-screen layout contract: same navy header and fixed upper-left back. */
class V155PlusNavigationConsistencyTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register/plus")
    private fun src(name: String) = File(root, name).readText()

    @Test fun sharedPlusChromeMatchesPosNavigationAndSafeArea() {
        val s = src("PlusTopBarV155.kt")
        val chrome = src("PlusScreenChromeV148.kt")
        assertTrue(s.contains("background(PlusNavyV148)"))
        assertTrue(s.contains("onClick = onBack"))
        assertTrue(s.contains("containerColor = Color.White"))
        assertTrue(s.contains("Text(\"戻る\""))
        assertTrue(s.contains("Text(\"つぐレジ＋\""))
        assertTrue(chrome.contains("WindowInsets.safeDrawing"))
    }

    @Test fun sixDriveScreensKeepPinnedHeaderAndScrollableContent() {
        val pages = listOf(
            "GoogleDriveEasyConnectActivity.kt",
            "GoogleDriveAccountActivity.kt",
            "GoogleDriveRecoveryActivity.kt",
            "GoogleDriveDiagnosticsActivity.kt",
            "GoogleDriveSetupGuideActivity.kt",
            "GoogleDriveSyncVerificationActivity.kt",
        )
        for (name in pages) {
            val s = src(name)
            assertTrue(name, s.contains("PlusTopBarV155(title = "))
            assertTrue(name, s.contains("onBack = onClose"))
            assertTrue(name, s.contains("modifier = Modifier.weight(1f).fillMaxWidth()"))
            assertFalse(name, s.contains("OutlinedButton(onClick = onClose) { Text(\"戻る\") }"))
        }
    }

    @Test fun accountAndRecoveryHideMaintenanceButPreserveSyncAndSafeWarnings() {
        val account = src("GoogleDriveAccountActivity.kt")
        assertTrue(account.contains("advancedV155.value"))
        assertTrue(account.contains("onClick = onConnect"))
        assertTrue(account.contains("onClick = onSync"))
        assertTrue(account.contains("onClick = onForceSync"))
        assertTrue(account.contains("onClick = onDisconnect"))
        assertTrue(account.contains("onClick = onOpenRecovery"))
        val recovery = src("GoogleDriveRecoveryActivity.kt")
        assertTrue(recovery.contains("showDetailsV155.value"))
        assertTrue(recovery.contains("onClick = onApplyRecommended"))
        assertTrue(recovery.contains("onClick = onChooseFolder"))
        assertTrue(recovery.contains("onChecklistChanged("))
        assertTrue(recovery.contains("Googleアカウント連携、取込済み売上、Drive上のファイル、SQLiteデータは削除しません"))
    }
}
