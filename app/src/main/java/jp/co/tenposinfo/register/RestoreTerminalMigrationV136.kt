package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale
import java.util.UUID

enum class RestoreTerminalModeV136 {
    SAME_TERMINAL,
    SPARE_TERMINAL,
}

data class RestoreTerminalMigrationRequestV136(
    val mode: RestoreTerminalModeV136,
    val confirmedStoreName: String = "",
    val oldTerminalStopped: Boolean = false,
    /**
     * 最大売上番号の外部確認値。通常は復元前DB・journal・送信成功ACKから自動算出する。
     * 新品の予備端末など、その端末がバックアップ後の旧端末履歴を持たない場合は
     * Drive/既存イベント側で確認した最大値を入力する。該当なしは0。
     */
    val remoteAckMaxSaleId: Long? = null,
) {
    companion object {
        fun sameTerminal(): RestoreTerminalMigrationRequestV136 =
            RestoreTerminalMigrationRequestV136(RestoreTerminalModeV136.SAME_TERMINAL)
    }
}

data class RestoreTerminalMigrationPlanV136(
    val mode: RestoreTerminalModeV136,
    val storeId: String,
    val sourceTerminalId: String,
    val targetTerminalId: String,
    val sourceGeneration: Long,
    val targetGeneration: Long,
    val saleSequenceFloor: Long,
    val remoteAckMaxSaleId: Long,
) {
    fun displaySummary(): String = when (mode) {
        RestoreTerminalModeV136.SAME_TERMINAL ->
            "同一端末復旧 / storeId=$storeId / oldTerminalId=$sourceTerminalId / newTerminalId=$targetTerminalId / " +
                "sourceGeneration=$sourceGeneration / generation=$targetGeneration / 採番下限=$saleSequenceFloor / " +
                "確認最大番号=$remoteAckMaxSaleId"
        RestoreTerminalModeV136.SPARE_TERMINAL ->
            "予備端末移行 / storeId=$storeId / oldTerminalId=$sourceTerminalId / newTerminalId=$targetTerminalId / " +
                "sourceGeneration=$sourceGeneration / generation=$targetGeneration / 採番下限=$saleSequenceFloor / " +
                "確認最大番号=$remoteAckMaxSaleId"
    }
}

