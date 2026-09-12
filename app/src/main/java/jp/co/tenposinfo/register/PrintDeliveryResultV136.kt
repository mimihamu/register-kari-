package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Formal v2.5 §16.9 print result recorded independently from queue terminal status. */
enum class PrintDeliveryResultV136(val displayName: String) {
    PRINTED("印刷確認済み"),
    ACCEPTED("送信受付済み"),
}

enum class PrintDeliveryJobKindV136(val tableName: String) {
    SALE_RECEIPT("print_jobs"),
    DOCUMENT("document_print_jobs"),
}

/**
 * Bytes were already accepted by the transport, but a post-send status query or result
 * persistence could not establish a safe terminal result. Never automatically retry it.
 */
class PrinterDeliveryConfirmationExceptionV136(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object PrintDeliveryConfirmationPolicyV136 {
    fun requiresVerifiedStatusQuery(configuration: PrinterConfiguration): Boolean {
        if (!configuration.usable) return false
        val capability = PrinterStatusCapabilityRegistry.forProfile(configuration.profile)
        return configuration.profile.statusProtocol == PrinterStatusProtocol.EPSON_DLE_EOT &&
            capability.verification == PrinterStatusVerification.VENDOR_DOCUMENTED &&
            capability.automaticQueryAllowed
    }

    fun isPrintedConfirmation(status: PrinterRealtimeStatus): Boolean =
        status.protocolValid &&
            status.online &&
            !status.waitingForOnlineRecovery &&
            !status.coverOpen &&
            !status.paperFeedStopped &&
            !status.offlineErrorPresent &&
            !status.recoverableError &&
            !status.cutterError &&
            !status.unrecoverableError &&
            !status.autoRecoverableError &&
            !status.paperOut

    fun confirm(
        configuration: PrinterConfiguration,
        query: ((PrinterConfiguration) -> Result<PrinterRealtimeStatus>)? = null,
    ): Result<PrintDeliveryResultV136> {
        if (!requiresVerifiedStatusQuery(configuration)) {
            return Result.success(PrintDeliveryResultV136.ACCEPTED)
        }
        val statusResult = query?.invoke(configuration)
            ?: TcpPrinterStatusClient(configuration).query(
                purpose = PrinterStatusCheckPurpose.SALES_MONITORING,
            )
        return statusResult.fold(
            onSuccess = { status ->
                if (isPrintedConfirmation(status)) {
                    Result.success(PrintDeliveryResultV136.PRINTED)
                } else {
                    Result.failure(
                        PrinterDeliveryConfirmationExceptionV136(
                            "送信結果が不明です。送信後状態確認で印刷完了を確定できないため自動再試行しません：${status.summary}（RAW ${status.rawHex}）",
                        ),
                    )
                }
            },
            onFailure = { error ->
                Result.failure(
                    PrinterDeliveryConfirmationExceptionV136(
                        "送信結果が不明です。送信後のプリンター状態を確認できないため自動再試行しません：${error.message ?: error.javaClass.simpleName}",
                        error,
                    ),
                )
            },
        )
    }
}

/** Additive, idempotent schema; legacy COMPLETED rows intentionally remain NULL. */
object PrintDeliveryResultStoreV136 {
    fun record(
        context: Context,
        kind: PrintDeliveryJobKindV136,
        jobId: Long,
        result: PrintDeliveryResultV136,
    ) {
        RegisterDatabase(context.applicationContext).use { database ->
            val db = database.writableDatabase
            ensureColumn(db, kind.tableName)
            val updated = db.update(
                kind.tableName,
                ContentValues().apply { put("delivery_result", result.name) },
                "id = ? AND status IN (?, ?)",
                arrayOf(jobId.toString(), PrintJobStatus.SENDING.name, PrintJobStatus.PRINTING.name),
            )
            check(updated == 1) { "送信結果の保存対象ジョブが見つからないか状態が変更されました" }
        }
    }

    fun load(
        context: Context,
        kind: PrintDeliveryJobKindV136,
        jobId: Long,
    ): PrintDeliveryResultV136? = RegisterDatabase(context.applicationContext).use { database ->
        val db = database.writableDatabase
        ensureColumn(db, kind.tableName)
        db.query(
            kind.tableName,
            arrayOf("delivery_result"),
            "id = ?",
            arrayOf(jobId.toString()),
            null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null
            else PrintDeliveryResultV136.valueOf(cursor.getString(0))
        }
    }

    fun ensureColumn(db: SQLiteDatabase, table: String) {
        require(table == "print_jobs" || table == "document_print_jobs") { "未対応の印刷ジョブテーブルです" }
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "delivery_result") {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN delivery_result TEXT")
    }
}

/**
 * Wraps the existing transport without changing the stable PrinterGateway contract.
 * The caller's normal COMPLETED transition runs only after result confirmation/persistence succeeds.
 */
class DeliveryConfirmingPrinterGatewayV136(
    context: Context,
    private val configuration: PrinterConfiguration,
    private val kind: PrintDeliveryJobKindV136,
    private val jobId: Long,
    private val delegate: PrinterGateway,
) : PrinterGateway {
    private val appContext = context.applicationContext

    override fun send(payload: ByteArray): Result<Unit> {
        val sent = delegate.send(payload)
        if (sent.isFailure) return sent
        val confirmation = PrintDeliveryConfirmationPolicyV136.confirm(configuration)
        return confirmation.fold(
            onSuccess = { result ->
                runCatching {
                    PrintDeliveryResultStoreV136.record(appContext, kind, jobId, result)
                }.fold(
                    onSuccess = { Result.success(Unit) },
                    onFailure = { error ->
                        Result.failure(
                            PrinterDeliveryConfirmationExceptionV136(
                                "送信結果が不明です。印刷結果を保存できないため自動再試行しません：${error.message ?: error.javaClass.simpleName}",
                                error,
                            ),
                        )
                    },
                )
            },
            onFailure = { Result.failure(it) },
        )
    }
}
