package jp.co.tenposinfo.register

import java.io.File

object AutoBackupTemporaryFilePolicy {
    const val DEFAULT_STALE_MILLIS = 60L * 60L * 1000L

    fun cleanup(
        backupDir: File,
        cacheDir: File,
        now: Long = System.currentTimeMillis(),
        staleMillis: Long = DEFAULT_STALE_MILLIS,
    ): List<String> {
        require(staleMillis > 0L) { "一時ファイル保持時間は1ミリ秒以上が必要です" }
        val threshold = now - staleMillis
        val deleted = mutableListOf<String>()

        backupDir.listFiles().orEmpty()
            .filter { shouldDeleteArchiveTemporary(it, threshold) }
            .forEach { file ->
                if (file.delete()) deleted += file.absolutePath
            }

        cacheDir.listFiles().orEmpty()
            .filter { shouldDeleteBackupCache(it, threshold) }
            .forEach { directory ->
                if (directory.deleteRecursively()) deleted += directory.absolutePath
            }

        return deleted
    }

    internal fun shouldDeleteArchiveTemporary(file: File, threshold: Long): Boolean =
        file.isFile && file.name.endsWith(".tmp") && file.lastModified() < threshold

    internal fun shouldDeleteBackupCache(file: File, threshold: Long): Boolean =
        file.isDirectory && file.name.startsWith("backup-") && file.lastModified() < threshold
}
