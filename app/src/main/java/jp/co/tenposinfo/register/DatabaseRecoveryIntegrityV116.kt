package jp.co.tenposinfo.register

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * v1.16: 復元DBを正本として成功確定する直前の最終ゲート。
 *
 * 既存のmigration/bootstrap/health-checkを再利用し、同じ検査を別実装で重複させない。
 * 復元後migrationもこのゲート内で完了させるため、失敗は呼出元のrollback境界へ伝播する。
 */
internal object DatabaseRecoveryIntegrityV116 {
    internal const val EXPECTED_DATABASE_USER_VERSION = 4
    private const val DATABASE_NAME = "register.db"

    private val REQUIRED_INDEXES = setOf(
        "idx_print_jobs_status",
        "idx_business_sessions_status",
        "idx_cash_movements_session",
        "idx_reversal_original_sale",
        "idx_reversal_items_sale_item",
        "idx_document_jobs_status",
        "idx_reversal_session",
        "idx_settlement_session",
    )

    fun migrateAndVerify(context: Context) {
        val appContext = context.applicationContext

        // SQLiteOpenHelper migration と既存Storeの冪等schema ensureを、復元rollback境界の内側で完了させる。
        DatabaseStartupSchemaBootstrapV085.ensureBeforeUi(appContext)

        verifyFinal(appContext)
    }

    fun verifyFinal(context: Context) {
        val appContext = context.applicationContext

        // DataProtectionManager の既存診断（integrity/FK/必須テーブル/意味整合性）を再利用する。
        val protection = DataProtectionManager(appContext).diagnose()
        require(protection.healthy) {
            "復元後DB診断に失敗しました: ${protection.issues.joinToString { it.code }}"
        }

        // v0.89 の読み取り専用health gateを再利用し、主要な後付け列まで確認する。
        val health = AppUpdateDatabaseHealthCheckV089.inspect(appContext)
        require(health.healthy) { "復元後DB health gateに失敗しました: ${health.auditSummary()}" }
        require(health.userVersion == EXPECTED_DATABASE_USER_VERSION) {
            "復元後DB schema version不一致: ${health.userVersion} != $EXPECTED_DATABASE_USER_VERSION"
        }

        verifyExpectedIndexesAndFenceRemoval(appContext)
    }

    private fun verifyExpectedIndexesAndFenceRemoval(context: Context) {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        require(databaseFile.isFile && databaseFile.length() > 0L) { "復元後DBファイルがありません" }
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val indexes = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'",
                null,
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val missing = REQUIRED_INDEXES - indexes
            require(missing.isEmpty()) {
                "復元後DBの必須index不足: ${missing.sorted().joinToString()}"
            }
            require(!PendingRestoreWriteFenceV116.isInstalled(database)) {
                "復元後DBに復元予約書込みフェンスが残っています"
            }
        } finally {
            database.close()
        }
    }
}

/**
 * 復元予約成立後から次回起動時のDB置換まで、現在DBへの業務書込みをSQLite自身で拒否する。
 *
 * triggerはDBファイルへ保存されるため、既に開いているRegisterDatabase接続にも即時に効く。
 * operation_auditだけは復元予約・取消の証跡を残すため除外する。
 */
internal object PendingRestoreWriteFenceV116 {
    internal const val TRIGGER_PREFIX = "v116_restore_fence_"
    private const val DATABASE_NAME = "register.db"
    private const val AUDIT_TABLE = "operation_audit"
    private const val BLOCK_MESSAGE = "復元予約中のため業務データを書き込めません"

    fun install(context: Context) {
        val databaseFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
        require(databaseFile.isFile && databaseFile.length() > 0L) { "復元予約対象DBがありません" }
        withWritableDatabase(databaseFile) { database ->
            database.transactional {
                val tables = rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val table = cursor.getString(0)
                            if (table != AUDIT_TABLE) add(table)
                        }
                    }
                }
                require(tables.isNotEmpty()) { "復元予約書込みフェンス対象テーブルがありません" }
                tables.forEach { table ->
                    createFenceTrigger(this, table, "insert", "INSERT")
                    createFenceTrigger(this, table, "update", "UPDATE")
                    createFenceTrigger(this, table, "delete", "DELETE")
                }
            }
        }
    }

    fun remove(context: Context) {
        remove(context.applicationContext.getDatabasePath(DATABASE_NAME))
    }

    fun remove(databaseFile: File) {
        if (!databaseFile.isFile || databaseFile.length() <= 0L) return
        withWritableDatabase(databaseFile) { database ->
            database.transactional { removeFromOpenDatabase(this) }
        }
    }

    fun isInstalled(database: SQLiteDatabase): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name LIKE ? LIMIT 1",
        arrayOf("$TRIGGER_PREFIX%"),
    ).use { it.moveToFirst() }

    private fun createFenceTrigger(
        database: SQLiteDatabase,
        table: String,
        suffix: String,
        operation: String,
    ) {
        val triggerName = "$TRIGGER_PREFIX${table}_$suffix"
        database.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${quoteIdentifier(triggerName)} " +
                "BEFORE $operation ON ${quoteIdentifier(table)} BEGIN " +
                "SELECT RAISE(ABORT, '$BLOCK_MESSAGE'); END",
        )
    }

    private fun removeFromOpenDatabase(database: SQLiteDatabase) {
        val triggers = database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE ? ORDER BY name",
            arrayOf("$TRIGGER_PREFIX%"),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        triggers.forEach { trigger -> database.execSQL("DROP TRIGGER IF EXISTS ${quoteIdentifier(trigger)}") }
    }

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private inline fun withWritableDatabase(file: File, block: (SQLiteDatabase) -> Unit) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private inline fun SQLiteDatabase.transactional(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
}

/**
 * UIからの復元予約・取消を、現在DBの書込みフェンスと不可分な手順として扱う。
 */
internal object RestoreReservationCoordinatorV116 {
    fun stage(
        context: Context,
        manager: DataProtectionManager,
        fileName: String,
        managerPin: String,
    ): RestoreStageResult = stage(
        context,
        manager,
        fileName,
        managerPin,
        RestoreTerminalMigrationRequestV136.sameTerminal(),
    )

    fun stage(
        context: Context,
        manager: DataProtectionManager,
        fileName: String,
        managerPin: String,
        migrationRequest: RestoreTerminalMigrationRequestV136,
    ): RestoreStageResult {
        val appContext = context.applicationContext
        PendingRestoreWriteFenceV116.install(appContext)
        return try {
            if (migrationRequest.mode == RestoreTerminalModeV136.SAME_TERMINAL) {
                manager.stageRestore(fileName, managerPin)
            } else {
                manager.stageRestore(fileName, managerPin, migrationRequest)
            }
        } catch (error: Throwable) {
            runCatching { PendingRestoreWriteFenceV116.remove(appContext) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    fun cancel(
        context: Context,
        manager: DataProtectionManager,
        managerPin: String,
    ): String {
        // PIN不正等で取消自体が失敗した場合はフェンスを維持する。
        val actor = manager.cancelPendingRestore(managerPin)
        PendingRestoreWriteFenceV116.remove(context.applicationContext)
        return actor
    }
}
