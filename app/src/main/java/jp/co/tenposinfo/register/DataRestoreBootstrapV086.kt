package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.util.Date

private const val REGISTER_DATABASE_NAME_V086 = "register.db"
private val ROLLBACK_FILE_PATTERN_V086 = Regex("rollback-register-(\\d+)\\.db")

internal data class RestoreRollbackSnapshotV086(
    val file: File,
    val createdAt: Long,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class RestoreRollbackInventoryV086(
    val count: Int,
    val latest: RestoreRollbackSnapshotV086?,
    val latestError: String? = null,
)

/**
 * v0.86: 復元前の元DBをWAL込みで確定した、一貫性検証済みスナップショットとして保持する。
 * v1.16: checkpointをTRUNCATEまで強化し、正本ファイルだけをコピーできる状態を明示的に固定する。
 *
 * - 現DBをPRAGMA wal_checkpoint(TRUNCATE)してWALを0 byteまで確定してからコピーする。
 * - コピー先は一時ファイルでSQLite整合性・外部キー・SHA-256を検証してから確定する。
 * - 復元失敗時は検証済みスナップショットから一時ファイル経由で元DBを戻す。
 * - ロールバック作成に失敗した場合は現DB/WALを一切削除せず復元を中止する。
 */
internal object RestoreRollbackSafetyV086 {
    fun createVerifiedSnapshot(
        sourceDatabase: File,
        restoreDir: File,
    ): RestoreRollbackSnapshotV086 {
        require(sourceDatabase.isFile) { "元DBファイルが見つかりません" }
        restoreDir.mkdirs()
        val createdAt = System.currentTimeMillis()
        val target = File(restoreDir, "rollback-register-$createdAt.db")
        val temporary = File(restoreDir, "rollback-register-$createdAt.db.tmp")
        temporary.delete()
        try {
            checkpointAndVerifyCurrentDatabase(sourceDatabase)
            sourceDatabase.copyTo(temporary, overwrite = true)
            val staged = verifySnapshot(temporary, createdAt)
            DataProtectionManager.atomicReplace(temporary, target)
            val committed = verifySnapshot(target, createdAt)
            require(committed.sha256 == staged.sha256) { "ロールバックDB確定後のSHA-256が一致しません" }
            return committed
        } finally {
            temporary.delete()
        }
    }

    fun restoreVerifiedSnapshot(
        snapshot: RestoreRollbackSnapshotV086,
        targetDatabase: File,
    ): RestoreRollbackSnapshotV086 {
        val source = verifySnapshot(snapshot.file, snapshot.createdAt)
        require(source.sha256 == snapshot.sha256) { "ロールバックDBのSHA-256が作成時から変化しています" }
        targetDatabase.parentFile?.mkdirs()
        val temporary = File(targetDatabase.parentFile, "${targetDatabase.name}.rollback-restore.tmp")
        temporary.delete()
        try {
            snapshot.file.copyTo(temporary, overwrite = true)
            val staged = verifySnapshot(temporary, snapshot.createdAt)
            require(staged.sha256 == snapshot.sha256) { "ロールバック復旧用コピーのSHA-256が一致しません" }
            deleteWalSidecars(targetDatabase)
            DataProtectionManager.atomicReplace(temporary, targetDatabase)
            val restored = verifySnapshot(targetDatabase, snapshot.createdAt)
            require(restored.sha256 == snapshot.sha256) { "元DBへ戻した後のSHA-256が一致しません" }
            return restored
        } finally {
            temporary.delete()
        }
    }

    fun inventory(context: Context): RestoreRollbackInventoryV086 {
        val restoreDir = File(context.filesDir, "data_restore")
        val files = restoreDir.listFiles().orEmpty()
            .filter { it.isFile && ROLLBACK_FILE_PATTERN_V086.matches(it.name) }
            .sortedByDescending(::timestampFromName)
        val latest = files.firstOrNull() ?: return RestoreRollbackInventoryV086(0, null)
        return runCatching {
            RestoreRollbackInventoryV086(files.size, verifySnapshot(latest, timestampFromName(latest)))
        }.getOrElse { error ->
            RestoreRollbackInventoryV086(files.size, null, error.message ?: error.javaClass.simpleName)
        }
    }

    fun deleteWalSidecars(database: File) {
        File(database.absolutePath + "-wal").delete()
        File(database.absolutePath + "-shm").delete()
    }

    private fun checkpointAndVerifyCurrentDatabase(databaseFile: File) {
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            // v0.86 compatibility marker: PRAGMA wal_checkpoint(FULL)
            // v1.16はRESTART相当の排他待ちに加え、成功時にWALを0 byteへ切り詰めるTRUNCATEを使用する。
            val checkpoint = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                require(cursor.moveToFirst()) { "WAL checkpoint結果を取得できません" }
                val busy = cursor.getInt(0)
                val logFrames = cursor.getInt(1)
                val checkpointedFrames = cursor.getInt(2)
                Triple(busy, logFrames, checkpointedFrames)
            }
            require(checkpoint.first == 0) { "WAL checkpointが他接続により完了できませんでした" }
            require(checkpoint.second == checkpoint.third) {
                "WAL checkpointが未完了です: ${checkpoint.third}/${checkpoint.second} frames"
            }
            requireIntegrity(database, "元DB")
        } finally {
            database.close()
        }

        // TRUNCATE成功後に接続を閉じた状態で、main DB単体コピーが安全なことをもう一度確認する。
        val wal = File(databaseFile.absolutePath + "-wal")
        require(!wal.exists() || wal.length() == 0L) {
            "WALを0 byteへ確定できていないため復元前ロールバックを作成できません"
        }
    }

    private fun verifySnapshot(file: File, createdAt: Long): RestoreRollbackSnapshotV086 {
        require(file.isFile && file.length() > 0L) { "ロールバックDBが空または存在しません" }
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            requireIntegrity(database, "ロールバックDB")
        } finally {
            database.close()
        }
        return RestoreRollbackSnapshotV086(
            file = file,
            createdAt = createdAt,
            sizeBytes = file.length(),
            sha256 = DataProtectionManager.sha256(file),
        )
    }

    private fun requireIntegrity(database: SQLiteDatabase, label: String) {
        val integrity = database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        require(integrity.size == 1 && integrity.single().equals("ok", ignoreCase = true)) {
            "$label のSQLite整合性エラー: ${integrity.joinToString(" / ")}"
        }
        val foreignKeyViolations = database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count++
            count
        }
        require(foreignKeyViolations == 0) { "$label に外部キー不整合があります: $foreignKeyViolations 件" }
    }

    private fun timestampFromName(file: File): Long =
        ROLLBACK_FILE_PATTERN_V086.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            ?: file.lastModified()
}

