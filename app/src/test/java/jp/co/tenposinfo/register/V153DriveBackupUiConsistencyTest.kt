package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive and backup settings expose daily actions first; advanced maintenance stays available. */
class V153DriveBackupUiConsistencyTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun backupHasPinnedNavigationAndClearlySeparatedAdvancedOperation() {
        val s = source("ExternalBackupSettingsActivity.kt")
        assertTrue(s.contains("RegisterTopBarV141(title = \"外部自動バックアップ\", onBack = onClose)"))
        assertTrue(s.contains("var showAdvancedV153 by remember"))
        assertTrue(s.contains("if (showAdvancedV153) {"))
        assertTrue(s.contains("status.lastError"))
        assertTrue(s.contains("onClick = { folderLauncher.launch(destinationUri) }"))
        assertTrue(s.contains("ExternalBackupScheduler.apply("))
        assertTrue(s.contains("AutoBackupAudit.record("))
        assertTrue(s.contains("設定を保存"))
        assertFalse(s.contains("treeUriText?.let { Text(it, color = Color.DarkGray"))
        assertFalse(s.contains("Modifier.width(220.dp).fillMaxHeight()"))
    }

    @Test fun driveScreensHaveNavyHeadersAndLeftAlignedBack() {
        val expectations = mapOf(
            "GoogleDriveEasyConnectActivity.kt" to "Google Drive かんたん接続",
            "GoogleDriveAccountActivity.kt" to "Google Driveアカウント",
            "GoogleDriveDiagnosticsActivity.kt" to "Google Drive診断",
            "GoogleDriveSetupGuideActivity.kt" to "Google Drive初期設定",
        )
        expectations.forEach { (name, title) ->
            val s = source(name)
            assertTrue(name, s.contains("RegisterTopBarV141(title = \"${title}\", onBack = onClose)"))
            assertFalse(name, s.contains("OutlinedButton(onClick = onClose) { Text(\"戻る\") }"))
        }
    }

    @Test fun connectAndAccountScreensKeepOperationsWithoutClutter() {
        val easy = source("GoogleDriveEasyConnectActivity.kt")
        assertTrue(easy.contains("onClick = onConnect"))
        assertTrue(easy.contains("onClick = onSync"))
        assertTrue(easy.contains("onClick = onDisconnect"))
        assertTrue(easy.contains("onClick = onOpenAdvanced"))
        assertTrue(easy.contains("onClick = onOpenSetupGuide"))
        assertFalse(easy.contains("通常はこの画面だけで設定できます"))

        val account = source("GoogleDriveAccountActivity.kt")
        assertTrue(account.contains("val showAdvanced = remember { mutableStateOf(false) }"))
        assertTrue(account.contains("if (showAdvanced.value)"))
        for (name in listOf("onConnect", "onVerify", "onUpload", "onRetry", "onDisconnect", "onDiagnostics", "onConnectionTest")) {
            assertTrue(name, account.contains("onClick = ${name}"))
        }
        assertTrue(account.contains("rememberScrollState()"))
        assertTrue(account.contains("Driveは同期・バックアップ経路"))
    }

    @Test fun diagnosisKeepsLogSafetyAndSplitActions() {
        val diag = source("GoogleDriveDiagnosticsActivity.kt")
        for (name in listOf("onRefresh", "onCopy", "onShare", "onClear", "onOpenAccount", "onUpload")) {
            assertTrue(name, diag.contains("onClick = ${name}"))
        }
        assertTrue(diag.contains("GoogleDriveDiagnosticSanitizer.detail(it)"))
        assertTrue(diag.contains("snapshot.database.recentFailures"))
        assertTrue(diag.contains("Row(modifier = Modifier.fillMaxWidth()"))
    }
}
