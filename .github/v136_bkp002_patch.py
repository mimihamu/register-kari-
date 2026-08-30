from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt",
    "                    source.copyTo(target, overwrite = true)\n",
    "                    DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)\n",
    "bounded fallback copy",
)

replace_once(
    "app/src/test/java/jp/co/tenposinfo/register/V104DataProtectionCrashSafetyTest.kt",
    '        val copy = body.indexOf("source.copyTo(target, overwrite = true)")\n',
    '        val copy = body.indexOf("DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)")\n',
    "v104 copy contract",
)

helper = '''package jp.co.tenposinfo.register

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream

/**
 * BKP-002: legacy SQLite fallback must never keep the sales database writer lock for an
 * unbounded file copy. Modern SQLite uses VACUUM INTO before reaching this path.
 */
internal object DataProtectionOnlineBackupV136 {
    const val PERFORMANCE_TARGET_TRANSACTIONS = 10_000
    const val PERFORMANCE_TARGET_MILLIS = 30_000L
    const val MAX_FALLBACK_WRITER_BLOCK_MILLIS = 2_000L
    private const val COPY_BUFFER_BYTES = 64 * 1024

    fun budgetExceeded(startedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - startedAtMillis > MAX_FALLBACK_WRITER_BLOCK_MILLIS

    fun copyWithinWriterBlockBudget(
        source: File,
        target: File,
        elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    ) {
        require(source.isFile) { "DB fallback sourceが見つかりません" }
        target.delete()
        val startedAt = elapsedRealtime()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        try {
            source.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        requireWithinBudget(startedAt, elapsedRealtime())
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        requireWithinBudget(startedAt, elapsedRealtime())
                    }
                    output.flush()
                }
            }
            requireWithinBudget(startedAt, elapsedRealtime())
            require(target.isFile && target.length() == source.length()) {
                "DB fallback snapshotのsizeが一致しません"
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun requireWithinBudget(startedAtMillis: Long, nowMillis: Long) {
        require(!budgetExceeded(startedAtMillis, nowMillis)) {
            "販売を長時間停止しないため旧SQLite fallback backupを中止しました: " +
                "writer-block>${MAX_FALLBACK_WRITER_BLOCK_MILLIS}ms"
        }
    }
}
'''
Path("app/src/main/java/jp/co/tenposinfo/register/DataProtectionOnlineBackupV136.kt").write_text(helper)

test = '''package jp.co.tenposinfo.register

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
        assertTrue(source.contains("database.execSQL(\\\"VACUUM INTO '$escaped'\\\")"))
        assertTrue(source.contains("BackupSnapshotFallbackPolicyV104.MAX_ATTEMPTS"))
        assertTrue(source.contains("database.endTransaction()"))
        assertTrue(source.contains("WALを安全に固定できないためbackupを中止しました"))
    }
}
'''
Path("app/src/test/java/jp/co/tenposinfo/register/V140DataProtectionOnlineBackupTest.kt").write_text(test)
