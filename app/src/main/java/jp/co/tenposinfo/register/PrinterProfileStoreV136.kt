package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * Formal v2.5 §16.1 / §16.2:
 * multiple printer profiles may coexist on one register and each document kind
 * selects its own default output printer. The legacy printer_settings(id=1)
 * row is migrated as printer-1 without destructive schema replacement.
 */
class PrinterProfileStoreV136(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)
    private val db = database.writableDatabase

    init {
        ensureSchema()
        seedLegacyProfileIfNeeded()
        seedRoutesIfNeeded()
    }

    override fun close() = database.close()

    fun list(): List<PrinterConfiguration> = db.query(
        PROFILE_TABLE,
        PROFILE_COLUMNS,
        null,
        null,
        null,
        null,
        "created_at ASC, printer_id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toPrinterConfiguration())
        }
    }

    fun load(printerId: String): PrinterConfiguration? = db.query(
        PROFILE_TABLE,
        PROFILE_COLUMNS,
        "printer_id = ?",
        arrayOf(printerId.trim()),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toPrinterConfiguration() else null
    }

    fun defaultPrinterId(kind: DocumentPrintKindV136): String? = db.query(
        ROUTE_TABLE,
        arrayOf("printer_id"),
        "document_kind = ?",
        arrayOf(kind.name),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    fun resolve(kind: DocumentPrintKindV136): PrinterConfiguration? {
        defaultPrinterId(kind)?.let(::load)?.let { return it }
        return list().firstOrNull { it.enabled } ?: list().firstOrNull()
    }

    fun routes(): Map<DocumentPrintKindV136, String> = buildMap {
        db.query(
            ROUTE_TABLE,
            arrayOf("document_kind", "printer_id"),
            null,
            null,
            null,
            null,
            "document_kind ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching { DocumentPrintKindV136.valueOf(cursor.getString(0)) }.getOrNull()
                if (kind != null) put(kind, cursor.getString(1))
            }
        }
    }

    fun save(configuration: PrinterConfiguration, actor: String): PrinterConfiguration {
        val printerId = configuration.printerId.trim().ifBlank { newPrinterId() }
        val normalized = configuration.copy(
            printerId = printerId,
            name = configuration.name.trim(),
            host = configuration.host.trim(),
            usbDeviceName = configuration.usbDeviceName.trim(),
            bluetoothAddress = configuration.bluetoothAddress.trim().uppercase(),
        )
        require(normalized.name.isNotBlank()) { "プリンター名を入力してください" }
        require(printerId.matches(PRINTER_ID_PATTERN)) { "プリンターIDが不正です" }
        PrinterProfileContractV136.validatePersistedConfiguration(normalized)
        require(normalized.timeoutMillis in 1_000..30_000) { "タイムアウトは1000～30000msで入力してください" }
        if (normalized.enabled) PrinterTransportPolicyV136.validate(normalized)

        val now = System.currentTimeMillis()
        val createdAt = db.query(
            PROFILE_TABLE,
            arrayOf("created_at"),
            "printer_id = ?",
            arrayOf(printerId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else now }

        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                PROFILE_TABLE,
                null,
                normalized.toProfileValues(createdAt, now),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            if (db.rawQuery("SELECT COUNT(*) FROM $ROUTE_TABLE", null).use { it.moveToFirst(); it.getInt(0) } == 0) {
                DocumentPrintKindV136.entries.forEach { kind ->
                    db.insertWithOnConflict(
                        ROUTE_TABLE,
                        null,
                        ContentValues().apply {
                            put("document_kind", kind.name)
                            put("printer_id", printerId)
                            put("updated_at", now)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            audit(
                eventType = "PRINTER_PROFILE_SAVED",
                referenceId = printerId.hashCode().toLong(),
                detail = "$printerId / ${normalized.name} / ${normalized.connectionType.displayName} / " +
                    "${PrinterTransportPolicyV136.endpointDisplay(normalized)} / " +
                    "${normalized.paperWidthMm}mm/${normalized.printableDotWidth}dot",
                actor = actor,
                now = now,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return normalized
    }

    fun createDraft(): PrinterConfiguration = PrinterConfiguration(
        printerId = newPrinterId(),
        name = "追加プリンター",
        enabled = false,
    )

    fun delete(printerId: String, actor: String) {
        val cleanId = printerId.trim()
        val profiles = list()
        require(profiles.any { it.printerId == cleanId }) { "プリンターが見つかりません" }
        require(profiles.size > 1) { "最後のプリンターは削除できません" }
        require(activeJobReferenceCount(cleanId) == 0L) {
            "未完了の印刷ジョブが参照しているため削除できません。印刷キューを先に処理してください"
        }
        val replacement = profiles.first { it.printerId != cleanId }
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE $ROUTE_TABLE SET printer_id = ?, updated_at = ? WHERE printer_id = ?",
                arrayOf(replacement.printerId, now, cleanId),
            )
            db.delete(PROFILE_TABLE, "printer_id = ?", arrayOf(cleanId))
            audit(
                eventType = "PRINTER_PROFILE_DELETED",
                referenceId = cleanId.hashCode().toLong(),
                detail = "$cleanId / route_reassigned_to=${replacement.printerId}",
                actor = actor,
                now = now,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setDefault(kind: DocumentPrintKindV136, printerId: String, actor: String) {
        val configuration = load(printerId)
            ?: throw IllegalArgumentException("出力先プリンターが見つかりません")
        val now = System.currentTimeMillis()
        db.insertWithOnConflict(
            ROUTE_TABLE,
            null,
            ContentValues().apply {
                put("document_kind", kind.name)
                put("printer_id", configuration.printerId)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        audit(
            eventType = "PRINTER_ROUTE_UPDATED",
            referenceId = kind.ordinal.toLong(),
            detail = "${kind.displayName} -> ${configuration.printerId} / ${configuration.name}",
            actor = actor,
            now = now,
        )
    }

    fun duplicateTcpPrinterIds(configuration: PrinterConfiguration): List<String> {
        if (configuration.connectionType != PrinterConnectionTypeV136.TCP_9100) return emptyList()
        val host = configuration.host.trim()
        if (host.isBlank()) return emptyList()
        return list()
            .filter {
                it.printerId != configuration.printerId &&
                    it.connectionType == PrinterConnectionTypeV136.TCP_9100 &&
                    it.host.trim().equals(host, ignoreCase = true) &&
                    it.port == configuration.port
            }
            .map { it.printerId }
    }

    fun activeJobReferenceCount(printerId: String): Long {
        val cleanId = printerId.trim()
        if (cleanId.isBlank()) return 0L
        val terminalStatuses = arrayOf(PrintJobStatus.COMPLETED.name, PrintJobStatus.DISCARDED.name)
        var total = 0L
        if (tableExists("print_jobs")) {
            total += db.rawQuery(
                "SELECT COUNT(*) FROM print_jobs WHERE printer_id = ? AND status NOT IN (?, ?)",
                arrayOf(cleanId, terminalStatuses[0], terminalStatuses[1]),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        }
        if (tableExists("document_print_jobs")) {
            total += db.rawQuery(
                "SELECT COUNT(*) FROM document_print_jobs WHERE printer_id = ? AND status NOT IN (?, ?)",
                arrayOf(cleanId, terminalStatuses[0], terminalStatuses[1]),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        }
        return total
    }

    private fun tableExists(name: String): Boolean = db.rawQuery(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(name),
    ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0L }

    private fun ensureSchema() {
        OperationAuditSchemaV136.ensure(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PROFILE_TABLE (
                printer_id TEXT PRIMARY KEY,
                printer_name TEXT NOT NULL,
                connection_type TEXT NOT NULL DEFAULT 'TCP_9100',
                host TEXT NOT NULL DEFAULT '',
                port INTEGER NOT NULL DEFAULT 9100,
                usb_device_name TEXT NOT NULL DEFAULT '',
                bluetooth_address TEXT NOT NULL DEFAULT '',
                paper_width_mm INTEGER NOT NULL,
                printable_dot_width INTEGER NOT NULL,
                feed_lines INTEGER NOT NULL DEFAULT 5,
                timeout_millis INTEGER NOT NULL DEFAULT 5000,
                enabled INTEGER NOT NULL DEFAULT 0,
                receipt_auto_print INTEGER NOT NULL DEFAULT 1,
                profile_key TEXT NOT NULL DEFAULT 'EPSON_TM_JAPAN',
                cut_mode TEXT NOT NULL DEFAULT 'PARTIAL',
                drawer_enabled INTEGER NOT NULL DEFAULT 0,
                drawer_open_on_cash INTEGER NOT NULL DEFAULT 1,
                drawer_open_on_cash_refund INTEGER NOT NULL DEFAULT 1,
                drawer_open_on_cash_movement INTEGER NOT NULL DEFAULT 1,
                drawer_open_on_exchange INTEGER NOT NULL DEFAULT 1,
                drawer_standalone_enabled INTEGER NOT NULL DEFAULT 0,
                drawer_open_reason_required INTEGER NOT NULL DEFAULT 1,
                drawer_port INTEGER NOT NULL DEFAULT 0,
                drawer_on_millis INTEGER NOT NULL DEFAULT 100,
                drawer_off_millis INTEGER NOT NULL DEFAULT 500,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $ROUTE_TABLE (
                document_kind TEXT PRIMARY KEY,
                printer_id TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(printer_id) REFERENCES $PROFILE_TABLE(printer_id)
                    ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_printer_profiles_enabled_v136 ON $PROFILE_TABLE(enabled, created_at)")
    }

    private fun seedLegacyProfileIfNeeded() {
        val count = db.rawQuery("SELECT COUNT(*) FROM $PROFILE_TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (count > 0) return

        val now = System.currentTimeMillis()
        val legacy = loadLegacyConfiguration() ?: PrinterConfiguration()
        val seeded = legacy.copy(printerId = PrinterProfileContractV136.SINGLE_PRINTER_ID)
        db.insertOrThrow(PROFILE_TABLE, null, seeded.toProfileValues(now, now))
    }

    private fun seedRoutesIfNeeded() {
        val fallback = list().firstOrNull()?.printerId ?: return
        val now = System.currentTimeMillis()
        DocumentPrintKindV136.entries.forEach { kind ->
            db.insertWithOnConflict(
                ROUTE_TABLE,
                null,
                ContentValues().apply {
                    put("document_kind", kind.name)
                    put("printer_id", fallback)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun loadLegacyConfiguration(): PrinterConfiguration? = runCatching {
        db.query(
            "printer_settings",
            arrayOf(
                "printer_name", "host", "port", "paper_width_mm", "printable_dot_width", "feed_lines",
                "timeout_millis", "enabled", "profile_key", "cut_mode", "drawer_enabled", "drawer_open_on_cash",
                "drawer_port", "drawer_on_millis", "drawer_off_millis", "receipt_auto_print",
                "drawer_open_on_cash_refund", "drawer_open_on_cash_movement", "drawer_open_on_exchange",
                "drawer_standalone_enabled", "drawer_open_reason_required", "connection_type",
                "usb_device_name", "bluetooth_address",
            ),
            "id = 1",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else PrinterConfiguration(
                printerId = PrinterProfileContractV136.SINGLE_PRINTER_ID,
                name = cursor.getString(0),
                host = cursor.getString(1),
                port = cursor.getInt(2),
                paperWidthMm = cursor.getInt(3),
                printableDotWidth = cursor.getInt(4),
                feedLines = cursor.getInt(5),
                timeoutMillis = cursor.getInt(6),
                enabled = cursor.getInt(7) != 0,
                profile = enumOrDefault(cursor.getString(8), PrinterProfile.EPSON_TM_JAPAN),
                cutMode = enumOrDefault(cursor.getString(9), PrinterCutMode.PARTIAL),
                drawerEnabled = cursor.getInt(10) != 0,
                drawerOpenOnCashSale = cursor.getInt(11) != 0,
                drawerPort = cursor.getInt(12),
                drawerOnMillis = cursor.getInt(13),
                drawerOffMillis = cursor.getInt(14),
                receiptAutoPrintEnabled = cursor.getInt(15) != 0,
                drawerOpenOnCashRefund = cursor.getInt(16) != 0,
                drawerOpenOnCashMovement = cursor.getInt(17) != 0,
                drawerOpenOnExchange = cursor.getInt(18) != 0,
                drawerStandaloneEnabled = cursor.getInt(19) != 0,
                drawerOpenReasonRequired = cursor.getInt(20) != 0,
                connectionType = enumOrDefault(cursor.getString(21), PrinterConnectionTypeV136.TCP_9100),
                usbDeviceName = cursor.getString(22).orEmpty(),
                bluetoothAddress = cursor.getString(23).orEmpty(),
            )
        }
    }.getOrNull()

    private fun PrinterConfiguration.toProfileValues(createdAt: Long, updatedAt: Long) = ContentValues().apply {
        put("printer_id", printerId)
        put("printer_name", name)
        put("connection_type", connectionType.name)
        put("host", host)
        put("port", port)
        put("usb_device_name", usbDeviceName)
        put("bluetooth_address", bluetoothAddress)
        put("paper_width_mm", paperWidthMm)
        put("printable_dot_width", printableDotWidth)
        put("feed_lines", feedLines)
        put("timeout_millis", timeoutMillis)
        put("enabled", if (enabled) 1 else 0)
        put("receipt_auto_print", if (receiptAutoPrintEnabled) 1 else 0)
        put("profile_key", profile.name)
        put("cut_mode", cutMode.name)
        put("drawer_enabled", if (drawerEnabled) 1 else 0)
        put("drawer_open_on_cash", if (drawerOpenOnCashSale) 1 else 0)
        put("drawer_open_on_cash_refund", if (drawerOpenOnCashRefund) 1 else 0)
        put("drawer_open_on_cash_movement", if (drawerOpenOnCashMovement) 1 else 0)
        put("drawer_open_on_exchange", if (drawerOpenOnExchange) 1 else 0)
        put("drawer_standalone_enabled", if (drawerStandaloneEnabled) 1 else 0)
        put("drawer_open_reason_required", if (drawerOpenReasonRequired) 1 else 0)
        put("drawer_port", drawerPort)
        put("drawer_on_millis", drawerOnMillis)
        put("drawer_off_millis", drawerOffMillis)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun Cursor.toPrinterConfiguration() = PrinterConfiguration(
        printerId = getString(0),
        name = getString(1),
        connectionType = enumOrDefault(getString(2), PrinterConnectionTypeV136.TCP_9100),
        host = getString(3),
        port = getInt(4),
        usbDeviceName = getString(5),
        bluetoothAddress = getString(6),
        paperWidthMm = getInt(7),
        printableDotWidth = getInt(8),
        feedLines = getInt(9),
        timeoutMillis = getInt(10),
        enabled = getInt(11) != 0,
        receiptAutoPrintEnabled = getInt(12) != 0,
        profile = enumOrDefault(getString(13), PrinterProfile.EPSON_TM_JAPAN),
        cutMode = enumOrDefault(getString(14), PrinterCutMode.PARTIAL),
        drawerEnabled = getInt(15) != 0,
        drawerOpenOnCashSale = getInt(16) != 0,
        drawerOpenOnCashRefund = getInt(17) != 0,
        drawerOpenOnCashMovement = getInt(18) != 0,
        drawerOpenOnExchange = getInt(19) != 0,
        drawerStandaloneEnabled = getInt(20) != 0,
        drawerOpenReasonRequired = getInt(21) != 0,
        drawerPort = getInt(22),
        drawerOnMillis = getInt(23),
        drawerOffMillis = getInt(24),
    )

    private fun audit(
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
        now: Long,
    ) {
        db.insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail.take(1_000))
                put("operator_name", actor.trim().ifBlank { "SYSTEM" }.take(100))
                put("created_at", now)
            },
        )
    }

    private fun newPrinterId(): String = "printer-" + UUID.randomUUID().toString().substring(0, 8)

    private companion object {
        const val PROFILE_TABLE = "printer_profiles_v136"
        const val ROUTE_TABLE = "document_printer_routes_v136"
        val PRINTER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,48}")
        val PROFILE_COLUMNS = arrayOf(
            "printer_id", "printer_name", "connection_type", "host", "port",
            "usb_device_name", "bluetooth_address", "paper_width_mm", "printable_dot_width",
            "feed_lines", "timeout_millis", "enabled", "receipt_auto_print", "profile_key",
            "cut_mode", "drawer_enabled", "drawer_open_on_cash", "drawer_open_on_cash_refund",
            "drawer_open_on_cash_movement", "drawer_open_on_exchange", "drawer_standalone_enabled",
            "drawer_open_reason_required", "drawer_port", "drawer_on_millis", "drawer_off_millis",
        )
    }
}


object PrinterRoutingV136 {
    fun resolve(
        context: Context,
        kind: DocumentPrintKindV136,
    ): PrinterConfiguration = PrinterProfileStoreV136(context.applicationContext).use { store ->
        store.resolve(kind)
    } ?: PrinterConfiguration()

    fun loadById(
        context: Context,
        printerId: String,
    ): PrinterConfiguration? = PrinterProfileStoreV136(context.applicationContext).use { store ->
        store.load(printerId)
    }

    fun kindFor(type: OperationDocumentType): DocumentPrintKindV136 =
        DocumentPrintSettingsPolicyV136.kindFor(type) ?: DocumentPrintKindV136.SALE_RECEIPT
}

private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
