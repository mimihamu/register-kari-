from pathlib import Path
import re

root = Path('.')
activity = root / 'app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt'
main_dir = root / 'app/src/main/java/jp/co/tenposinfo/register'
test_dir = root / 'app/src/test/java/jp/co/tenposinfo/register'
docs_dir = root / 'docs'

s = activity.read_text(encoding='utf-8')

old = '''    val autoStatusStore = remember { AutoBackupStatusStore(appContext) }
    val autoSettingsStore = remember { AutoBackupSettingsStore(appContext) }
'''
new = '''    val autoStatusStore = remember { AutoBackupStatusStore(appContext) }
    val driveSyncStatusStore = remember { GoogleDriveDirectUploadStatusStore(appContext) }
    val autoSettingsStore = remember { AutoBackupSettingsStore(appContext) }
'''
if old not in s:
    raise SystemExit('BKP-008 status store insertion point not found')
s = s.replace(old, new, 1)

old = '''    var autoStatus by remember { mutableStateOf(autoStatusStore.load()) }
    var autoSettings by remember { mutableStateOf(autoSettingsStore.load()) }
'''
new = '''    var autoStatus by remember { mutableStateOf(autoStatusStore.load()) }
    var driveSyncStatus by remember { mutableStateOf(driveSyncStatusStore.load()) }
    var autoSettings by remember { mutableStateOf(autoSettingsStore.load()) }
'''
if old not in s:
    raise SystemExit('BKP-008 state insertion point not found')
s = s.replace(old, new, 1)

pattern = re.compile(r'(?m)^(?P<indent>[ \t]*)autoStatus = autoStatusStore\.load\(\)\n(?P=indent)autoSettings = autoSettingsStore\.load\(\)')
s, count = pattern.subn(lambda m: f"{m.group('indent')}autoStatus = autoStatusStore.load()\n{m.group('indent')}driveSyncStatus = driveSyncStatusStore.load()\n{m.group('indent')}autoSettings = autoSettingsStore.load()", s)
if count < 3:
    raise SystemExit(f'expected at least 3 status refresh points, found {count}')

old = '''                        Text("自動バックアップ", fontWeight = FontWeight.Bold, color = DpNavy)
                        Text(
                            "Z精算後: 常時有効 / 定期: ${if (autoSettings.periodicEnabled) "${autoSettings.cadence.displayName} ${autoSettings.preferredHour}時台" else "OFF"}",
                            color = DpNavy,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("最終結果: ${autoStatus.lastResult.displayName}", color = if (autoStatus.lastResult == AutoBackupResultState.FAILED || autoStatus.lastResult == AutoBackupResultState.SKIPPED_LOW_STORAGE) DpDanger else DpGreen)
                        Text("最終実行: ${autoStatus.lastCompletedAt?.let(::formatTime) ?: "未実行"}", fontSize = 13.sp)
                        Text("次回定期予定: ${autoStatus.nextScheduledAt?.let(::formatTime) ?: if (autoSettings.periodicEnabled) "再登録待ち" else "OFF"}", fontSize = 13.sp)
                        Text("保持: Z精算 ${autoSettings.zRetentionBusinessDays}営業日 / 定期 ${autoSettings.monthlyRetentionMonths}か月", fontSize = 13.sp)
                        autoStatus.lastReason?.let { Text("作成理由: ${it.displayName}", fontSize = 13.sp) }
                        autoStatus.lastRetentionResult?.let { Text("自動整理: $it", fontSize = 13.sp) }
                        autoStatus.lastError?.let { Text("エラー詳細: $it", color = DpDanger, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
'''
new = '''                        val independentStatus = BackupDriveIndependenceV146.snapshot(autoStatus, driveSyncStatus)
                        Text("バックアップ状態（端末復元用）", fontWeight = FontWeight.Bold, color = DpNavy)
                        Text(
                            "自動バックアップ: Z精算後は常時有効 / 定期: ${if (autoSettings.periodicEnabled) "${autoSettings.cadence.displayName} ${autoSettings.preferredHour}時台" else "OFF"}",
                            color = DpNavy,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("最終結果: ${independentStatus.backupResult.displayName}", color = if (autoStatus.lastResult == AutoBackupResultState.FAILED || autoStatus.lastResult == AutoBackupResultState.SKIPPED_LOW_STORAGE) DpDanger else DpGreen)
                        Text("最終実行: ${autoStatus.lastCompletedAt?.let(::formatTime) ?: "未実行"}", fontSize = 13.sp)
                        Text("次回定期予定: ${autoStatus.nextScheduledAt?.let(::formatTime) ?: if (autoSettings.periodicEnabled) "再登録待ち" else "OFF"}", fontSize = 13.sp)
                        Text("保持: Z精算 ${autoSettings.zRetentionBusinessDays}営業日 / 定期 ${autoSettings.monthlyRetentionMonths}か月", fontSize = 13.sp)
                        autoStatus.lastReason?.let { Text("作成理由: ${it.displayName}", fontSize = 13.sp) }
                        autoStatus.lastRetentionResult?.let { Text("自動整理: $it", fontSize = 13.sp) }
                        autoStatus.lastError?.let { Text("エラー詳細: $it", color = DpDanger, fontSize = 13.sp) }
                        Text("バックアップ成功/失敗は、Google Drive売上同期の結果とは独立して判定します。", color = Color.DarkGray, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Google Drive売上同期状態（売上管理アプリ交換用）", fontWeight = FontWeight.Bold, color = DpNavy)
                        Text(
                            "同期状態: ${independentStatus.driveStateLabel}",
                            color = if (driveSyncStatus.blockedCategory != null || driveSyncStatus.permanentFailureCount > 0) DpDanger else DpNavy,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("最終同期: ${driveSyncStatus.lastCompletedAt?.let(::formatTime) ?: "未実行"}", fontSize = 13.sp)
                        Text("送信 ${driveSyncStatus.uploadedCount}件 / 既存 ${driveSyncStatus.duplicateCount}件 / 再試行 ${driveSyncStatus.retryCount}件 / 永久失敗 ${driveSyncStatus.permanentFailureCount}件", fontSize = 13.sp)
                        Text("詳細: ${driveSyncStatus.lastMessage}", color = if (driveSyncStatus.blockedCategory != null) DpDanger else Color.DarkGray, fontSize = 12.sp)
                        Text("※ 売上同期が成功していても、端末復元用バックアップの成功を意味しません。", color = Color.DarkGray, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
'''
if old not in s:
    raise SystemExit('BKP-008 backup status UI block not found')