object RestoreTerminalMigrationPolicyV136 {
    fun plan(
        request: RestoreTerminalMigrationRequestV136,
        backupStoreName: String?,
        backupIdentity: SalesJournalIdentity,
        currentIdentity: SalesJournalIdentity,
        currentKnownMaxSaleId: Long,
        backupKnownMaxSaleId: Long,
        newTerminalId: () -> String = {
            "TERMINAL-${UUID.randomUUID().toString().uppercase(Locale.ROOT)}"
        },
    ): RestoreTerminalMigrationPlanV136 {
        require(currentKnownMaxSaleId >= 0L && backupKnownMaxSaleId >= 0L) { "売上採番最大値が不正です" }
        val externalMax = request.remoteAckMaxSaleId ?: 0L
        require(externalMax >= 0L) { "Drive ACK/既存イベントの最大売上番号が不正です" }
        val floor = maxOf(currentKnownMaxSaleId, backupKnownMaxSaleId, externalMax)
        val maxGeneration = maxOf(backupIdentity.generation, currentIdentity.generation).coerceAtLeast(1L)

        return when (request.mode) {
            RestoreTerminalModeV136.SAME_TERMINAL -> {
                require(backupIdentity.storeId == currentIdentity.storeId) { "同一端末復旧のstoreIdが一致しません" }
                require(backupIdentity.terminalId == currentIdentity.terminalId) { "同一端末復旧のterminalIdが一致しません" }
                RestoreTerminalMigrationPlanV136(
                    mode = request.mode,
                    storeId = backupIdentity.storeId,
                    sourceTerminalId = backupIdentity.terminalId,
                    targetTerminalId = currentIdentity.terminalId,
                    sourceGeneration = backupIdentity.generation,
                    targetGeneration = maxGeneration,
                    saleSequenceFloor = floor,
                    remoteAckMaxSaleId = externalMax,
                )
            }

            RestoreTerminalModeV136.SPARE_TERMINAL -> {
                require(currentIdentity.storeId == "STORE-UNCONFIGURED") {
                    "予備端末移行は店舗未設定の新品端末でのみ実行できます"
                }
                require(currentKnownMaxSaleId == 0L) {
                    "予備端末に既存の売上または採番履歴があるため移行できません"
                }
                require(currentIdentity.generation == 1L) {
                    "予備端末に既存の端末世代情報があるため移行できません"
                }
                require(backupIdentity.storeId.isNotBlank() && backupIdentity.storeId != "STORE-UNCONFIGURED") {
                    "バックアップ元のstoreIdを安全に確認できません"
                }
                val trustedStoreName = backupStoreName
                    ?.let(::normalizeStoreName)
                    ?.takeIf(String::isNotBlank)
                    ?: error("予備端末移行には店舗設定を含むBKP-003バックアップが必要です")
                require(storeNamesMatch(trustedStoreName, request.confirmedStoreName)) {
                    "店舗名の再入力がバックアップ内容と一致しません"
                }
                require(request.oldTerminalStopped) { "旧端末を停止したことを確認してください" }
                require(request.remoteAckMaxSaleId != null) {
                    "予備端末移行ではDrive ACK/既存イベントの最大売上番号を確認してください（該当なしは0）"
                }
                val targetId = newTerminalId()
                require(targetId.isNotBlank() && targetId != backupIdentity.terminalId && targetId != currentIdentity.terminalId) {
                    "予備端末の新terminalIdを発行できません"
                }
                RestoreTerminalMigrationPlanV136(
                    mode = request.mode,
                    storeId = backupIdentity.storeId,
                    sourceTerminalId = backupIdentity.terminalId,
                    targetTerminalId = targetId,
                    sourceGeneration = backupIdentity.generation,
                    targetGeneration = maxGeneration + 1L,
                    saleSequenceFloor = floor,
                    remoteAckMaxSaleId = externalMax,
                )
            }
        }
    }

    fun backupStoreName(stagedContentRoot: File): String? {
        if (!BackupContentBundleV136.hasStagedContent(stagedContentRoot)) return null
        val preferences = File(stagedContentRoot, "content/settings/tax_invoice_settings.pref")
        require(preferences.isFile) { "バックアップの店舗設定がありません" }
        return (BackupPreferenceCodecV136.decode(preferences.readBytes())["store_name"] as? String)
            ?.let(::normalizeStoreName)
            ?.takeIf(String::isNotBlank)
    }

    fun storeNamesMatch(expected: String, entered: String): Boolean =
        normalizeStoreName(expected) == normalizeStoreName(entered) && normalizeStoreName(expected).isNotBlank()

    private fun normalizeStoreName(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
}

/** BKP-005: stale restore must never rewind the AUTOINCREMENT sale number. */
object SaleSequenceSafetyV136 {
    const val FLOOR_KEY = "bkp005_sales_sequence_floor"

    fun maxKnownSaleId(db: SQLiteDatabase): Long = maxOf(
        scalarIfTable(db, "sales", "SELECT COALESCE(MAX(id), 0) FROM sales"),
        scalarIfTable(
            db,
            "sales_journal",
            "SELECT COALESCE(MAX(CAST(aggregate_id AS INTEGER)), 0) FROM sales_journal " +
                "WHERE event_type='SALE' AND aggregate_id <> '' AND aggregate_id NOT GLOB '*[^0-9]*'",
        ),
        driveAckMaxSaleId(db),
        sqliteSequence(db),
        persistedFloor(db),
    )

