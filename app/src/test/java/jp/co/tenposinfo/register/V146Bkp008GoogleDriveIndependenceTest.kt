package jp.co.tenposinfo.register

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
