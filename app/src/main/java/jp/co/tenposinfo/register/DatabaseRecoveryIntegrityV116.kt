package jp.co.tenposinfo.register

import android.content.Context
import android.database.sqlite.SQLiteDatabase

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

        verifyExpectedIndexes(appContext)
    }

    private fun verifyExpectedIndexes(context: Context) {
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
        } finally {
            database.close()
        }
    }
}
