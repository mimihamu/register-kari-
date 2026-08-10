package jp.co.tenposinfo.register

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

internal data class AppReleaseIdentityV088(
    val versionName: String,
    val versionCode: Int,
)

internal data class PendingAppStartupV088(
    val source: AppReleaseIdentityV088?,
    val target: AppReleaseIdentityV088,
    val startedAt: Long,
    val attemptCount: Int,
)

internal data class AppUpdateTransitionBeginV088(
    val trackingRequired: Boolean,
    val pending: PendingAppStartupV088? = null,
    val displacedIncomplete: PendingAppStartupV088? = null,
)

/**
 * Android依存を持たない、版遷移開始判定。
 *
 * - 同じ版が既に正常起動済みなら何もしない。
 * - 更新後初回起動はsource -> targetを未完了として記録する。
 * - 同じtargetの未完了が残っていれば、前回起動未完了としてattemptCountを増やす。
 * - 未完了のままさらに別版へ更新された場合は、古い未完了証跡を退避する。
 */
internal object AppUpdateTransitionPolicyV088 {
    fun begin(
        lastSuccessful: AppReleaseIdentityV088?,
        existingPending: PendingAppStartupV088?,
        current: AppReleaseIdentityV088,
        now: Long,
    ): AppUpdateTransitionBeginV088 {
        if (existingPending?.target == current) {
            return AppUpdateTransitionBeginV088(
                trackingRequired = true,
                pending = existingPending.copy(attemptCount = existingPending.attemptCount + 1),
            )
        }
        if (existingPending == null && lastSuccessful == current) {
            return AppUpdateTransitionBeginV088(trackingRequired = false)
        }
        return AppUpdateTransitionBeginV088(
            trackingRequired = true,
            pending = PendingAppStartupV088(
                source = lastSuccessful,
                target = current,
                startedAt = now,
                attemptCount = 1,
            ),
            displacedIncomplete = existingPending,
        )
    }
}

/**
 * v0.88: 上書き更新後の「初回起動が本当にMainActivity RESUMEDまで到達したか」を追跡する。
 *
 * 重要:
 * - DBへ書く前にSharedPreferencesへ同期commitし、途中停止時に未完了証跡を残す。
 * - Provider onCreateだけでは成功扱いにしない。
 * - MainActivityがRESUMEDした時だけ既存operation_auditへ結果を追記し、成功版を確定する。
 * - 監査DB処理はUIスレッドで実行しない。
 * - 台帳記録失敗だけでは販売画面をクラッシュさせず、未完了証跡を次回へ残す。
 * - sales / sale_items / sale_payments等の業務データは更新・削除しない。
 */
internal object AppUpdateTransitionV088 {
    private const val TAG = "TsuguRegiUpdateV088"
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

