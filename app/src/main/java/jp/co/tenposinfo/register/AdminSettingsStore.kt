package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class OperatorRole(val displayName: String) {
    CASHIER("担当者"),
    MANAGER("責任者"),
}

enum class RegisterPermission(val displayName: String) {
    SALES("販売"),
    HOLD_TICKET("保留伝票"),
    VIEW_SALES("売上確認"),
    CASH_MOVEMENT("入出金"),
    BUSINESS_START("営業開始"),
    X_INSPECTION("X点検"),
    Z_SETTLEMENT("Z精算"),
    SETTLEMENT("点検・精算（旧互換）"),
    REVERSAL("返品・取消"),
    SETTINGS("各種設定"),
    AUDIT_LOG("監査ログ"),
}

data class OperatorRecord(
    val id: Long,
    val code: String,
    val name: String,
    val role: OperatorRole,
    val enabled: Boolean,
    val displayOrder: Int,
    val permissions: Set<RegisterPermission>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PrinterConfiguration(
    val name: String = "レシートプリンター",
    val host: String = "",
    val port: Int = 9100,
    val paperWidthMm: Int = 80,
    val timeoutMillis: Int = 5_000,
    val enabled: Boolean = false,
    val profile: PrinterProfile = PrinterProfile.EPSON_TM_JAPAN,
    val cutMode: PrinterCutMode = PrinterCutMode.PARTIAL,
    val drawerEnabled: Boolean = false,
    val drawerOpenOnCashSale: Boolean = true,
    val drawerPort: Int = 0,
    val drawerOnMillis: Int = 100,
    val drawerOffMillis: Int = 500,
) {
    val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
}

data class AuditLogRecord(
    val id: Long,
    val eventType: String,
    val referenceId: Long,
    val detail: String,
    val operatorName: String,
    val createdAt: Long,
)

object OperatorPermissionPolicy {
    fun defaults(role: OperatorRole): Set<RegisterPermission> = when (role) {
        OperatorRole.CASHIER -> setOf(
            RegisterPermission.SALES,
            RegisterPermission.HOLD_TICKET,
            RegisterPermission.VIEW_SALES,
        )

        OperatorRole.MANAGER -> RegisterPermissionCompatibilityV026.selectablePermissions.toSet()
    }
}

object PinSecurity {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256
    private val random = SecureRandom()

    data class EncodedPin(val salt: String, val hash: String)

    fun encode(pin: String): EncodedPin {
        validate(pin)
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(pin, salt)
        return EncodedPin(
            salt = Base64.getEncoder().encodeToString(salt),
            hash = Base64.getEncoder().encodeToString(hash),
        )
    }

    fun verify(pin: String, saltBase64: String, hashBase64: String): Boolean = runCatching {
        if (!pin.matches(Regex("\\d{4,8}"))) return false
        val salt = Base64.getDecoder().decode(saltBase64)
        val expected = Base64.getDecoder().decode(hashBase64)
        MessageDigest.isEqual(expected, derive(pin, salt))
    }.getOrDefault(false)

    fun validate(pin: String) {
        require(pin.matches(Regex("\\d{4,8}"))) { "PINは4～8桁の数字で入力してください" }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * 実プリンター設定を、既存の印刷キューからも参照できるようにする軽量レジストリ。
 * アプリ起動時と設定保存時に再読み込みする。
 */
object PrinterConfigurationRegistry {
    @Volatile
    private var configuration: PrinterConfiguration? = null

    fun current(): PrinterConfiguration? = configuration

    fun reload(context: Context) {
        configuration = runCatching {
            AdminSettingsStore(context.applicationContext).use { it.loadPrinterConfiguration() }
        }.getOrNull()
    }

    fun clear() {
        configuration = null
    }
}

/**
 * 印字幅はプリンター設定だけで決定する。
 * 会計、再印字、返品・取消、X点検、Z精算などの実行画面から幅を受け取らない。
 */
object PrinterPaperSettingPolicy {
    fun normalizeWidthMm(widthMm: Int): Int = if (widthMm >= 80) 80 else 58

    fun paper(configuration: PrinterConfiguration): ReceiptPaper =
        ReceiptPaper.fromWidth(normalizeWidthMm(configuration.paperWidthMm))

    fun currentConfiguration(context: Context): PrinterConfiguration =
        PrinterConfigurationRegistry.current() ?: runCatching {
            AdminSettingsStore(context.applicationContext).use { it.loadPrinterConfiguration() }
        }.getOrElse { PrinterConfiguration() }

    fun currentWidthMm(context: Context): Int =
        normalizeWidthMm(currentConfiguration(context).paperWidthMm)

    fun currentPaper(context: Context): ReceiptPaper =
        paper(currentConfiguration(context))
}

class AdminSettingsStore(context: Context) : AutoCloseable {
    private val baseDatabase = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase

    init {
        ensureSchema()
        migrateBusinessStartPermissionV135()
        seedDefaults()
    }

    override fun close() = baseDatabase.close()

    fun listOperators(): List<OperatorRecord> {
        val operators = mutableListOf<OperatorRecord>()
        db.query(
            "register_operators",
            arrayOf("id", "operator_code", "operator_name", "role", "enabled", "display_order", "created_at", "updated_at"),
            null,
            null,
            null,
            null,
            "display_order ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                operators += OperatorRecord(
                    id = id,
                    code = cursor.getString(1),
                    name = cursor.getString(2),
                    role = OperatorRole.valueOf(cursor.getString(3)),
                    enabled = cursor.getInt(4) != 0,
                    displayOrder = cursor.getInt(5),
                    permissions = loadPermissions(id),
                    createdAt = cursor.getLong(6),
                    updatedAt = cursor.getLong(7),
                )
            }
        }
        return operators
    }

    fun saveOperator(
        id: Long?,
        code: String,
        name: String,
        role: OperatorRole,
        enabled: Boolean,
        pin: String?,
        permissions: Set<RegisterPermission>,
        actor: String,
    ): Long {
        val cleanCode = code.trim().uppercase()
        val cleanName = name.trim()
        require(cleanCode.matches(Regex("[A-Z0-9_-]{1,20}"))) { "担当者コードは英数字・_・-で20文字以内です" }
        require(cleanName.isNotBlank()) { "担当者名を入力してください" }
        if (id == null) require(!pin.isNullOrBlank()) { "新規登録時はPINが必要です" }
        pin?.takeIf(String::isNotBlank)?.let(PinSecurity::validate)
        if (!enabled && id != null) ensureNotLastManager(id)

        val now = System.currentTimeMillis()
        return db.inTransaction {
            val operatorId = if (id == null) {
                val encoded = PinSecurity.encode(requireNotNull(pin))
                insertOrThrow(
                    "register_operators",
                    null,
                    ContentValues().apply {
                        put("operator_code", cleanCode)
                        put("operator_name", cleanName)
                        put("role", role.name)
                        put("enabled", if (enabled) 1 else 0)
                        put("pin_salt", encoded.salt)
                        put("pin_hash", encoded.hash)
                        put("display_order", nextDisplayOrder())
                        put("created_at", now)
                        put("updated_at", now)
                    },
                )
            } else {
                val values = ContentValues().apply {
                    put("operator_code", cleanCode)
                    put("operator_name", cleanName)
                    put("role", role.name)
                    put("enabled", if (enabled) 1 else 0)
                    put("updated_at", now)
                    pin?.takeIf(String::isNotBlank)?.let {
                        val encoded = PinSecurity.encode(it)
                        put("pin_salt", encoded.salt)
                        put("pin_hash", encoded.hash)
                    }
                }
                require(update("register_operators", values, "id = ?", arrayOf(id.toString())) == 1) { "担当者が見つかりません" }
                id
            }

            delete("operator_permissions", "operator_id = ?", arrayOf(operatorId.toString()))
            val effectivePermissions = RegisterPermissionCompatibilityV026.normalizeForSave(
                if (permissions.isEmpty()) OperatorPermissionPolicy.defaults(role) else permissions,
            )
            effectivePermissions.forEach { permission ->
                insertOrThrow(
                    "operator_permissions",
                    null,
                    ContentValues().apply {
                        put("operator_id", operatorId)
                        put("permission_key", permission.name)
                    },
                )
            }
            insertAudit(
                eventType = if (id == null) "OPERATOR_CREATED" else "OPERATOR_UPDATED",
                referenceId = operatorId,
                detail = "$cleanCode / $cleanName / ${role.displayName} / ${if (enabled) "有効" else "停止"}",
                operatorName = actor,
                createdAt = now,
            )
            operatorId
        }
    }

    fun moveOperator(operatorId: Long, direction: Int, actor: String) {
        require(direction == -1 || direction == 1)
        val rows = listOperators()
        val index = rows.indexOfFirst { it.id == operatorId }
        if (index < 0) return
        val targetIndex = index + direction
        if (targetIndex !in rows.indices) return
        val current = rows[index]
        val target = rows[targetIndex]
        db.inTransaction {
            update("register_operators", ContentValues().apply { put("display_order", target.displayOrder) }, "id = ?", arrayOf(current.id.toString()))
            update("register_operators", ContentValues().apply { put("display_order", current.displayOrder) }, "id = ?", arrayOf(target.id.toString()))
            insertAudit("OPERATOR_REORDERED", current.id, "${current.name}を${if (direction < 0) "上" else "下"}へ移動", actor, System.currentTimeMillis())
        }
    }

    fun verifyManagerPin(pin: String): Boolean = db.rawQuery(
        "SELECT pin_salt, pin_hash FROM register_operators WHERE role = ? AND enabled = 1",
        arrayOf(OperatorRole.MANAGER.name),
    ).use { cursor ->
        var matched = false
        while (cursor.moveToNext()) {
            matched = matched or PinSecurity.verify(pin, cursor.getString(0), cursor.getString(1))
        }
        matched
    }

    fun managerNameForPin(pin: String): String? = db.rawQuery(
        "SELECT operator_name, pin_salt, pin_hash FROM register_operators WHERE role = ? AND enabled = 1 ORDER BY display_order",
        arrayOf(OperatorRole.MANAGER.name),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            if (PinSecurity.verify(pin, cursor.getString(1), cursor.getString(2))) return@use cursor.getString(0)
        }
        null
    }

    fun changeManagerPin(currentPin: String, newPin: String, actor: String): Long {
        PinSecurity.validate(newPin)
        val manager = db.rawQuery(
            "SELECT id, pin_salt, pin_hash FROM register_operators WHERE role = ? AND enabled = 1 ORDER BY display_order",
            arrayOf(OperatorRole.MANAGER.name),
        ).use { cursor ->
            var found: Long? = null
            while (cursor.moveToNext()) {
                if (PinSecurity.verify(currentPin, cursor.getString(1), cursor.getString(2))) {
                    found = cursor.getLong(0)
                    break
                }
            }
            found
        } ?: throw IllegalArgumentException("現在の責任者PINが違います")

        val encoded = PinSecurity.encode(newPin)
        val now = System.currentTimeMillis()
        db.inTransaction {
            update(
                "register_operators",
                ContentValues().apply {
                    put("pin_salt", encoded.salt)
                    put("pin_hash", encoded.hash)
                    put("updated_at", now)
                },
                "id = ?",
                arrayOf(manager.toString()),
            )
            insertAudit("MANAGER_PIN_CHANGED", manager, "責任者PINを変更", actor, now)
        }
        return manager
    }

    fun hasPermission(operatorId: Long, permission: RegisterPermission): Boolean = db.rawQuery(
        "SELECT COUNT(*) FROM operator_permissions p INNER JOIN register_operators o ON o.id = p.operator_id WHERE p.operator_id = ? AND p.permission_key = ? AND o.enabled = 1",
        arrayOf(operatorId.toString(), permission.name),
    ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0 }

    fun loadPrinterConfiguration(): PrinterConfiguration = db.query(
        "printer_settings",
        arrayOf(
            "printer_name", "host", "port", "paper_width_mm", "timeout_millis", "enabled",
            "profile_key", "cut_mode", "drawer_enabled", "drawer_open_on_cash",
            "drawer_port", "drawer_on_millis", "drawer_off_millis",
        ),
        "id = 1",
        null,
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) PrinterConfiguration() else PrinterConfiguration(
            name = cursor.getString(0),
            host = cursor.getString(1),
            port = cursor.getInt(2),
            paperWidthMm = cursor.getInt(3),
            timeoutMillis = cursor.getInt(4),
            enabled = cursor.getInt(5) != 0,
            profile = enumValueOrDefault(cursor.getString(6), PrinterProfile.EPSON_TM_JAPAN),
            cutMode = enumValueOrDefault(cursor.getString(7), PrinterCutMode.PARTIAL),
            drawerEnabled = cursor.getInt(8) != 0,
            drawerOpenOnCashSale = cursor.getInt(9) != 0,
            drawerPort = cursor.getInt(10),
            drawerOnMillis = cursor.getInt(11),
            drawerOffMillis = cursor.getInt(12),
        )
    }

    fun savePrinterConfiguration(configuration: PrinterConfiguration, actor: String) {
        require(configuration.name.isNotBlank()) { "プリンター名を入力してください" }
        require(configuration.port in 1..65535) { "ポート番号は1～65535で入力してください" }
        require(configuration.paperWidthMm == 58 || configuration.paperWidthMm == 80) { "用紙幅は58mmまたは80mmです" }
        require(configuration.timeoutMillis in 1_000..30_000) { "タイムアウトは1000～30000msで入力してください" }
        require(configuration.drawerPort == 0 || configuration.drawerPort == 1) { "ドロアポートはDK1またはDK2です" }
        require(configuration.drawerOnMillis in 20..500) { "ドロアON時間は20～500msです" }
        require(configuration.drawerOffMillis in 20..500) { "ドロアOFF時間は20～500msです" }
        if (configuration.enabled) require(configuration.host.isNotBlank()) { "有効にする場合はIPアドレスまたはホスト名が必要です" }
        if (configuration.drawerEnabled) require(configuration.profile.supportsDrawer) { "選択中のプロファイルはドロア制御に対応していません" }
        val now = System.currentTimeMillis()
        db.inTransaction {
            update(
                "printer_settings",
                ContentValues().apply {
                    put("printer_name", configuration.name.trim())
                    put("host", configuration.host.trim())
                    put("port", configuration.port)
                    put("paper_width_mm", configuration.paperWidthMm)
                    put("timeout_millis", configuration.timeoutMillis)
                    put("enabled", if (configuration.enabled) 1 else 0)
                    put("profile_key", configuration.profile.name)
                    put("cut_mode", configuration.cutMode.name)
                    put("drawer_enabled", if (configuration.drawerEnabled) 1 else 0)
                    put("drawer_open_on_cash", if (configuration.drawerOpenOnCashSale) 1 else 0)
                    put("drawer_port", configuration.drawerPort)
                    put("drawer_on_millis", configuration.drawerOnMillis)
                    put("drawer_off_millis", configuration.drawerOffMillis)
                    put("updated_at", now)
                },
                "id = 1",
                null,
            )
            insertAudit(
                "PRINTER_SETTINGS_UPDATED",
                1,
                "${configuration.name} ${configuration.host}:${configuration.port} ${configuration.paperWidthMm}mm / ${configuration.profile.displayName} / ${configuration.cutMode.displayName} / ドロア${if (configuration.drawerEnabled) "有効" else "無効"}",
                actor,
                now,
            )
        }
    }

    fun testPrinter(configuration: PrinterConfiguration): Result<Unit> {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名を入力してください" }
        val now = Instant.now().toString()
        val text = buildString {
            append("つぐレジ プリンターテスト\n")
            append("${configuration.name}\n")
            append("${configuration.host}:${configuration.port}\n")
            append("${configuration.profile.displayName}\n")
            append("用紙 ${configuration.paperWidthMm}mm / ${configuration.cutMode.displayName}\n")
            append("$now\n")
            append("--------------------------------\n")
            append("日本語印字テスト 1234567890\n")
        }
        val payload = PrinterCommandEncoder.encodeText(
            text = text,
            configuration = configuration,
            openDrawer = false,
            appendCut = true,
        )
        return printerGateway(configuration).send(payload)
    }

    fun testDrawer(configuration: PrinterConfiguration): Result<Unit> {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名を入力してください" }
        return printerGateway(configuration).send(PrinterCommandEncoder.drawerOnly(configuration))
    }

    fun recordPrinterTest(configuration: PrinterConfiguration, success: Boolean, message: String, actor: String) {
        db.inTransaction {
            insertAudit(
                eventType = if (success) "PRINTER_TEST_SUCCEEDED" else "PRINTER_TEST_FAILED",
                referenceId = 1,
                detail = "${configuration.host}:${configuration.port} / ${message.take(300)}",
                operatorName = actor,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    fun recordDrawerTest(configuration: PrinterConfiguration, success: Boolean, message: String, actor: String) {
        db.inTransaction {
            insertAudit(
                eventType = if (success) "DRAWER_TEST_SUCCEEDED" else "DRAWER_TEST_FAILED",
                referenceId = 1,
                detail = "${configuration.host}:${configuration.port} / DK${configuration.drawerPort + 1} / ${message.take(300)}",
                operatorName = actor,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    fun listAuditLogs(limit: Int = 500, query: String = ""): List<AuditLogRecord> {
        val cleanQuery = query.trim()
        val selection = if (cleanQuery.isEmpty()) null else "event_type LIKE ? OR detail LIKE ? OR operator_name LIKE ?"
        val args = if (cleanQuery.isEmpty()) null else Array(3) { "%$cleanQuery%" }
        val result = mutableListOf<AuditLogRecord>()
        db.query(
            "operation_audit",
            arrayOf("id", "event_type", "reference_id", "detail", "operator_name", "created_at"),
            selection,
            args,
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 2_000).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AuditLogRecord(
                    id = cursor.getLong(0),
                    eventType = cursor.getString(1),
                    referenceId = cursor.getLong(2),
                    detail = cursor.getString(3),
                    operatorName = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
        }
        return result
    }

    fun auditCount(): Long = db.rawQuery("SELECT COUNT(*) FROM operation_audit", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    fun recordOperationalAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
    ) {
        require(eventType.isNotBlank()) { "監査イベント種別が必要です" }
        require(actor.isNotBlank()) { "監査担当者が必要です" }
        db.inTransaction {
            insertAudit(
                eventType = eventType.trim().take(80),
                referenceId = referenceId,
                detail = detail.trim().take(1_000),
                operatorName = actor.trim().take(100),
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    private fun printerGateway(configuration: PrinterConfiguration) = TcpEscPosPrinterGateway(
        host = configuration.host.trim(),
        port = configuration.port,
        timeoutMillis = configuration.timeoutMillis,
    )

    private fun loadPermissions(operatorId: Long): Set<RegisterPermission> {
        val permissions = linkedSetOf<RegisterPermission>()
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
                runCatching { RegisterPermission.valueOf(cursor.getString(0)) }.getOrNull()?.let(permissions::add)
            }
        }
        return RegisterPermissionCompatibilityV026.expand(permissions)
    }

    private fun nextDisplayOrder(): Int = db.rawQuery(
        "SELECT COALESCE(MAX(display_order), 0) + 10 FROM register_operators",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 10 }

    private fun ensureNotLastManager(operatorId: Long) {
        val targetIsManager = db.rawQuery(
            "SELECT COUNT(*) FROM register_operators WHERE id = ? AND role = ? AND enabled = 1",
            arrayOf(operatorId.toString(), OperatorRole.MANAGER.name),
        ).use { it.moveToFirst() && it.getLong(0) > 0 }
        if (!targetIsManager) return
        val enabledManagers = db.rawQuery(
            "SELECT COUNT(*) FROM register_operators WHERE role = ? AND enabled = 1",
            arrayOf(OperatorRole.MANAGER.name),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        require(enabledManagers > 1) { "最後の責任者は停止できません" }
    }

    private fun SQLiteDatabase.insertAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        operatorName: String,
        createdAt: Long,
    ) {
        insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail)
                put("operator_name", operatorName.ifBlank { "責任者" })
                put("created_at", createdAt)
            },
        )
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS register_operators (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operator_code TEXT NOT NULL UNIQUE,
                operator_name TEXT NOT NULL,
                role TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                pin_salt TEXT NOT NULL,
                pin_hash TEXT NOT NULL,
                display_order INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operator_permissions (
                operator_id INTEGER NOT NULL,
                permission_key TEXT NOT NULL,
                PRIMARY KEY(operator_id, permission_key),
                FOREIGN KEY(operator_id) REFERENCES register_operators(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_settings (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                printer_name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                paper_width_mm INTEGER NOT NULL,
                timeout_millis INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                profile_key TEXT NOT NULL DEFAULT 'EPSON_TM_JAPAN',
                cut_mode TEXT NOT NULL DEFAULT 'PARTIAL',
                drawer_enabled INTEGER NOT NULL DEFAULT 0,
                drawer_open_on_cash INTEGER NOT NULL DEFAULT 1,
                drawer_port INTEGER NOT NULL DEFAULT 0,
                drawer_on_millis INTEGER NOT NULL DEFAULT 100,
                drawer_off_millis INTEGER NOT NULL DEFAULT 500,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        ensurePrinterColumn("profile_key", "TEXT NOT NULL DEFAULT 'EPSON_TM_JAPAN'")
        ensurePrinterColumn("cut_mode", "TEXT NOT NULL DEFAULT 'PARTIAL'")
        ensurePrinterColumn("drawer_enabled", "INTEGER NOT NULL DEFAULT 0")
        ensurePrinterColumn("drawer_open_on_cash", "INTEGER NOT NULL DEFAULT 1")
        ensurePrinterColumn("drawer_port", "INTEGER NOT NULL DEFAULT 0")
        ensurePrinterColumn("drawer_on_millis", "INTEGER NOT NULL DEFAULT 100")
        ensurePrinterColumn("drawer_off_millis", "INTEGER NOT NULL DEFAULT 500")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                detail TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_register_operator_order ON register_operators(display_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_operation_audit_created ON operation_audit(created_at)")
    }

    private fun ensurePrinterColumn(name: String, declaration: String) {
        val exists = db.rawQuery("PRAGMA table_info(printer_settings)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE printer_settings ADD COLUMN $name $declaration")
    }

    /**
     * v1.35 BIZDAY-001: 営業開始権限をZ精算から分離する一度限りの互換移行。
     * 旧版でZ精算権限を持っていた既存担当者には営業開始権限を一度だけコピーする。
     * マーカー保存後は再実行しないため、管理者が営業開始権限だけ外しても再付与しない。
     */
    private fun migrateBusinessStartPermissionV135() {
        val migrationKey = "BUSINESS_START_FROM_Z_V135"
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS register_permission_migrations_v135 (
                migration_key TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val applied = db.rawQuery(
            "SELECT COUNT(*) FROM register_permission_migrations_v135 WHERE migration_key = ?",
            arrayOf(migrationKey),
        ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0L }
        if (applied) return

        db.runInTransaction {
            execSQL(
                """
                INSERT OR IGNORE INTO operator_permissions(operator_id, permission_key)
                SELECT operator_id, '${RegisterPermission.BUSINESS_START.name}'
                FROM operator_permissions
                WHERE permission_key IN (
                    '${RegisterPermission.Z_SETTLEMENT.name}',
                    '${RegisterPermission.SETTLEMENT.name}'
                )
                """.trimIndent(),
            )
            insertOrThrow(
                "register_permission_migrations_v135",
                null,
                ContentValues().apply {
                    put("migration_key", migrationKey)
                    put("applied_at", System.currentTimeMillis())
                },
            )
        }
    }

    private fun seedDefaults() {
        val operatorCount = db.rawQuery("SELECT COUNT(*) FROM register_operators", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        if (operatorCount == 0L) {
            val now = System.currentTimeMillis()
            db.inTransaction {
                val cashier = PinSecurity.encode("1111")
                val cashierId = insertOrThrow(
                    "register_operators",
                    null,
                    ContentValues().apply {
                        put("operator_code", "OP001")
                        put("operator_name", "担当者")
                        put("role", OperatorRole.CASHIER.name)
                        put("enabled", 1)
                        put("pin_salt", cashier.salt)
                        put("pin_hash", cashier.hash)
                        put("display_order", 10)
                        put("created_at", now)
                        put("updated_at", now)
                    },
                )
                OperatorPermissionPolicy.defaults(OperatorRole.CASHIER).forEach {
                    insertOrThrow("operator_permissions", null, ContentValues().apply {
                        put("operator_id", cashierId)
                        put("permission_key", it.name)
                    })
                }

                val manager = PinSecurity.encode("0000")
                val managerId = insertOrThrow(
                    "register_operators",
                    null,
                    ContentValues().apply {
                        put("operator_code", "MG001")
                        put("operator_name", "責任者")
                        put("role", OperatorRole.MANAGER.name)
                        put("enabled", 1)
                        put("pin_salt", manager.salt)
                        put("pin_hash", manager.hash)
                        put("display_order", 20)
                        put("created_at", now)
                        put("updated_at", now)
                    },
                )
                OperatorPermissionPolicy.defaults(OperatorRole.MANAGER).forEach {
                    insertOrThrow("operator_permissions", null, ContentValues().apply {
                        put("operator_id", managerId)
                        put("permission_key", it.name)
                    })
                }
                insertAudit("SECURITY_INITIALIZED", managerId, "担当者・権限マスターを初期化", "SYSTEM", now)
            }
        }

        db.execSQL(
            """
            INSERT OR IGNORE INTO printer_settings(
                id, printer_name, host, port, paper_width_mm, timeout_millis, enabled,
                profile_key, cut_mode, drawer_enabled, drawer_open_on_cash,
                drawer_port, drawer_on_millis, drawer_off_millis, updated_at
            ) VALUES(
                1, 'レシートプリンター', '', 9100, 80, 5000, 0,
                'EPSON_TM_JAPAN', 'PARTIAL', 0, 1, 0, 100, 500, 0
            )
            """.trimIndent(),
        )
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val value = block()
        setTransactionSuccessful()
        value
    } finally {
        endTransaction()
    }
}
