package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale

private const val OUTBOX_OPERATIONS_PREVIEW_BYTES = 64 * 1024
private const val OUTBOX_OPERATIONS_MAX_JSON_BYTES = 20L * 1024L * 1024L

data class OutboxDeliveryDashboardCounts(
    val pending: Int = 0,
    val processing: Int = 0,
    val retry: Int = 0,
    val staged: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
) {
    val unsent: Int get() = pending + processing + retry + staged

    companion object {
        fun from(statusCounts: Map<String, Int>): OutboxDeliveryDashboardCounts =
            OutboxDeliveryDashboardCounts(
                pending = statusCounts[SyncOutboxStatus.PENDING.name].orZero(),
                processing = statusCounts[SyncOutboxStatus.PROCESSING.name].orZero(),
                retry = statusCounts[SyncOutboxStatus.RETRY.name].orZero(),
                staged = statusCounts[SyncOutboxStatus.STAGED.name].orZero(),
                sent = statusCounts[SyncOutboxStatus.SENT.name].orZero(),
                failed = statusCounts[SyncOutboxStatus.FAILED.name].orZero(),
            )

        private fun Int?.orZero(): Int = this?.coerceAtLeast(0) ?: 0
    }
}

data class OutboxDeliveryDashboard(
    val counts: OutboxDeliveryDashboardCounts = OutboxDeliveryDashboardCounts(),
    val lastSuccessAt: Long? = null,
    val lastSuccessDetail: String? = null,
    val latestErrorAt: Long? = null,
    val latestError: String? = null,
)

data class OutboxDeliveryOperationItem(
    val id: Long,
    val eventId: String,
    val businessDate: String,
    val eventType: String,
    val aggregateId: String,
    val objectKey: String,
    val status: SyncOutboxStatus,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val hasLocalJson: Boolean,
)

data class OutboxDeliveryAuditEntry(
    val id: Long,
    val eventType: String,
    val referenceId: Long,
    val detail: String,
    val operatorName: String,
    val createdAt: Long,
)

data class OutboxDeliveryJsonPreview(
    val outboxId: Long,
    val objectKey: String,
    val byteSize: Long,
    val sha256: String,
    val text: String,
    val truncated: Boolean,
)

data class OutboxDestinationTestResult(
    val success: Boolean,
    val message: String,
    val byteSize: Long = 0L,
    val sha256: String? = null,
    val temporaryFileRemoved: Boolean = false,
)

object OutboxItemRetryPolicy {
    fun canRetry(status: SyncOutboxStatus): Boolean = status == SyncOutboxStatus.FAILED

    fun targetStatus(localJsonExists: Boolean): SyncOutboxStatus =
        if (localJsonExists) SyncOutboxStatus.STAGED else SyncOutboxStatus.PENDING
}

object OutboxJsonPreviewPolicy {
    fun decode(bytes: ByteArray, totalBytes: Long): Pair<String, Boolean> =
        bytes.toString(Charsets.UTF_8) to (totalBytes > bytes.size)
}

class OutboxDeliveryOperationsStore(context: Context) {
    private val appContext = context.applicationContext