    internal fun pending(context: Context): PendingAppStartupV088? =
        readPending(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private fun registerCompletionCallback(application: Application) {
        synchronized(this) {
            if (callbackRegistered) return
            callbackRegistered = true
        }
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                application.unregisterActivityLifecycleCallbacks(this)
                synchronized(this@AppUpdateTransitionV088) { callbackRegistered = false }
                Thread(
                    {
                        runCatching { completeAfterMainResumed(application) }
                            .onFailure { Log.e(TAG, "更新起動の成功記録に失敗。未完了証跡を保持します", it) }
                    },
                    "tsuguregi-update-v088",
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

        val incomplete = readIncomplete(prefs)
        val userVersion = RegisterDatabase(appContext).use { helper ->
            helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

        // 監査追記が失敗した場合は成功確定しない。次回起動で未完了として再検知する。
        AdminSettingsStore(appContext).use { audit ->
            incomplete?.let {
                audit.recordOperationalAudit(
                    eventType = "APP_UPDATE_PREVIOUS_STARTUP_INCOMPLETE",
                    referenceId = it.target.versionCode.toLong(),
                    detail = transitionDetail(it, userVersion) + " / 次版=${current.versionName}(${current.versionCode})",
                    actor = "システム",
                )
            }
            if (pending.attemptCount > 1) {
                audit.recordOperationalAudit(
                    eventType = "APP_UPDATE_STARTUP_RECOVERED",
                    referenceId = current.versionCode.toLong(),
                    detail = transitionDetail(pending, userVersion),
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
                detail = transitionDetail(pending, userVersion),
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
    }

    private fun transitionDetail(pending: PendingAppStartupV088, userVersion: Int): String {
        val source = pending.source?.let { "${it.versionName}(${it.versionCode})" } ?: "v0.88導入前・不明"
        return "$source -> ${pending.target.versionName}(${pending.target.versionCode}) / " +
            "初回開始=${pending.startedAt} / 起動試行=${pending.attemptCount} / DB user_version=$userVersion"
    }

    private fun currentIdentity() = AppReleaseIdentityV088(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
    )

    private fun readLastSuccessful(prefs: android.content.SharedPreferences): AppReleaseIdentityV088? {
        val name = prefs.getString(KEY_LAST_SUCCESS_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val code = prefs.getInt(KEY_LAST_SUCCESS_CODE, -1)
        return if (code >= 0) AppReleaseIdentityV088(name, code) else null
    }

    private fun readPending(prefs: android.content.SharedPreferences): PendingAppStartupV088? =
        readTransition(
            prefs = prefs,
            sourceNameKey = KEY_PENDING_SOURCE_NAME,
            sourceCodeKey = KEY_PENDING_SOURCE_CODE,
            targetNameKey = KEY_PENDING_TARGET_NAME,
            targetCodeKey = KEY_PENDING_TARGET_CODE,
            startedAtKey = KEY_PENDING_STARTED_AT,
            attemptsKey = KEY_PENDING_ATTEMPTS,
        )

    private fun readIncomplete(prefs: android.content.SharedPreferences): PendingAppStartupV088? =
        readTransition(
            prefs = prefs,
            sourceNameKey = KEY_INCOMPLETE_SOURCE_NAME,
            sourceCodeKey = KEY_INCOMPLETE_SOURCE_CODE,
            targetNameKey = KEY_INCOMPLETE_TARGET_NAME,
            targetCodeKey = KEY_INCOMPLETE_TARGET_CODE,
            startedAtKey = KEY_INCOMPLETE_STARTED_AT,
            attemptsKey = KEY_INCOMPLETE_ATTEMPTS,
        )

    private fun readTransition(
        prefs: android.content.SharedPreferences,
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
        return PendingAppStartupV088(
            source = source,
            target = AppReleaseIdentityV088(targetName, targetCode),
            startedAt = startedAt,
            attemptCount = attempts,
        )
    }

    private fun writePending(editor: android.content.SharedPreferences.Editor, pending: PendingAppStartupV088) {
        writeTransition(
            editor = editor,
            pending = pending,
            sourceNameKey = KEY_PENDING_SOURCE_NAME,
            sourceCodeKey = KEY_PENDING_SOURCE_CODE,
            targetNameKey = KEY_PENDING_TARGET_NAME,
            targetCodeKey = KEY_PENDING_TARGET_CODE,
            startedAtKey = KEY_PENDING_STARTED_AT,
            attemptsKey = KEY_PENDING_ATTEMPTS,
        )
    }

    private fun writeIncomplete(editor: android.content.SharedPreferences.Editor, pending: PendingAppStartupV088) {
        writeTransition(
            editor = editor,
            pending = pending,
            sourceNameKey = KEY_INCOMPLETE_SOURCE_NAME,
            sourceCodeKey = KEY_INCOMPLETE_SOURCE_CODE,
            targetNameKey = KEY_INCOMPLETE_TARGET_NAME,
            targetCodeKey = KEY_INCOMPLETE_TARGET_CODE,
            startedAtKey = KEY_INCOMPLETE_STARTED_AT,
            attemptsKey = KEY_INCOMPLETE_ATTEMPTS,
        )
    }

    private fun writeTransition(
        editor: android.content.SharedPreferences.Editor,
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

    private fun clearPending(editor: android.content.SharedPreferences.Editor) {
        editor.remove(KEY_PENDING_SOURCE_NAME)
            .remove(KEY_PENDING_SOURCE_CODE)
            .remove(KEY_PENDING_TARGET_NAME)
            .remove(KEY_PENDING_TARGET_CODE)
            .remove(KEY_PENDING_STARTED_AT)
            .remove(KEY_PENDING_ATTEMPTS)
    }

    private fun clearIncomplete(editor: android.content.SharedPreferences.Editor) {
        editor.remove(KEY_INCOMPLETE_SOURCE_NAME)
            .remove(KEY_INCOMPLETE_SOURCE_CODE)
            .remove(KEY_INCOMPLETE_TARGET_NAME)
            .remove(KEY_INCOMPLETE_TARGET_CODE)
            .remove(KEY_INCOMPLETE_STARTED_AT)
            .remove(KEY_INCOMPLETE_ATTEMPTS)
    }
}

/**
 * DataRestoreBootstrapProviderV086（initOrder=1000）の直後、通常Provider群より前に実行する。
 * 復元でDBを置換する可能性があるため、更新起動証跡は必ず復元適用後に開始する。
 */
class AppUpdateTransitionBootstrapProviderV088 : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(AppUpdateTransitionV088::beginAfterRestore)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
