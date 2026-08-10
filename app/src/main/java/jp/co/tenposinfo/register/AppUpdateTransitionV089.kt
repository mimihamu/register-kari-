package jp.co.tenposinfo.register

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log

internal data class AppUpdateDatabaseHealthV089(
    val checkedAt: Long,
    val userVersion: Int,
    val integrityOk: Boolean,
    val foreignKeyViolationCount: Int,
    val missingTables: Set<String>,
    val missingColumns: Set<String>,
    val errorType: String? = null,
) {
    val healthy: Boolean
        get() = errorType == null && integrityOk && foreignKeyViolationCount == 0 &&
            missingTables.isEmpty() && missingColumns.isEmpty()

    fun auditSummary(): String = buildString {
        append("DB-health=")
        append(if (healthy) "OK" else "NG")
        append(" / user_version=").append(userVersion)
        append(" / integrity=").append(if (integrityOk) "OK" else "NG")
        append(" / FK=").append(foreignKeyViolationCount)
        if (missingTables.isNotEmpty()) append(" / missing-tables=").append(missingTables.sorted().joinToString(","))
        if (missingColumns.isNotEmpty()) append(" / missing-columns=").append(missingColumns.sorted().joinToString(","))
        errorType?.let { append(" / error-type=").append(it) }
    }
}

internal data class AppUpdateDatabaseHealthFailureEvidenceV089(
    val target: AppReleaseIdentityV088,
    val checkedAt: Long,
    val summary: String,
)

/**
 * v0.89: MainActivity RESUMED後、更新成功を確定する直前に現在DBを読み取り専用で検査する。
 *
 * 成功条件:
 * - PRAGMA integrity_check == ok
 * - PRAGMA foreign_key_check == 0件
 * - 初版運用に必要な主要テーブルが存在
 * - 後付けスキーマの主要列が存在
 *
 * この検査は業務行を更新・削除せず、DDLも実行しない。
 */
internal object AppUpdateDatabaseHealthCheckV089 {
    private const val DATABASE_NAME = "register.db"

    private val requiredTables = DataProtectionTablePolicy.requiredTables + setOf(
        "document_print_jobs",
        "operation_commit_keys",
        "receipt_voucher_batches",
        "receipt_voucher_issuances",
        "receipt_voucher_reprints",
        "sale_receipt_reprint_requests",
    )

    private val requiredColumns = mapOf(
        "cart_items" to setOf("line_no", "discount_amount", "note"),
        "sales" to setOf("print_count", "business_session_id", "business_date"),
        "sale_items" to setOf("discount_amount", "note"),
        "held_ticket_items" to setOf("discount_amount", "note"),
        "reversal_items" to setOf(
            "tax_key",
            "tax_label",
            "tax_rate_percent",
            "tax_included",
            "taxable",
            "reduced",
            "tax_symbol",
        ),
        "settlement_reports" to setOf("opening_cash", "cash_in", "cash_out", "snapshot_version"),
    )

    fun inspect(context: Context): AppUpdateDatabaseHealthV089 {
        val databaseFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
        if (!databaseFile.isFile || databaseFile.length() <= 0L) {
            return AppUpdateDatabaseHealthV089(
                checkedAt = System.currentTimeMillis(),
                userVersion = -1,
                integrityOk = false,
                foreignKeyViolationCount = -1,
                missingTables = requiredTables,
                missingColumns = requiredColumns.flatMap { (table, columns) -> columns.map { "$table.$it" } }.toSet(),
                errorType = "DATABASE_FILE_MISSING",
            )
        }

        return runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use(::inspectDatabase)
        }.getOrElse { error ->
            AppUpdateDatabaseHealthV089(
                checkedAt = System.currentTimeMillis(),
                userVersion = -1,
                integrityOk = false,
                foreignKeyViolationCount = -1,
                missingTables = emptySet(),
                missingColumns = emptySet(),
                errorType = error.javaClass.simpleName.ifBlank { "DATABASE_OPEN_ERROR" },
            )
        }
    }

    private fun inspectDatabase(db: SQLiteDatabase): AppUpdateDatabaseHealthV089 {
        val userVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }
        val integrityRows = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val integrityOk = integrityRows.size == 1 && integrityRows.single().equals("ok", ignoreCase = true)
        val foreignKeyViolationCount = db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count++
            count
        }
        val existingTables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val missingTables = requiredTables - existingTables
        val missingColumns = buildSet {
            requiredColumns.forEach { (table, columns) ->
                if (table !in existingTables) {
                    columns.forEach { add("$table.$it") }
                } else {
                    val existingColumns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
                    }
                    (columns - existingColumns).forEach { add("$table.$it") }
                }
            }
        }
        return AppUpdateDatabaseHealthV089(
            checkedAt = System.currentTimeMillis(),
            userVersion = userVersion,
            integrityOk = integrityOk,
            foreignKeyViolationCount = foreignKeyViolationCount,
            missingTables = missingTables,
            missingColumns = missingColumns,
        )
    }
}

