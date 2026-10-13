package jp.co.tenposinfo.register

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V104DataProtectionCrashSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun checkpointMustBeCompleteBeforeRawFallbackCopyCanStart() {
        assertTrue(BackupSnapshotFallbackPolicyV104.mayAttemptCopy(WalCheckpointResultV104(0, 0, 0)))
        assertTrue(BackupSnapshotFallbackPolicyV104.mayAttemptCopy(WalCheckpointResultV104(0, -1, -1)))
        assertTrue(BackupSnapshotFallbackPolicyV104.mayAttemptCopy(WalCheckpointResultV104(0, 12, 12)))
        assertFalse(BackupSnapshotFallbackPolicyV104.mayAttemptCopy(WalCheckpointResultV104(1, 12, 12)))
        assertFalse(BackupSnapshotFallbackPolicyV104.mayAttemptCopy(WalCheckpointResultV104(0, 12, 11)))
    }

    @Test
    fun fallbackRequiresWalToRemainEmptyAfterExclusiveTransactionIsAcquired() {
        val directory = Files.createTempDirectory("v104-wal-policy").toFile()
        try {
            val wal = File(directory, "register.db-wal")
            assertTrue(BackupSnapshotFallbackPolicyV104.walQuiescent(wal))
            wal.writeBytes(byteArrayOf())
            assertTrue(BackupSnapshotFallbackPolicyV104.walQuiescent(wal))
            wal.writeBytes(byteArrayOf(1))
            assertFalse(BackupSnapshotFallbackPolicyV104.walQuiescent(wal))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun atomicReplaceReplacesExistingTargetWithoutPreDeleteContract() {
        val directory = Files.createTempDirectory("v104-atomic-replace").toFile()
        try {
            val source = File(directory, "source.tmp").apply { writeText("new") }
            val target = File(directory, "target.db").apply { writeText("old") }

            CrashSafeFileReplaceV104.replace(source, target)

            assertEquals("new", target.readText())
            assertFalse(source.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun crossDirectoryReplaceStagesBesideTargetThenAtomicallyMoves() {
        val rootDir = Files.createTempDirectory("v104-cross-dir").toFile()
        val sourceDir = File(rootDir, "source").apply { mkdirs() }
        val targetDir = File(rootDir, "target").apply { mkdirs() }
        try {
            val source = File(sourceDir, "source.tmp").apply { writeText("replacement") }
            val target = File(targetDir, "target.db").apply { writeText("original") }

            CrashSafeFileReplaceV104.replace(source, target)

            assertEquals("replacement", target.readText())
            assertFalse(source.exists())
            assertTrue(targetDir.listFiles().orEmpty().none { it.name.contains(".atomic-replace-") })
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun dataProtectionSourceOrdersCheckpointLockWalCheckAndCopy() {
        val source = dataProtectionSource()
        val snapshotStart = source.indexOf("private fun snapshotDatabase")
        val inspectStart = source.indexOf("private fun inspectDatabaseFile", snapshotStart)
        assertTrue(snapshotStart >= 0)
        assertTrue(inspectStart > snapshotStart)
        val body = source.substring(snapshotStart, inspectStart)

        val checkpoint = body.indexOf("PRAGMA wal_checkpoint(TRUNCATE)")
        val transaction = body.indexOf("database.beginTransaction()")
        val walCheck = body.indexOf("BackupSnapshotFallbackPolicyV104.walQuiescent(wal)")
        val copy = body.indexOf("DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)")
        assertTrue(checkpoint >= 0)
        assertTrue(transaction > checkpoint)
        assertTrue(walCheck > transaction)
        assertTrue(copy > walCheck)
        assertTrue(body.contains("BackupSnapshotFallbackPolicyV104.MAX_ATTEMPTS"))
        assertTrue(body.contains("WALを安全に固定できないためbackupを中止しました"))
    }

    @Test
    fun atomicReplaceNoLongerDeletesCurrentTargetBeforeReplacement() {
        val source = dataProtectionSource()
        val start = source.indexOf("internal fun atomicReplace")
        val end = source.indexOf("internal fun readSimpleProperties", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)
        assertTrue(body.contains("CrashSafeFileReplaceV104.replace(source, target)"))
        assertFalse(body.contains("target.delete()"))
        assertFalse(body.contains("source.copyTo(target"))

        val helper = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/DataProtectionCrashSafetyV104.kt",
        ).readText()
        assertTrue(helper.contains("Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)"))
        val replaceBody = helper.substringAfter("fun replace(source: File, target: File)").substringBefore("private fun copyAndSync")
        assertFalse(replaceBody.contains("target.delete()"))
        assertTrue(replaceBody.contains("runCatching { source.delete() }"))
        assertFalse(replaceBody.contains("require(source.delete()"))
    }

    @Test
    fun releaseIdentityAndDataProtectionDocumentationAreExplicit() {
        val design = File(root, "docs/V1.04_DATA_PROTECTION_CRASH_SAFETY.md")
        val notes = File(root, "docs/V1.04_RELEASE_NOTES.md")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)

        val historicalNotes = notes.readText()
        assertTrue(historicalNotes.contains("versionCode `134`"))
        assertTrue(historicalNotes.contains("versionName `1.04.0-dev.1`"))
        assertTrue(design.readText().contains("WAL"))
        assertTrue(design.readText().contains("ATOMIC_MOVE"))
        assertTrue(historicalNotes.contains("最終総合実機試験"))

        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains("DATA_PROTECTION_WAL_SAFE_FALLBACK=true"))
        assertTrue(workflow.contains("DATA_PROTECTION_ATOMIC_REPLACE=true"))
        assertTrue(workflow.contains("DATA_PROTECTION_TARGET_PREDELETE_REMOVED=true"))
    }

    private fun dataProtectionSource(): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt",
    ).readText()
}
