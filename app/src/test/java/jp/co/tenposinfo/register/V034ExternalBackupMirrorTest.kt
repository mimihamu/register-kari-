package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V034ExternalBackupMirrorTest {
    @Test
    fun enablingRequiresDocumentTreeDestination() {
        val failure = runCatching {
            ExternalBackupSettingsPolicy.validated(
                ExternalBackupSettings(enabled = true),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)

        val valid = ExternalBackupSettingsPolicy.validated(
            ExternalBackupSettings(
                enabled = true,
                treeUri = "content://com.example.documents/tree/backup",
                destinationLabel = "バックアップ",
            ),
        )
        assertTrue(valid.enabled)
        assertEquals("バックアップ", valid.destinationLabel)
    }

    @Test
    fun pendingMirrorIsDestinationSpecificAndOldestFirst() {
        val entries = listOf(
            ExternalBackupMirrorEntry(
                fileName = "TSUGUREGI_backup_20260805_020000.tgbak",
                createdAt = 200L,
                valid = true,
                state = AutoBackupFileState.READY,
                mirroredDestinationKey = null,
            ),
            ExternalBackupMirrorEntry(
                fileName = "TSUGUREGI_backup_20260805_010000.tgbak",
                createdAt = 100L,
                valid = true,
                state = AutoBackupFileState.READY,
                mirroredDestinationKey = "other-destination",
            ),
            ExternalBackupMirrorEntry(
                fileName = "TSUGUREGI_backup_20260805_030000.tgbak",
                createdAt = 300L,
                valid = true,
                state = AutoBackupFileState.READY,
                mirroredDestinationKey = "current-destination",
            ),
            ExternalBackupMirrorEntry(
                fileName = "TSUGUREGI_backup_20260805_040000.tgbak",
                createdAt = 400L,
                valid = false,
                state = AutoBackupFileState.CORRUPT,
                mirroredDestinationKey = null,
            ),
        )

        val pending = ExternalBackupMirrorPolicy.pending(entries, "current-destination")

        assertEquals(
            listOf(
                "TSUGUREGI_backup_20260805_010000.tgbak",
                "TSUGUREGI_backup_20260805_020000.tgbak",
            ),
            pending.map { it.fileName },
        )
    }

    @Test
    fun partialNameKeepsSafeBackupName() {
        assertEquals(
            "TSUGUREGI_backup_20260805_010000.tgbak.partial",
            ExternalBackupFileNamePolicy.partialName(
                "TSUGUREGI_backup_20260805_010000.tgbak",
            ),
        )
        assertTrue(
            runCatching { ExternalBackupFileNamePolicy.partialName("../register.db") }.isFailure,
        )
    }

    @Test
    fun sourceHooksAndManifestStayConnected() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val application = File(root, "RegisterApplication.kt").readText()
        val autoBackup = File(root, "AutoBackup.kt").readText()
        val settings = File(root, "AutoBackupSettingsActivity.kt").readText()
        val mirror = File(root, "ExternalBackupSync.kt").readText()
        val protection = File(root, "DataProtection.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(application.contains("ExternalBackupScheduler.apply(this)"))
        assertTrue(application.contains("is ExternalBackupSettingsActivity"))
        assertTrue(autoBackup.contains("ExternalBackupScheduler.enqueueNow"))
        assertTrue(settings.contains("ExternalBackupSettingsActivity::class.java"))
        assertTrue(mirror.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue(mirror.contains("archiveSha256"))
        assertTrue(mirror.contains(".partial"))
        assertTrue(protection.contains("fun copyVerifiedBackup"))
        assertTrue(manifest.contains("android:name=\".ExternalBackupSettingsActivity\""))
        assertFalse(manifest.contains("<activity-alias"))
    }
}