/**
 * v0.86以降の起動時復元Provider。
 * v0.83の起動順序（initOrder=1000）は維持しつつ、復元前ロールバックをWAL-safeにする。
 * v1.16ではmigrationと復元後最終検証まで同じrollback境界へ含める。
 */
class DataRestoreBootstrapProviderV086 : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(PendingRestoreApplierV086::applyIfPresent)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal object PendingRestoreApplierV086 {
    fun applyIfPresent(context: Context) {
        val restoreDir = File(context.filesDir, "data_restore")
        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        val pendingContent = File(restoreDir, "pending-content-v136")
        val database = context.getDatabasePath(REGISTER_DATABASE_NAME_V086)

        // 予約計画と候補DBの組がない起動は、取消・予約途中異常で残ったフェンスだけを回収する。
        if (!planFile.isFile || !pending.isFile) {
            BackupContentBundleV136.removePending(pendingContent)
            PendingRestoreWriteFenceV116.remove(database)
            return
        }

        val resultFile = File(restoreDir, "restore-result.txt")
        val plan = runCatching {
            DataProtectionManager.readSimpleProperties(planFile.readText(Charsets.UTF_8))
        }.getOrElse { error ->
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元計画を読み取れません: ${error.message}")
        }
        val expectedHash = plan["database_sha256"]
            ?: return failWithoutReplacement(planFile, pending, resultFile, database, "復元計画にSHA-256がありません")
        val actualHash = runCatching { DataProtectionManager.sha256(pending) }.getOrElse { error ->
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBを読み取れません: ${error.message}")
        }
        if (actualHash != expectedHash) {
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBのSHA-256が一致しません")
        }
        val contentMode = plan["content_bundle"] ?: "LEGACY_DB_ONLY"
        if (contentMode == BackupContentBundleV136.FORMAT && !BackupContentBundleV136.hasStagedContent(pendingContent)) {
            return failWithoutReplacement(planFile, pending, resultFile, database, "BKP-003復元contentがありません")
        }
        if (contentMode != BackupContentBundleV136.FORMAT) {
            BackupContentBundleV136.removePending(pendingContent)
        }

        database.parentFile?.mkdirs()
        val hadCurrent = database.isFile

        val rollback = if (hadCurrent) {
            runCatching {
                RestoreRollbackSafetyV086.createVerifiedSnapshot(database, restoreDir)
            }.getOrElse { error ->
                return failWithoutReplacement(
                    planFile,
                    pending,
                    resultFile,
                    database,
                    "復元中止・元DB保持。復元前ロールバックを安全に作成できません: ${error.message}",
                )
            }
        } else {
            null
        }

        var contentRollback: BackupContentBundleV136.Rollback? = null
        try {
            RestoreRollbackSafetyV086.deleteWalSidecars(database)
            DataProtectionManager.atomicReplace(pending, database)

            // 候補DBに過去の復元予約triggerが含まれていても、正本化前に必ず除去する。
            PendingRestoreWriteFenceV116.remove(database)

            // 配置直後は候補DBそのものの最低限の構造を確認する。
            verifyRestoredDatabase(database)

            // v1.16: legacy migration / 後付けschema ensure / user_version / index検査までrollback境界内で完了させる。
            DatabaseRecoveryIntegrityV116.migrateAndVerify(context)

            // BKP-005: identity/generationと採番floorをrollback境界内で確定する。
            RestoreTerminalMigrationV136.apply(database, plan)

            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // BKP-003: DBが正本として成立した後に設定・画像を適用する。現在値はrollback保持する。
            contentRollback = BackupContentBundleV136.applyStagedWithRollback(context, pendingContent, restoreDir)

            // sync_outbox本体はDBに含まれる。staging JSONは派生キャッシュなので、復元時は
            // PROCESSING/STAGEDを再生成可能な状態へ戻して再送を継続する。
            JournalOutboxStore(context).use { outbox ->
                outbox.recoverStaleProcessing(Long.MAX_VALUE)
                outbox.requeueStaged()
            }
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // 成功記録の書込みまでrollback snapshotを保持する。
            resultFile.writeText(
                "復元成功: ${plan["backup_file"].orEmpty()} / ${Date()} / " +
                    "ロールバック=${rollback?.file?.name ?: "なし"}" +
                    (rollback?.let { " / rollback-sha256=${it.sha256}" } ?: "") +
                    " / BKP-003=${contentMode}" +
                    " / BKP-005=${plan["restore_mode"].orEmpty()}" +
                    " / storeId=${plan["target_store_id"].orEmpty()}" +
                    " / oldTerminalId=${plan["source_terminal_id"].orEmpty()}" +
                    " / newTerminalId=${plan["target_terminal_id"].orEmpty()}" +
                    " / source-generation=${plan["source_generation"].orEmpty()}" +
                    " / generation=${plan["target_generation"].orEmpty()}" +
                    " / sale-floor=${plan["sale_sequence_floor"].orEmpty()}" +
                    " / confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}",
                Charsets.UTF_8,
            )
            planFile.delete()
            BackupContentBundleV136.removePending(pendingContent)
            contentRollback?.discard()
            contentRollback = null
        } catch (error: Throwable) {
            val contentRollbackResult = runCatching {
                contentRollback?.restore()
                if (contentRollback != null) " / 設定・画像ロールバック完了" else ""
            }.getOrElse { rollbackError -> " / 設定・画像ロールバック失敗: ${rollbackError.message}" }
            val rollbackResult = if (rollback != null) {
                runCatching {
                    val restored = RestoreRollbackSafetyV086.restoreVerifiedSnapshot(rollback, database)
                    // rollback snapshotには予約フェンスも含まれるため、元DBへ戻した直後に除去して再検証する。
                    PendingRestoreWriteFenceV116.remove(database)
                    DatabaseRecoveryIntegrityV116.verifyFinal(context)
                    "元DBへロールバック完了 / ${restored.file.name} / sha256=${restored.sha256}"
                }.getOrElse { rollbackError ->
                    "元DBロールバック失敗: ${rollbackError.message} / 安全スナップショット=${rollback.file.name}"
                }
            } else {
                database.delete()
                RestoreRollbackSafetyV086.deleteWalSidecars(database)
                "復元前の元DBなし"
            }
            planFile.delete()
            pending.delete()
            BackupContentBundleV136.removePending(pendingContent)
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult$contentRollbackResult",
                Charsets.UTF_8,
            )
        }
    }

    private fun verifyRestoredDatabase(file: File) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val integrity = database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            require(integrity.size == 1 && integrity.single().equals("ok", ignoreCase = true)) {
                "復元DBのSQLite整合性エラー: ${integrity.joinToString()}"
            }
            val foreignKeyViolations = database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count++
                count
            }
            require(foreignKeyViolations == 0) {
                "復元DBに外部キー不整合があります: $foreignKeyViolations 件"
            }
            val tables = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null,
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val missing = DataProtectionTablePolicy.requiredTables - tables
            require(missing.isEmpty()) { "復元DBの必須テーブル不足: ${missing.sorted().joinToString()}" }
        } finally {
            database.close()
        }
    }

    private fun insertRestoreAudit(file: File, plan: Map<String, String>) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS operation_audit (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "event_type TEXT NOT NULL,reference_id INTEGER NOT NULL,detail TEXT NOT NULL," +
                    "operator_name TEXT NOT NULL,created_at INTEGER NOT NULL)",
            )
            database.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "DATA_RESTORE_APPLIED")
                    put("reference_id", 0)
                    put(
                        "detail",
                        "${plan["backup_file"].orEmpty()} / 起動時復元 / v1.16 WAL・migration-safe rollback / " +
                            "BKP-005=${plan["restore_mode"].orEmpty()} / storeId=${plan["target_store_id"].orEmpty()} / " +
                            "oldTerminalId=${plan["source_terminal_id"].orEmpty()} / " +
                            "newTerminalId=${plan["target_terminal_id"].orEmpty()} / " +
                            "source-generation=${plan["source_generation"].orEmpty()} / " +
                            "generation=${plan["target_generation"].orEmpty()} / " +
                            "sale-floor=${plan["sale_sequence_floor"].orEmpty()} / " +
                            "confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}",
                    )
                    put("operator_name", plan["actor_name"].orEmpty().ifBlank { "責任者" })
                    put("created_at", System.currentTimeMillis())
                },
            )
        } finally {
            database.close()
        }
    }

    private fun failWithoutReplacement(
        plan: File,
        pending: File,
        result: File,
        database: File,
        message: String,
    ) {
        plan.delete()
        pending.delete()
        BackupContentBundleV136.removePending(File(plan.parentFile, "pending-content-v136"))
        val fenceCleanup = runCatching { PendingRestoreWriteFenceV116.remove(database) }
            .exceptionOrNull()
            ?.let { " / フェンス解除失敗: ${it.message}" }
            .orEmpty()
        result.writeText("復元予約を破棄・元DB保持: $message$fenceCleanup", Charsets.UTF_8)
    }
}