    fun driveAckMaxSaleId(db: SQLiteDatabase): Long {
        if (!tableExists(db, "sync_outbox") || !tableExists(db, "sales_journal")) return 0L
        return scalar(
            db,
            "SELECT COALESCE(MAX(CAST(j.aggregate_id AS INTEGER)), 0) " +
                "FROM sync_outbox o INNER JOIN sales_journal j ON j.event_id=o.event_id " +
                "WHERE o.status='SENT' AND j.event_type='SALE' " +
                "AND j.aggregate_id <> '' AND j.aggregate_id NOT GLOB '*[^0-9]*'",
        )
    }

    fun persistFloor(db: SQLiteDatabase, requestedFloor: Long): Long {
        require(requestedFloor >= 0L) { "売上採番下限が不正です" }
        ensureSettingsTable(db)
        val floor = maxOf(requestedFloor, persistedFloor(db))
        db.insertWithOnConflict(
            "sync_runtime_settings",
            null,
            ContentValues().apply {
                put("setting_key", FLOOR_KEY)
                put("setting_value", floor.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return floor
    }

    fun enforceBeforeSale(db: SQLiteDatabase): Long {
        val floor = persistFloor(db, maxKnownSaleId(db))
        setSqliteSequenceAtLeast(db, floor)
        return floor
    }

    fun applyRestoreFloor(db: SQLiteDatabase, floor: Long) {
        val committed = persistFloor(db, maxOf(floor, maxKnownSaleId(db)))
        setSqliteSequenceAtLeast(db, committed)
    }

    private fun setSqliteSequenceAtLeast(db: SQLiteDatabase, floor: Long) {
        if (floor <= 0L || !tableExists(db, "sales") || !tableExists(db, "sqlite_sequence")) return
        val current = sqliteSequence(db)
        if (current >= floor) return
        val changed = db.update(
            "sqlite_sequence",
            ContentValues().apply { put("seq", floor) },
            "name=?",
            arrayOf("sales"),
        )
        if (changed == 0) {
            db.insertOrThrow(
                "sqlite_sequence",
                null,
                ContentValues().apply {
                    put("name", "sales")
                    put("seq", floor)
                },
            )
        }
    }

    private fun sqliteSequence(db: SQLiteDatabase): Long {
        if (!tableExists(db, "sqlite_sequence")) return 0L
        return db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='sales'", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
        }
    }

    private fun persistedFloor(db: SQLiteDatabase): Long {
        if (!tableExists(db, "sync_runtime_settings")) return 0L
        return db.rawQuery(
            "SELECT setting_value FROM sync_runtime_settings WHERE setting_key=?",
            arrayOf(FLOOR_KEY),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L
        }
    }

    private fun scalarIfTable(db: SQLiteDatabase, table: String, sql: String): Long =
        if (tableExists(db, table)) scalar(db, sql) else 0L

    private fun scalar(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
        }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun ensureSettingsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_runtime_settings (setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)",
        )
    }
}

object RestoreTerminalMigrationV136 {
    fun apply(databaseFile: File, planValues: Map<String, String>) {
        val mode = RestoreTerminalModeV136.valueOf(planValues.getValue("restore_mode"))
        val storeId = planValues.getValue("target_store_id")
        val terminalId = planValues.getValue("target_terminal_id")
        val generation = planValues.getValue("target_generation").toLong()
        val floor = planValues.getValue("sale_sequence_floor").toLong()
        require(generation >= 1L && floor >= 0L) { "BKP-005復元計画が不正です" }
        val db = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val restored = SalesJournalIdentityStore.resolve(db)
            if (mode == RestoreTerminalModeV136.SAME_TERMINAL) {
                require(restored.storeId == storeId && restored.terminalId == terminalId) {
                    "同一端末復旧で端末識別子が変化しています"
                }
            }
            SalesJournalIdentityStore.updateForRestore(db, storeId, terminalId, generation)
            SaleSequenceSafetyV136.applyRestoreFloor(db, floor)
        } finally {
            db.close()
        }
    }
}
