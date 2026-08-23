package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/** Formal v2.5 §16.9 print result recorded independently from queue terminal status. */
enum class PrintDeliveryResultV136(val displayName: String) {
    PRINTED("印刷確認済み"),
    ACCEPTED("送信受付済み"),
}

/**
 * Bytes were already accepted by the transport, but a post-send status query could not
 * establish a safe PRINTED result. This must never enter the automatic retry path.
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

/** Additive, idempotent schema migration; legacy COMPLETED rows intentionally remain NULL. */
object PrintDeliveryResultSchemaV136 {
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
