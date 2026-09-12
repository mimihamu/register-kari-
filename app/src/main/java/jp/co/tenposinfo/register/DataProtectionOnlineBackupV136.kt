package jp.co.tenposinfo.register

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
