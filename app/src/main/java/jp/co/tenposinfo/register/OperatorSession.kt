package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * 販売アプリ全体で共有する認証済み担当者スナップショット。
 * 権限はログイン時だけでなく復元時にもDBから再読込し、停止済み担当者を復元しない。
 */
data class AuthenticatedOperator(
    val id: Long,
    val code: String,
    val name: String,
    val role: OperatorRole,
    val permissions: Set<RegisterPermission>,
    val revision: Long = 0L,
) {
    fun allows(permission: RegisterPermission): Boolean = permission in permissions
    val isManager: Boolean get() = role == OperatorRole.MANAGER
}

class OperatorAuthenticationStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val bootstrap = AdminSettingsStore(appContext).also { it.close() }
    private val baseDatabase = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase

    override fun close() = baseDatabase.close()

    fun listEnabledOperators(): List<OperatorRecord> = AdminSettingsStore(appContext).use { store ->
        store.listOperators().filter { it.enabled }
    }

    fun authenticate(operatorId: Long, pin: String): Result<AuthenticatedOperator> = runCatching {
        require(pin.isNotBlank()) { "PINを入力してください" }
        val row = db.query(
            "register_operators",
            arrayOf("id", "operator_code", "operator_name", "role", "pin_salt", "pin_hash", "updated_at"),
            "id = ? AND enabled = 1",
            arrayOf(operatorId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else OperatorPinRow(
                id = cursor.getLong(0),
                code = cursor.getString(1),
                name = cursor.getString(2),
                role = OperatorRole.valueOf(cursor.getString(3)),
                salt = cursor.getString(4),
                hash = cursor.getString(5),
                revision = cursor.getLong(6),
            )
        } ?: throw IllegalArgumentException("担当者が見つからないか停止されています")

        if (!PinSecurity.verify(pin, row.salt, row.hash)) {
            insertAudit("LOGIN_FAILED", row.id, "${row.code} / PIN不一致", row.name)
            throw IllegalArgumentException("PINが違います")
        }
        val authenticated = row.toAuthenticated(loadPermissions(row.id))
        insertAudit("LOGIN_SUCCEEDED", row.id, "${row.code} / ${row.role.displayName}", row.name)
        authenticated
    }

    fun loadEnabledOperator(operatorId: Long): AuthenticatedOperator? = db.query(
        "register_operators",
        arrayOf("id", "operator_code", "operator_name", "role", "updated_at"),
        "id = ? AND enabled = 1",
        arrayOf(operatorId.toString()),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AuthenticatedOperator(
            id = cursor.getLong(0),
            code = cursor.getString(1),
            name = cursor.getString(2),
            role = OperatorRole.valueOf(cursor.getString(3)),
            permissions = loadPermissions(cursor.getLong(0)),
            revision = cursor.getLong(4),
        )
    }

    fun recordLogout(operator: AuthenticatedOperator) {
        insertAudit("LOGOUT", operator.id, "${operator.code} / 担当者切替", operator.name)
    }

    private fun loadPermissions(operatorId: Long): Set<RegisterPermission> {
        val result = linkedSetOf<RegisterPermission>()
        db.query(
            "operator_permissions",
            arrayOf("permission_key"),
            "operator_id = ?",
            arrayOf(operatorId.toString()),
            null,
            null,
            "permission_key ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { RegisterPermission.valueOf(cursor.getString(0)) }.getOrNull()?.let(result::add)
            }
        }
        return result
    }

    private fun insertAudit(eventType: String, referenceId: Long, detail: String, operatorName: String) {
        db.insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail)
                put("operator_name", operatorName)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    private data class OperatorPinRow(
        val id: Long,
        val code: String,
        val name: String,
        val role: OperatorRole,
        val salt: String,
        val hash: String,
        val revision: Long,
    ) {
        fun toAuthenticated(permissions: Set<RegisterPermission>) = AuthenticatedOperator(
            id = id,
            code = code,
            name = name,
            role = role,
            permissions = permissions,
            revision = revision,
        )
    }
}

object OperatorSessionRegistry {
    private const val PREFS_NAME = "register_operator_session"
    private const val KEY_OPERATOR_ID = "operator_id"
    private const val KEY_LAST_ACTIVITY = "last_activity"
    private const val SESSION_TIMEOUT_MILLIS = 30L * 60L * 1_000L

    @Volatile
    private var cached: AuthenticatedOperator? = null

    fun current(context: Context): AuthenticatedOperator? {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getLong(KEY_OPERATOR_ID, -1L)
        val lastActivity = prefs.getLong(KEY_LAST_ACTIVITY, 0L)
        if (operatorId <= 0L || isExpired(lastActivity, System.currentTimeMillis())) {
            clear(appContext)
            return null
        }
        val stored = OperatorAuthenticationStore(appContext).use { it.loadEnabledOperator(operatorId) }
        if (stored == null || !OperatorSessionRevisionPolicy.mayContinue(true, stored.permissions)) {
            clear(appContext)
            return null
        }
        val current = cached
        if (current == null || current.id != operatorId || OperatorSessionRevisionPolicy.shouldReload(current.revision, stored.revision)) {
            cached = stored
            return stored
        }
        return current
    }

    internal fun isExpired(lastActivity: Long, now: Long): Boolean =
        lastActivity <= 0L || now - lastActivity > SESSION_TIMEOUT_MILLIS

    fun login(context: Context, operator: AuthenticatedOperator) {
        cached = operator
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_OPERATOR_ID, operator.id)
            .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
            .apply()
    }

    fun touch(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_OPERATOR_ID, -1L) <= 0L) return
        prefs.edit().putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis()).apply()
    }

    fun logout(context: Context) {
        val operator = current(context)
        if (operator != null) runCatching {
            OperatorAuthenticationStore(context).use { it.recordLogout(operator) }
        }
        clear(context)
    }

    fun verifyManagerPin(context: Context, pin: String): Boolean =
        AdminSettingsStore(context.applicationContext).use { it.verifyManagerPin(pin) }

    fun lastKnownName(): String? = cached?.name

    fun invalidate(context: Context) = clear(context.applicationContext)

    private fun clear(context: Context) {
        cached = null
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