s = s.replace(old, new, 1)

old = ') { Text("Google Drive・USBへの外部自動保存を設定") }'
new = ') { Text("バックアップ外部保存先（Google Drive・USB）を設定") }'
if old not in s:
    raise SystemExit('BKP-008 external backup label not found')
s = s.replace(old, new, 1)
activity.write_text(s, encoding='utf-8')

(main_dir / 'BackupDriveIndependenceV146.kt').write_text(r'''package jp.co.tenposinfo.register

data class BackupDriveIndependentStatusV146(
    val backupResult: AutoBackupResultState,
    val backupSuccessful: Boolean,
    val driveStateLabel: String,
)

/**
 * BKP-008 contract: terminal-recovery backup and Google Drive sales exchange
 * have independent purposes and independent success conditions.
 */
object BackupDriveIndependenceV146 {
    fun snapshot(
        backup: AutoBackupRuntimeStatus,
        drive: GoogleDriveDirectUploadStatus,
    ): BackupDriveIndependentStatusV146 = BackupDriveIndependentStatusV146(
        backupResult = backup.lastResult,
        backupSuccessful = backup.lastResult == AutoBackupResultState.CREATED,
        driveStateLabel = driveStateLabel(drive),
    )

    fun driveStateLabel(status: GoogleDriveDirectUploadStatus): String = when {
        status.running -> "送信中"
        status.blockedCategory != null -> "要確認（${status.blockedCategory.name}）"
        status.lastCompletedAt == null -> "未実行"
        status.permanentFailureCount > 0 -> "要確認（永久失敗あり）"
        status.retryCount > 0 -> "完了（再試行あり）"
        else -> "完了"
    }
}
''', encoding='utf-8')