    fun dashboard(): OutboxDeliveryDashboard {
        ensureSchemas()
        RegisterDatabase(appContext).use { helper ->
            val db = helper.readableDatabase
            val statusCounts = linkedMapOf<String, Int>()
            db.rawQuery(
                "SELECT status, COUNT(*) FROM sync_outbox GROUP BY status",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) statusCounts[cursor.getString(0)] = cursor.getInt(1)
            }
            val success = db.rawQuery(
                """
                SELECT created_at, detail
                FROM operation_audit
                WHERE event_type IN ('SYNC_OUTBOX_EXTERNAL_SENT','SYNC_OUTBOX_EXTERNAL_SKIPPED_DUPLICATE')
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
            }
            val error = db.rawQuery(
                """
                SELECT updated_at, last_error
                FROM sync_outbox
                WHERE last_error IS NOT NULL AND TRIM(last_error) <> ''
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
            }
            return OutboxDeliveryDashboard(
                counts = OutboxDeliveryDashboardCounts.from(statusCounts),
                lastSuccessAt = success?.first,
                lastSuccessDetail = success?.second,
                latestErrorAt = error?.first,
                latestError = error?.second,
            )
        }
    }

    fun recentItems(limit: Int = 30): List<OutboxDeliveryOperationItem> {
        ensureSchemas()
        RegisterDatabase(appContext).use { helper ->
            val db = helper.readableDatabase
            return db.rawQuery(
                """
                SELECT o.id, o.event_id, j.business_date, j.event_type, j.aggregate_id,
                       o.object_key, o.status, o.attempt_count, o.next_attempt_at,
                       o.last_error, o.created_at, o.updated_at
                FROM sync_outbox o
                INNER JOIN sales_journal j ON j.event_id = o.event_id
                ORDER BY o.updated_at DESC, o.id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.coerceIn(1, 100).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val objectKey = cursor.getString(5)
                        add(
                            OutboxDeliveryOperationItem(
                                id = cursor.getLong(0),
                                eventId = cursor.getString(1),
                                businessDate = cursor.getString(2),
                                eventType = cursor.getString(3),
                                aggregateId = cursor.getString(4),
                                objectKey = objectKey,
                                status = SyncOutboxStatus.valueOf(cursor.getString(6)),
                                attemptCount = cursor.getInt(7),
                                nextAttemptAt = cursor.getLong(8),
                                lastError = if (cursor.isNull(9)) null else cursor.getString(9),
                                createdAt = cursor.getLong(10),
                                updatedAt = cursor.getLong(11),
                                hasLocalJson = runCatching { localFile(objectKey).isFile }.getOrDefault(false),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun retryItem(id: Long, actorName: String = "管理者"): SyncOutboxStatus {
        require(id > 0L) { "Outbox IDが不正です" }
        ensureSchemas()
        RegisterDatabase(appContext).use { helper ->
            val db = helper.writableDatabase
            val row = db.rawQuery(
                "SELECT status, object_key FROM sync_outbox WHERE id = ?",
                arrayOf(id.toString()),
            ).use { cursor ->
                require(cursor.moveToFirst()) { "対象の送信データが見つかりません" }
                SyncOutboxStatus.valueOf(cursor.getString(0)) to cursor.getString(1)
            }
            require(OutboxItemRetryPolicy.canRetry(row.first)) { "失敗状態のデータだけ個別再試行できます" }
            val target = OutboxItemRetryPolicy.targetStatus(
                runCatching { localFile(row.second).isFile }.getOrDefault(false),
            )
            val now = System.currentTimeMillis()
            val changed = db.update(
                "sync_outbox",
                ContentValues().apply {
                    put("status", target.name)
                    put("attempt_count", 0)
                    put("next_attempt_at", 0)
                    putNull("last_error")
                    putNull("processing_started_at")
                    putNull("lease_until")
                    putNull("worker_token")
                    put("updated_at", now)
                },
                "id = ? AND status = ?",
                arrayOf(id.toString(), SyncOutboxStatus.FAILED.name),
            )
            require(changed == 1) { "対象状態が変化したため再試行へ戻せませんでした" }
            OutboxDeliveryAudit.record(
                appContext,
                "SYNC_OUTBOX_ITEM_RETRY_REQUESTED",
                "outboxId=$id / target=${target.name} / ${row.second}",
                id,
                actorName,
            )
            return target
        }
    }

    fun preview(id: Long): OutboxDeliveryJsonPreview {
        require(id > 0L) { "Outbox IDが不正です" }
        ensureSchemas()
        val objectKey = RegisterDatabase(appContext).use { helper ->
            helper.readableDatabase.rawQuery(
                "SELECT object_key FROM sync_outbox WHERE id = ?",
                arrayOf(id.toString()),
            ).use { cursor ->
                require(cursor.moveToFirst()) { "対象の送信データが見つかりません" }
                cursor.getString(0)
            }
        }
        val file = localFile(objectKey)
        require(file.isFile) { "端末内JSONはまだ生成されていません" }
        require(file.length() in 1..OUTBOX_OPERATIONS_MAX_JSON_BYTES) { "端末内JSONのサイズが不正です" }
        val bytes = FileInputStream(file).use { input ->
            val buffer = ByteArray(OUTBOX_OPERATIONS_PREVIEW_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                if (read == 0) continue
                total += read
            }
            buffer.copyOf(total)
        }
        val decoded = OutboxJsonPreviewPolicy.decode(bytes, file.length())
        return OutboxDeliveryJsonPreview(
            outboxId = id,
            objectKey = objectKey,
            byteSize = file.length(),
            sha256 = sha256(FileInputStream(file)),
            text = decoded.first,
            truncated = decoded.second,
        )
    }

    fun recentAudit(limit: Int = 20): List<OutboxDeliveryAuditEntry> {
        ensureSchemas()
        RegisterDatabase(appContext).use { helper ->
            return helper.readableDatabase.rawQuery(
                """
                SELECT id, event_type, reference_id, detail, operator_name, created_at
                FROM operation_audit
                WHERE event_type LIKE 'SYNC_OUTBOX_%'
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.coerceIn(1, 100).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            OutboxDeliveryAuditEntry(
                                id = cursor.getLong(0),
                                eventType = cursor.getString(1),
                                referenceId = cursor.getLong(2),
                                detail = cursor.getString(3),
                                operatorName = cursor.getString(4),
                                createdAt = cursor.getLong(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun testDestination(treeUriText: String?): OutboxDestinationTestResult {
        val normalized = treeUriText?.trim().orEmpty()
        if (normalized.isBlank()) return OutboxDestinationTestResult(false, "送信先が未設定です")
        val treeUri = runCatching { Uri.parse(normalized) }.getOrElse {
            return OutboxDestinationTestResult(false, "送信先URIが不正です")
        }
        if (!ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, treeUri)) {
            return OutboxDestinationTestResult(false, "送信先への永続書込権限がありません")
        }

        val testAt = System.currentTimeMillis()
        val fileName = "tsuguregi-v038-write-test-$testAt.json.partial"
        val payload = "{\"schema\":\"jp.co.tenposinfo.tsuguregi.destination-test\",\"testAt\":$testAt}".toByteArray()
        var documentUri: Uri? = null
        var removed = false
        return try {
            val parent = OutboxExternalDocumentProvider.rootDocumentUri(treeUri)
            OutboxExternalDocumentProvider.findChild(appContext, treeUri, parent, fileName)?.let {
                require(OutboxExternalDocumentProvider.delete(appContext, it.uri)) {
                    "以前の送信先テストファイルを削除できません"
                }
            }
            val createdUri = OutboxExternalDocumentProvider.createFile(appContext, parent, fileName)
            documentUri = createdUri
            val written = appContext.contentResolver.openOutputStream(createdUri, "w")?.use { output ->
                output.write(payload)
                output.flush()
                payload.size.toLong()
            } ?: throw FileNotFoundException("送信先テストファイルを開けません")
            require(written == payload.size.toLong()) { "送信先テストの書込サイズが一致しません" }
            val externalBytes = appContext.contentResolver.openInputStream(createdUri)?.use { it.readBytes() }
                ?: throw FileNotFoundException("送信先テストファイルを再読込できません")
            require(externalBytes.contentEquals(payload)) { "送信先テストの読込内容が一致しません" }
            val digest = sha256(payload)
            removed = OutboxExternalDocumentProvider.delete(appContext, createdUri)
            require(removed) { "送信先テストは成功しましたが一時ファイルを削除できません" }
            OutboxDeliveryAudit.record(
                appContext,
                "SYNC_OUTBOX_DESTINATION_TEST_SUCCEEDED",
                "$fileName / ${payload.size} bytes / sha256=$digest / removed=true",
                actorName = "管理者",
            )
            OutboxDestinationTestResult(
                success = true,
                message = "書込・再読込・SHA-256照合・一時ファイル削除に成功しました",
                byteSize = payload.size.toLong(),
                sha256 = digest,
                temporaryFileRemoved = true,
            )
        } catch (error: Throwable) {
            if (!removed) documentUri?.let { removed = OutboxExternalDocumentProvider.delete(appContext, it) }
            val message = (error.message ?: error.javaClass.simpleName).take(500)
            runCatching {
                OutboxDeliveryAudit.record(
                    appContext,
                    "SYNC_OUTBOX_DESTINATION_TEST_FAILED",
                    "$fileName / removed=$removed / $message",
                    actorName = "管理者",
                )
            }
            OutboxDestinationTestResult(
                success = false,
                message = message,
                byteSize = payload.size.toLong(),
                sha256 = sha256(payload),
                temporaryFileRemoved = removed,
            )
        }
    }

    private fun ensureSchemas() {
        OperationsStore(appContext).close()
        RegisterDatabase(appContext).use { helper -> JournalOutboxSchema.ensureCore(helper.writableDatabase) }
    }

    private fun localFile(objectKey: String): File {
        OutboxDeliveryPathPolicy.segments(objectKey)
        val root = File(appContext.filesDir, "drive-sync-staging").canonicalFile
        val file = File(root, objectKey).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "ローカル同期ファイルのパスが不正です" }
        return file
    }

    private fun sha256(input: FileInputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }
}
