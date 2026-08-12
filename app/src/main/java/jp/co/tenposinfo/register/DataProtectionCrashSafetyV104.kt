package jp.co.tenposinfo.register

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

internal data class WalCheckpointResultV104(
    val busy: Int,
    val logFrames: Int,
    val checkpointedFrames: Int,
) {
    val complete: Boolean
        get() = busy == 0 && logFrames == checkpointedFrames
}

/**
 * `VACUUM INTO` が利用できない端末向けの生DBコピー可否を厳格化する。
 *
 * TRUNCATE checkpoint 完了後にEXCLUSIVE transactionを取得し、その時点でもWALが
 * 空のままである場合だけ main database file をコピーする。checkpointとlock取得の間に
 * 別writerがcommitした場合はWALが再び非0になるため、その試行は破棄してやり直す。
 */
internal object BackupSnapshotFallbackPolicyV104 {
    const val MAX_ATTEMPTS = 3

    fun mayAttemptCopy(checkpoint: WalCheckpointResultV104): Boolean = checkpoint.complete

    fun walQuiescent(walFile: File): Boolean = !walFile.exists() || walFile.length() == 0L
}

/**
 * 既存targetを先に削除せず、targetと同じdirectoryにstagingした後でatomic moveする。
 *
 * minSdk 26では java.nio.file.Files / ATOMIC_MOVE が利用可能。atomic moveを提供できない
 * filesystemでは安全性を落としたcopy/deleteへfallbackせず例外にして、既存targetを保持する。
 */
internal object CrashSafeFileReplaceV104 {
    private const val TEMP_MARKER = ".atomic-replace-"

    fun replace(source: File, target: File) {
        require(source.isFile) { "置換元ファイルが見つかりません" }
        val targetDir = requireNotNull(target.parentFile) { "置換先directoryがありません" }.apply { mkdirs() }
        require(targetDir.isDirectory) { "置換先directoryを作成できません" }

        cleanupStaleSiblingTemps(targetDir, target.name)
        val sameDirectory = source.parentFile?.canonicalFile == targetDir.canonicalFile
        val staged = if (sameDirectory) {
            source
        } else {
            File(targetDir, ".${target.name}$TEMP_MARKER${UUID.randomUUID()}.tmp").also { temporary ->
                copyAndSync(source, temporary)
            }
        }

        try {
            sync(staged)
            Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            require(target.isFile) { "原子的ファイル置換後にtargetを確認できません" }
            if (!sameDirectory) {
                require(source.delete() || !source.exists()) { "置換完了後に元一時ファイルを削除できません" }
            }
        } finally {
            if (!sameDirectory) staged.delete()
        }
    }

    private fun copyAndSync(source: File, target: File) {
        target.delete()
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        require(target.length() == source.length()) { "原子的置換用staging copyのsizeが一致しません" }
    }

    private fun sync(file: File) {
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    private fun cleanupStaleSiblingTemps(directory: File, targetName: String) {
        val prefix = ".$targetName$TEMP_MARKER"
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".tmp") }
            .forEach { it.delete() }
    }
}