(test_dir / 'V146Bkp008GoogleDriveIndependenceTest.kt').write_text(r'''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V146Bkp008GoogleDriveIndependenceTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    @Test
    fun successfulBackupRemainsSuccessfulWhenDriveIsBlocked() {
        val backup = AutoBackupRuntimeStatus(lastResult = AutoBackupResultState.CREATED)
        val healthyDrive = GoogleDriveDirectUploadStatus(lastCompletedAt = 1L, uploadedCount = 2)
        val blockedDrive = GoogleDriveDirectUploadStatus(
            lastCompletedAt = 2L,
            blockedCategory = GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED,
            lastMessage = "再認可が必要",
        )

        val healthy = BackupDriveIndependenceV146.snapshot(backup, healthyDrive)
        val blocked = BackupDriveIndependenceV146.snapshot(backup, blockedDrive)
        assertTrue(healthy.backupSuccessful)
        assertTrue(blocked.backupSuccessful)
        assertEquals(AutoBackupResultState.CREATED, healthy.backupResult)
        assertEquals(AutoBackupResultState.CREATED, blocked.backupResult)
        assertNotEquals(healthy.driveStateLabel, blocked.driveStateLabel)
    }

    @Test
    fun failedBackupRemainsFailedWhenDriveLooksSuccessful() {
        val backup = AutoBackupRuntimeStatus(lastResult = AutoBackupResultState.FAILED)
        val drive = GoogleDriveDirectUploadStatus(lastCompletedAt = 3L, uploadedCount = 10)
        val snapshot = BackupDriveIndependenceV146.snapshot(backup, drive)
        assertFalse(snapshot.backupSuccessful)
        assertEquals(AutoBackupResultState.FAILED, snapshot.backupResult)
        assertEquals("完了", snapshot.driveStateLabel)
    }

    @Test
    fun sourceKeepsBackupAndSalesDriveStatusSeparateOnBackupScreen() {
        fun source(name: String) = File(root, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val activity = source("DataProtectionActivity.kt")
        val autoBackup = source("AutoBackup.kt")
        val drive = source("GoogleDriveDirectUpload.kt")

        assertTrue(activity.contains("AutoBackupStatusStore(appContext)"))
        assertTrue(activity.contains("GoogleDriveDirectUploadStatusStore(appContext)"))
        assertTrue(activity.contains("バックアップ状態（端末復元用）"))
        assertTrue(activity.contains("Google Drive売上同期状態（売上管理アプリ交換用）"))
        assertTrue(activity.contains("バックアップ成功/失敗は、Google Drive売上同期の結果とは独立"))
        assertTrue(activity.contains("売上同期が成功していても、端末復元用バックアップの成功を意味しません"))
        assertTrue(activity.contains("バックアップ外部保存先（Google Drive・USB）を設定"))

        assertTrue(autoBackup.contains("statusStore.completed(reason, AutoBackupResultState.CREATED)"))
        assertFalse(autoBackup.contains("GoogleDriveDirectUploadStatusStore"))
        assertTrue(drive.contains("class GoogleDriveDirectUploadStatusStore"))
        assertTrue(drive.contains("tsuguregi_drive_api_upload_status"))
    }
}
''', encoding='utf-8')

(docs_dir / 'V1.36_BKP_008_GOOGLE_DRIVE_INDEPENDENCE.md').write_text(r'''# v1.36 BKP-008 Google Drive 非依存

正式仕様 v2.5 `BKP-008` を正本とする。

## 正式要件

バックアップは端末障害からつぐレジ全体を復元する機能、Google Drive売上同期はつぐレジ＋との業務データ交換機能として、目的と成功条件を分離する。Drive同期成功をバックアップ成功として扱わず、バックアップ画面で両状態を別表示する。

## 実装確認と変更

既存のバックアップ作成処理は、ローカル暗号化バックアップの作成と `verifyBackup()` が完了した結果を `AutoBackupStatusStore` の `CREATED/FAILED` として保存している。売上交換用Google Drive直接送信は `GoogleDriveDirectUploadStatusStore` の別SharedPreferencesに状態を保存しており、バックアップ成功判定へ参照されていない。この論理分離は維持する。

BKP-008では `SCR-767 データ保全・バックアップ・復元` に次を追加する。

1. `バックアップ状態（端末復元用）` と `Google Drive売上同期状態（売上管理アプリ交換用）` を別表示する。
2. Drive側は送信中、未実行、要確認、再試行あり、完了を既存 `GoogleDriveDirectUploadStatusStore` から表示する。
3. 「売上同期成功は端末復元用バックアップ成功を意味しない」ことを画面上で明示する。
4. SAFによるGoogle Drive/USBへのバックアップミラーは売上同期と混同しないよう `バックアップ外部保存先` と明記する。
5. `BackupDriveIndependenceV146` で、バックアップ成功がDrive状態に依存しない契約を純粋関数として固定する。

## 自動検証

`V146Bkp008GoogleDriveIndependenceTest` で以下を固定する。

- Driveが認証ブロックでもローカルバックアップ `CREATED` は成功のまま。
- Drive送信が完了表示でもローカルバックアップ `FAILED` は失敗のまま。
- バックアップ画面が2つの状態ストアを別々に読み、用途と注意書きを別表示する。
- `AutoBackup.kt` の成功判定が `GoogleDriveDirectUploadStatusStore` を参照しない。

## 実機未確認

- 実GoogleアカウントでDrive認証失効中にバックアップを実行し、バックアップ成功状態が維持されること。
- Drive同期成功中にバックアップを意図的に失敗させ、画面でDrive成功／バックアップ失敗が同時表示されること。
- SAFでGoogle Driveをバックアップ外部保存先に設定した状態と、売上同期状態が混同されないこと。
''', encoding='utf-8')

print('BKP-008 patch prepared')
