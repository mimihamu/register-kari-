package jp.co.tenposinfo.register

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V140DataProtectionOnlineBackupTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun formalPerformanceTargetsAndWriterBlockBudgetAreExplicit() {
        assertEquals(10_000, DataProtectionOnlineBackupV136.PERFORMANCE_TARGET_TRANSACTIONS)
        assertEquals(30_000L, DataProtectionOnlineBackupV136.PERFORMANCE_TARGET_MILLIS)
        assertTrue(DataProtectionOnlineBackupV136.MAX_FALLBACK_WRITER_BLOCK_MILLIS in 1..5_000L)
        assertFalse(DataProtectionOnlineBackupV136.budgetExceeded(1_000L, 3_000L))
        assertTrue(DataProtectionOnlineBackupV136.budgetExceeded(1_000L, 3_001L))
    }

    @Test
    fun fallbackCopyAbortsInsteadOfHoldingSalesWriterIndefinitely() {
        val dir = Files.createTempDirectory("v140-bkp002").toFile()
        try {
            val source = File(dir, "source.db").apply { writeBytes(ByteArray(256 * 1024) { 1 }) }
            val target = File(dir, "target.db")
            var clock = 0L
            val error = assertThrows(IllegalArgumentException::class.java) {
                DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target) {
                    clock += 2_001L
                    clock
                }
            }
            assertTrue(error.message.orEmpty().contains("販売を長時間停止しない"))
            assertFalse(target.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun productionSnapshotPrefersLiveVacuumIntoAndBoundsOnlyLegacyFallback() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt").readText()
        val start = source.indexOf("private fun snapshotDatabase")
        val end = source.indexOf("private fun inspectDatabaseFile", start)
        assertTrue(start >= 0 && end > start)
        val body = source.substring(start, end)

        val vacuum = body.indexOf("VACUUM INTO")
        val checkpoint = body.indexOf("PRAGMA wal_checkpoint(TRUNCATE)")
        val transaction = body.indexOf("database.beginTransaction()")
        val walCheck = body.indexOf("BackupSnapshotFallbackPolicyV104.walQuiescent(wal)")
        val boundedCopy = body.indexOf("DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)")
        assertTrue(vacuum >= 0)
        assertTrue(checkpoint > vacuum)
        assertTrue(transaction > checkpoint)
        assertTrue(walCheck > transaction)
        assertTrue(boundedCopy > walCheck)
        assertFalse(body.contains("source.copyTo(target, overwrite = true)"))
    }

    @Test
    fun modernPathRemainsConsistentSnapshotAndLegacyCrashFenceIsPreserved() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt").readText()
        assertTrue(source.contains("database.execSQL(\"VACUUM INTO '$escaped'\")"))
        assertTrue(source.contains("BackupSnapshotFallbackPolicyV104.MAX_ATTEMPTS"))
        assertTrue(source.contains("database.endTransaction()"))
        assertTrue(source.contains("WALを安全に固定できないためbackupを中止しました"))
    }
}