/**
 * DBが不健全な場合は同じDBへ監査書込みを行わず、SharedPreferencesだけへ証跡を同期保存する。
 */
internal object AppUpdateDatabaseHealthEvidenceV089 {
    private const val PREFS = "app_update_database_health_v089"
    private const val KEY_TARGET_NAME = "target_name"
    private const val KEY_TARGET_CODE = "target_code"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_SUMMARY = "summary"

    fun recordFailure(
        context: Context,
        target: AppReleaseIdentityV088,
        health: AppUpdateDatabaseHealthV089,
    ): Boolean = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_TARGET_NAME, target.versionName)
        .putInt(KEY_TARGET_CODE, target.versionCode)
        .putLong(KEY_CHECKED_AT, health.checkedAt)
        .putString(KEY_SUMMARY, health.auditSummary().take(1000))
        .commit()

    fun read(context: Context): AppUpdateDatabaseHealthFailureEvidenceV089? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_TARGET_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val code = prefs.getInt(KEY_TARGET_CODE, -1)
        val checkedAt = prefs.getLong(KEY_CHECKED_AT, -1L)
        val summary = prefs.getString(KEY_SUMMARY, null)?.takeIf { it.isNotBlank() } ?: return null
        if (code < 0 || checkedAt < 0L) return null
        return AppUpdateDatabaseHealthFailureEvidenceV089(
            target = AppReleaseIdentityV088(name, code),
            checkedAt = checkedAt,
            summary = summary,
        )
    }

    fun clear(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
}

/**
 * v0.89: v0.88の版遷移台帳をそのまま引き継ぎ、成功確定前DB健全性ゲートを追加する。
 * PREFS/KEYはv0.88と同一なので、v0.88で記録済みの正常版・未完了証跡を継承できる。
 */
internal object AppUpdateTransitionV089 {
    private const val TAG = "TsuguRegiUpdateV089"
    private const val PREFS = "app_update_transition_v088"

    private const val KEY_LAST_SUCCESS_NAME = "last_success_name"
    private const val KEY_LAST_SUCCESS_CODE = "last_success_code"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_PENDING_SOURCE_NAME = "pending_source_name"
    private const val KEY_PENDING_SOURCE_CODE = "pending_source_code"
    private const val KEY_PENDING_TARGET_NAME = "pending_target_name"
    private const val KEY_PENDING_TARGET_CODE = "pending_target_code"
    private const val KEY_PENDING_STARTED_AT = "pending_started_at"
    private const val KEY_PENDING_ATTEMPTS = "pending_attempts"
    private const val KEY_INCOMPLETE_SOURCE_NAME = "incomplete_source_name"
    private const val KEY_INCOMPLETE_SOURCE_CODE = "incomplete_source_code"
    private const val KEY_INCOMPLETE_TARGET_NAME = "incomplete_target_name"
    private const val KEY_INCOMPLETE_TARGET_CODE = "incomplete_target_code"
    private const val KEY_INCOMPLETE_STARTED_AT = "incomplete_started_at"
    private const val KEY_INCOMPLETE_ATTEMPTS = "incomplete_attempts"

    @Volatile
    private var callbackRegistered = false

    fun beginAfterRestore(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = currentIdentity()
        val decision = AppUpdateTransitionPolicyV088.begin(
            lastSuccessful = readLastSuccessful(prefs),
            existingPending = readPending(prefs),
            current = current,
            now = System.currentTimeMillis(),
        )
        if (!decision.trackingRequired) return

        val pending = requireNotNull(decision.pending)
        val editor = prefs.edit()
        writePending(editor, pending)
        decision.displacedIncomplete?.let { writeIncomplete(editor, it) }
        if (!editor.commit()) {
            Log.e(TAG, "更新起動の未完了証跡を保存できません")
            return
        }

        val application = appContext as? Application ?: return
        registerCompletionCallback(application)
    }

    private fun registerCompletionCallback(application: Application) {
        synchronized(this) {
            if (callbackRegistered) return
            callbackRegistered = true
        }
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                application.unregisterActivityLifecycleCallbacks(this)
                synchronized(this@AppUpdateTransitionV089) { callbackRegistered = false }
                Thread(
                    {
                        runCatching { completeAfterMainResumed(application) }
                            .onFailure { Log.e(TAG, "更新起動の成功判定に失敗。未完了証跡を保持します", it) }
                    },
                    "tsuguregi-update-v089",
                ).start()
            }

            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callback)
    }

    private fun completeAfterMainResumed(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = currentIdentity()
        val pending = readPending(prefs) ?: return
        if (pending.target != current) return

        // v0.89: 更新成功を確定する前に、現在DBを読み取り専用で健全性確認する。
        val health = AppUpdateDatabaseHealthCheckV089.inspect(appContext)
        if (!health.healthy) {
            val evidenceSaved = AppUpdateDatabaseHealthEvidenceV089.recordFailure(appContext, current, health)
            Log.e(
                TAG,
                "更新成功を確定しません。DB健全性NG / evidenceSaved=$evidenceSaved / ${health.auditSummary()}",
            )
            // 不健全DBにはoperation_auditも書かない。pendingは残して次回起動で再検査する。
            return
        }

        val incomplete = readIncomplete(prefs)
        val priorHealthFailure = AppUpdateDatabaseHealthEvidenceV089.read(appContext)
            ?.takeIf { it.target == current }
        val healthyDetail = transitionDetail(pending, health.userVersion) + " / ${health.auditSummary()}"

        // DBが健全であることを確認した後だけ監査へ追記し、成功版を確定する。
        AdminSettingsStore(appContext).use { audit ->
            priorHealthFailure?.let {
                audit.recordOperationalAudit(
                    eventType = "APP_UPDATE_STARTUP_DB_HEALTH_RECOVERED",
                    referenceId = current.versionCode.toLong(),
                    detail = "前回DB健全性NG=${it.summary} / 今回=${health.auditSummary()}",
                    actor = "システム",
                )
            }
            incomplete?.let {
                audit.recordOperationalAudit(
                    eventType = "APP_UPDATE_PREVIOUS_STARTUP_INCOMPLETE",
                    referenceId = it.target.versionCode.toLong(),
                    detail = transitionDetail(it, health.userVersion) + " / 次版=${current.versionName}(${current.versionCode})",
                    actor = "システム",
                )
            }
            if (pending.attemptCount > 1) {
                audit.recordOperationalAudit(
                    eventType = "APP_UPDATE_STARTUP_RECOVERED",
                    referenceId = current.versionCode.toLong(),
                    detail = healthyDetail,
                    actor = "システム",
                )
            }
            audit.recordOperationalAudit(
                eventType = if (pending.source == null) {
                    "APP_VERSION_BASELINE_ESTABLISHED"
                } else {
                    "APP_UPDATE_STARTUP_SUCCEEDED"
                },
                referenceId = current.versionCode.toLong(),
                detail = healthyDetail,
                actor = "システム",
            )
        }

        val editor = prefs.edit()
            .putString(KEY_LAST_SUCCESS_NAME, current.versionName)
            .putInt(KEY_LAST_SUCCESS_CODE, current.versionCode)
            .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
        clearPending(editor)
        clearIncomplete(editor)
        check(editor.commit()) { "更新起動の成功状態を保存できません" }

        if (!AppUpdateDatabaseHealthEvidenceV089.clear(appContext)) {
            Log.w(TAG, "DB健全性失敗証跡を消去できませんでした。成功版確定には影響しません")
        }
    }

    private fun transitionDetail(pending: PendingAppStartupV088, userVersion: Int): String {
        val source = pending.source?.let { "${it.versionName}(${it.versionCode})" } ?: "更新台帳導入前・不明"
        return "$source -> ${pending.target.versionName}(${pending.target.versionCode}) / " +
            "初回開始=${pending.startedAt} / 起動試行=${pending.attemptCount} / DB user_version=$userVersion"
    }

    private fun currentIdentity() = AppReleaseIdentityV088(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
    )

    private fun readLastSuccessful(prefs: SharedPreferences): AppReleaseIdentityV088? {
        val name = prefs.getString(KEY_LAST_SUCCESS_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val code = prefs.getInt(KEY_LAST_SUCCESS_CODE, -1)
        return if (code >= 0) AppReleaseIdentityV088(name, code) else null
    }

    private fun readPending(prefs: SharedPreferences): PendingAppStartupV088? = readTransition(
        prefs,
        KEY_PENDING_SOURCE_NAME,
        KEY_PENDING_SOURCE_CODE,
        KEY_PENDING_TARGET_NAME,
        KEY_PENDING_TARGET_CODE,
        KEY_PENDING_STARTED_AT,
        KEY_PENDING_ATTEMPTS,
    )

    private fun readIncomplete(prefs: SharedPreferences): PendingAppStartupV088? = readTransition(
        prefs,
        KEY_INCOMPLETE_SOURCE_NAME,
        KEY_INCOMPLETE_SOURCE_CODE,
        KEY_INCOMPLETE_TARGET_NAME,
        KEY_INCOMPLETE_TARGET_CODE,
        KEY_INCOMPLETE_STARTED_AT,
        KEY_INCOMPLETE_ATTEMPTS,
    )

    private fun readTransition(
        prefs: SharedPreferences,
        sourceNameKey: String,
        sourceCodeKey: String,
        targetNameKey: String,
        targetCodeKey: String,
        startedAtKey: String,
        attemptsKey: String,
    ): PendingAppStartupV088? {
        val targetName = prefs.getString(targetNameKey, null)?.takeIf { it.isNotBlank() } ?: return null
        val targetCode = prefs.getInt(targetCodeKey, -1)
        val startedAt = prefs.getLong(startedAtKey, -1L)
        val attempts = prefs.getInt(attemptsKey, 0)
        if (targetCode < 0 || startedAt < 0L || attempts <= 0) return null
        val sourceName = prefs.getString(sourceNameKey, null)?.takeIf { it.isNotBlank() }
        val sourceCode = prefs.getInt(sourceCodeKey, -1)
        val source = if (sourceName != null && sourceCode >= 0) AppReleaseIdentityV088(sourceName, sourceCode) else null
        return PendingAppStartupV088(source, AppReleaseIdentityV088(targetName, targetCode), startedAt, attempts)
    }

    private fun writePending(editor: SharedPreferences.Editor, pending: PendingAppStartupV088) = writeTransition(
        editor,
        pending,
        KEY_PENDING_SOURCE_NAME,
        KEY_PENDING_SOURCE_CODE,
        KEY_PENDING_TARGET_NAME,
        KEY_PENDING_TARGET_CODE,
        KEY_PENDING_STARTED_AT,
        KEY_PENDING_ATTEMPTS,
    )

    private fun writeIncomplete(editor: SharedPreferences.Editor, pending: PendingAppStartupV088) = writeTransition(
        editor,
        pending,
        KEY_INCOMPLETE_SOURCE_NAME,
        KEY_INCOMPLETE_SOURCE_CODE,
        KEY_INCOMPLETE_TARGET_NAME,
        KEY_INCOMPLETE_TARGET_CODE,
        KEY_INCOMPLETE_STARTED_AT,
        KEY_INCOMPLETE_ATTEMPTS,
    )

    private fun writeTransition(
        editor: SharedPreferences.Editor,
        pending: PendingAppStartupV088,
        sourceNameKey: String,
        sourceCodeKey: String,
        targetNameKey: String,
        targetCodeKey: String,
        startedAtKey: String,
        attemptsKey: String,
    ) {
        if (pending.source == null) {
            editor.remove(sourceNameKey).remove(sourceCodeKey)
        } else {
            editor.putString(sourceNameKey, pending.source.versionName)
                .putInt(sourceCodeKey, pending.source.versionCode)
        }
        editor.putString(targetNameKey, pending.target.versionName)
            .putInt(targetCodeKey, pending.target.versionCode)
            .putLong(startedAtKey, pending.startedAt)
            .putInt(attemptsKey, pending.attemptCount)
    }

    private fun clearPending(editor: SharedPreferences.Editor) {
        editor.remove(KEY_PENDING_SOURCE_NAME)
            .remove(KEY_PENDING_SOURCE_CODE)
            .remove(KEY_PENDING_TARGET_NAME)
            .remove(KEY_PENDING_TARGET_CODE)
            .remove(KEY_PENDING_STARTED_AT)
            .remove(KEY_PENDING_ATTEMPTS)
    }

    private fun clearIncomplete(editor: SharedPreferences.Editor) {
        editor.remove(KEY_INCOMPLETE_SOURCE_NAME)
            .remove(KEY_INCOMPLETE_SOURCE_CODE)
            .remove(KEY_INCOMPLETE_TARGET_NAME)
            .remove(KEY_INCOMPLETE_TARGET_CODE)
            .remove(KEY_INCOMPLETE_STARTED_AT)
            .remove(KEY_INCOMPLETE_ATTEMPTS)
    }
}

/**
 * DataRestoreBootstrapProviderV086（1000）の直後、通常Provider群（150以下）より前に実行する。
 */
class AppUpdateTransitionBootstrapProviderV089 : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(AppUpdateTransitionV089::beginAfterRestore)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
