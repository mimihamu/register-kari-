package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/**
 * Formal v2.5 §16.9 / §16.10.
 *
 * 印刷キューの正本はESC/POS生バイトではなく、取引／文書を表す構造化snapshotとする。
 * document_print_jobs は既存コードとの互換性のため payload_text も保持するが、
 * exact rendered text は print_document_journal_v136 へ構造化メタデータと一緒に追記保存する。
 */
object PrintDocumentSnapshotV136 {
    const val SCHEMA_VERSION = 1
    const val SALE_DOCUMENT_SCHEMA = "jp.co.tenposinfo.tsuguregi.print-document.sale"
    const val SALE_JOB_REFERENCE_SCHEMA = "jp.co.tenposinfo.tsuguregi.print-job.sale-reference"
    const val DOCUMENT_JOB_REFERENCE_SCHEMA = "jp.co.tenposinfo.tsuguregi.print-job.document-reference"

    fun persistSaleSnapshot(
        db: SQLiteDatabase,
        printJobId: Long?,
        saleId: Long,
        businessDate: String,
        issuedAt: Long,
        operatorName: String,
        items: List<CartItem>,
        taxSummary: TaxSummary,
        payments: List<PaymentAllocation>,
        changeAmount: Long,
        settings: TaxInvoiceSettings,
    ): String {
        val basePayload = db.rawQuery(
            "SELECT payload_json FROM sales_journal WHERE event_type = ? AND aggregate_id = ? ORDER BY created_at DESC LIMIT 1",
            arrayOf(JournalEventType.SALE.name, saleId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "{}" }
        val payload = enrichSalePayload(
            basePayloadJson = basePayload,
            saleId = saleId,
            businessDate = businessDate,
            issuedAt = issuedAt,
            operatorName = operatorName,
            items = items,
            taxSummary = taxSummary,
            payments = payments,
            changeAmount = changeAmount,
            settings = settings,
        )
        db.update(
            "sales_journal",
            ContentValues().apply { put("payload_json", payload) },
            "event_type = ? AND aggregate_id = ?",
            arrayOf(JournalEventType.SALE.name, saleId.toString()),
        )
        if (printJobId != null) {
            db.update(
                "print_jobs",
                ContentValues().apply {
                    put("payload_version", SCHEMA_VERSION)
                    put("payload_json", payload)
                },
                "id = ?",
                arrayOf(printJobId.toString()),
            )
        }
        return payload
    }

    fun enrichSalePayload(
        basePayloadJson: String,
        saleId: Long,
        businessDate: String,
        issuedAt: Long,
        operatorName: String,
        items: List<CartItem>,
        taxSummary: TaxSummary,
        payments: List<PaymentAllocation>,
        changeAmount: Long,
        settings: TaxInvoiceSettings,
    ): String {
        val base = basePayloadJson.trim()
        val legacyFields = if (base.startsWith("{") && base.endsWith("}")) {
            base.substring(1, base.length - 1).trim()
        } else {
            ""
        }
        return buildString {
            append('{')
            if (legacyFields.isNotBlank()) append(legacyFields).append(',')
            append("\"printDocument\":{")
            append("\"schema\":\"").append(SALE_DOCUMENT_SCHEMA).append("\",")
            append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
            append("\"documentType\":\"SALES_RECEIPT\",")
            append("\"sourceId\":").append(saleId).append(',')
            append("\"businessDate\":\"").append(escape(businessDate)).append("\",")
            append("\"issuedAt\":").append(issuedAt).append(',')
            append("\"header\":{")
            append("\"storeName\":\"").append(escape(settings.issuer.storeName)).append("\",")
            append("\"address\":\"").append(escape(settings.issuer.address)).append("\",")
            append("\"phone\":\"").append(escape(settings.issuer.phone)).append("\",")
            append("\"registrationNo\":\"").append(escape(settings.issuer.registrationNumber)).append("\"},")
            append("\"transactionInfo\":{")
            append("\"operatorId\":\"").append(escape(operatorName)).append("\",")
            append("\"invoiceAggregationBasis\":\"").append(settings.invoiceAggregationBasis.name).append("\",")
            append("\"mixedTaxPolicy\":\"").append(settings.mixedTaxPolicy.name).append("\"},")
            append("\"lines\":[")
            items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append('{')
                append("\"productName\":\"").append(escape(item.product.name)).append("\",")
                append("\"receiptName\":\"").append(escape(item.product.name)).append("\",")
                append("\"productCode\":\"").append(escape(item.product.id)).append("\",")
                append("\"quantity\":").append(item.quantity).append(',')
                append("\"unitPrice\":").append(item.unitPrice).append(',')
                append("\"lineAmount\":").append(item.baseAmount).append(',')
                append("\"taxCategoryId\":\"").append(escape(item.product.taxKey)).append("\",")
                append("\"taxRateSnapshot\":").append(item.product.taxRatePercent).append(',')
                append("\"pricingModeSnapshot\":\"")
                    .append(if (item.product.taxIncluded) "TAX_INCLUDED" else "TAX_EXCLUDED")
                    .append("\",")
                append("\"receiptTaxSymbolSnapshot\":\"").append(escape(item.product.taxSymbol)).append("\",")
                append("\"discountAmount\":").append(item.discountAmount).append(',')
                append("\"note\":\"").append(escape(item.note)).append("\",")
                append("\"displayOrder\":").append(index + 1)
                append('}')
            }
            append("],")
            append("\"summary\":{")
            append("\"netAmount\":").append(taxSummary.netAmount).append(',')
            append("\"taxAmount\":").append(taxSummary.taxAmount).append(',')
            append("\"grossAmount\":").append(taxSummary.grossAmount).append(',')
            append("\"changeAmount\":").append(changeAmount).append("},")
            append("\"invoiceTaxes\":[")
            taxSummary.buckets.forEachIndexed { index, bucket ->
                if (index > 0) append(',')
                append('{')
                append("\"taxRate\":").append(bucket.ratePercent).append(',')
                append("\"taxable\":").append(bucket.taxable).append(',')
                append("\"netAmount\":").append(bucket.netAmount).append(',')
                append("\"taxAmount\":").append(bucket.taxAmount).append(',')
                append("\"grossAmount\":").append(bucket.grossAmount).append(',')
                append("\"sourceTaxKeys\":[")
                bucket.sourceTaxKeys.sorted().forEachIndexed { keyIndex, key ->
                    if (keyIndex > 0) append(',')
                    append('"').append(escape(key)).append('"')
                }
                append("]}")
            }
            append("],")
            append("\"tenders\":[")
            payments.forEachIndexed { index, payment ->
                if (index > 0) append(',')
                append('{')
                append("\"method\":\"").append(payment.method.name).append("\",")
                append("\"appliedAmount\":").append(payment.appliedAmount).append(',')
                append("\"receivedAmount\":").append(payment.receivedAmount)
                append('}')
            }
            append("],")
            append("\"footer\":{},")
            append("\"flags\":[]")
            append("}}")
        }
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

/**
 * §16.9 retention authority.
 * - electronic journal/audit records are ordinary-cleanup excluded and kept indefinitely by current implementation
 *   (therefore the formal minimum 2 years is exceeded).
 * - print error detail journal is append-only and never pruned by ordinary maintenance; 365 days is the minimum contract.
 */
object PrintJournalRetentionPolicyV136 {
    const val ELECTRONIC_JOURNAL_MIN_DAYS = 730
    const val PRINT_ERROR_DETAIL_MIN_DAYS = 365
    const val ORDINARY_CLEANUP_ALLOWED = false
}

object PrintDocumentSnapshotSchemaV136 {
    private const val SALE_INSERT_TRIGGER = "trg_v136_sale_print_payload_reference"
    private const val DOCUMENT_INSERT_TRIGGER = "trg_v136_document_print_journal"
    private const val SALE_ERROR_TRIGGER = "trg_v136_sale_print_error_journal"
    private const val DOCUMENT_ERROR_TRIGGER = "trg_v136_document_print_error_journal"

    fun ensureSale(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "print_jobs")) return
        ensureCommonTables(db)
        SchemaMigration.ensureColumn(db, "print_jobs", "payload_version", "INTEGER NOT NULL DEFAULT 1")
        SchemaMigration.ensureColumn(db, "print_jobs", "payload_json", "TEXT")
        SchemaMigration.ensureColumn(db, "print_jobs", "rendered_hash", "TEXT")
        SchemaMigration.ensureColumn(db, "print_jobs", "source_job_id", "INTEGER")
        db.execSQL(
            """
            UPDATE print_jobs
               SET payload_version = ${PrintDocumentSnapshotV136.SCHEMA_VERSION},
                   payload_json = '{"schema":"${PrintDocumentSnapshotV136.SALE_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"saleId":' || sale_id || ',"paperWidthMm":' || paper_width_mm || '}'
             WHERE payload_json IS NULL OR trim(payload_json) = ''
            """.trimIndent(),
        )
        db.execSQL("DROP TRIGGER IF EXISTS $SALE_INSERT_TRIGGER")
        db.execSQL(
            """
            CREATE TRIGGER $SALE_INSERT_TRIGGER
            AFTER INSERT ON print_jobs
            FOR EACH ROW
            WHEN NEW.payload_json IS NULL OR trim(NEW.payload_json) = ''
            BEGIN
                UPDATE print_jobs
                   SET payload_version = ${PrintDocumentSnapshotV136.SCHEMA_VERSION},
                       payload_json = '{"schema":"${PrintDocumentSnapshotV136.SALE_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"saleId":' || NEW.sale_id || ',"paperWidthMm":' || NEW.paper_width_mm || '}'
                 WHERE id = NEW.id;
            END
            """.trimIndent(),
        )
        ensureErrorTrigger(db, "print_jobs", SALE_ERROR_TRIGGER)
    }

    fun ensureDocument(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "document_print_jobs")) return
        ensureCommonTables(db)
        SchemaMigration.ensureColumn(db, "document_print_jobs", "payload_version", "INTEGER NOT NULL DEFAULT 1")
        SchemaMigration.ensureColumn(db, "document_print_jobs", "payload_json", "TEXT")
        SchemaMigration.ensureColumn(db, "document_print_jobs", "rendered_hash", "TEXT")
        SchemaMigration.ensureColumn(db, "document_print_jobs", "source_job_id", "INTEGER")
        backfillDocumentJournal(db)
        db.execSQL("DROP TRIGGER IF EXISTS $DOCUMENT_INSERT_TRIGGER")
        db.execSQL(
            """
            CREATE TRIGGER $DOCUMENT_INSERT_TRIGGER
            AFTER INSERT ON document_print_jobs
            FOR EACH ROW
            BEGIN
                INSERT OR IGNORE INTO print_document_journal_v136(
                    job_id, document_type, reference_id, source_job_id, paper_width_mm,
                    rendered_text, rendered_hash, created_at
                ) VALUES(
                    NEW.id, NEW.document_type, NEW.reference_id, NEW.source_job_id, NEW.paper_width_mm,
                    NEW.payload_text, NULL, NEW.created_at
                );
                UPDATE document_print_jobs
                   SET payload_version = ${PrintDocumentSnapshotV136.SCHEMA_VERSION},
                       payload_json = '{"schema":"${PrintDocumentSnapshotV136.DOCUMENT_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"journalJobId":' || NEW.id || ',"documentType":"' || NEW.document_type || '","referenceId":' || NEW.reference_id || ',"paperWidthMm":' || NEW.paper_width_mm || '}'
                 WHERE id = NEW.id AND (payload_json IS NULL OR trim(payload_json) = '');
            END
            """.trimIndent(),
        )
        ensureErrorTrigger(db, "document_print_jobs", DOCUMENT_ERROR_TRIGGER)
    }

    fun recordRenderedHash(db: SQLiteDatabase, table: String, jobId: Long, payload: ByteArray): String {
        require(table == "print_jobs" || table == "document_print_jobs") { "unsupported print job table" }
        val hash = PrintDocumentSnapshotV136.sha256Hex(payload)
        db.update(
            table,
            ContentValues().apply { put("rendered_hash", hash) },
            "id = ?",
            arrayOf(jobId.toString()),
        )
        if (table == "document_print_jobs") {
            db.update(
                "print_document_journal_v136",
                ContentValues().apply { put("rendered_hash", hash) },
                "job_id = ?",
                arrayOf(jobId.toString()),
            )
        }
        return hash
    }

    private fun ensureCommonTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS print_document_journal_v136 (
                job_id INTEGER PRIMARY KEY,
                document_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                source_job_id INTEGER,
                paper_width_mm INTEGER NOT NULL,
                rendered_text TEXT NOT NULL,
                rendered_hash TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS print_error_journal_v136 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_table TEXT NOT NULL,
                job_id INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_detail TEXT NOT NULL,
                occurred_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS print_journal_retention_policy_v136 (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                electronic_journal_min_days INTEGER NOT NULL,
                print_error_detail_min_days INTEGER NOT NULL,
                ordinary_cleanup_allowed INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO print_journal_retention_policy_v136(
                id, electronic_journal_min_days, print_error_detail_min_days, ordinary_cleanup_allowed
            ) VALUES(
                1,
                ${PrintJournalRetentionPolicyV136.ELECTRONIC_JOURNAL_MIN_DAYS},
                ${PrintJournalRetentionPolicyV136.PRINT_ERROR_DETAIL_MIN_DAYS},
                ${if (PrintJournalRetentionPolicyV136.ORDINARY_CLEANUP_ALLOWED) 1 else 0}
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_print_error_journal_job_v136 ON print_error_journal_v136(job_table, job_id, occurred_at)")
    }

    private fun backfillDocumentJournal(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO print_document_journal_v136(
                job_id, document_type, reference_id, source_job_id, paper_width_mm,
                rendered_text, rendered_hash, created_at
            )
            SELECT id, document_type, reference_id, source_job_id, paper_width_mm,
                   payload_text, rendered_hash, created_at
              FROM document_print_jobs
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE document_print_jobs
               SET payload_version = ${PrintDocumentSnapshotV136.SCHEMA_VERSION},
                   payload_json = '{"schema":"${PrintDocumentSnapshotV136.DOCUMENT_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"journalJobId":' || id || ',"documentType":"' || document_type || '","referenceId":' || reference_id || ',"paperWidthMm":' || paper_width_mm || '}'
             WHERE payload_json IS NULL OR trim(payload_json) = ''
            """.trimIndent(),
        )
    }

    private fun ensureErrorTrigger(db: SQLiteDatabase, table: String, trigger: String) {
        db.execSQL("DROP TRIGGER IF EXISTS $trigger")
        db.execSQL(
            """
            CREATE TRIGGER $trigger
            AFTER UPDATE OF last_error ON $table
            FOR EACH ROW
            WHEN NEW.last_error IS NOT NULL
             AND trim(NEW.last_error) <> ''
             AND (OLD.last_error IS NULL OR OLD.last_error <> NEW.last_error)
            BEGIN
                INSERT INTO print_error_journal_v136(job_table, job_id, status, error_detail, occurred_at)
                VALUES('$table', NEW.id, NEW.status, NEW.last_error, NEW.updated_at);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO print_error_journal_v136(job_table, job_id, status, error_detail, occurred_at)
            SELECT '$table', p.id, p.status, p.last_error, p.updated_at
              FROM $table p
             WHERE p.last_error IS NOT NULL
               AND trim(p.last_error) <> ''
               AND NOT EXISTS(
                   SELECT 1 FROM print_error_journal_v136 e
                    WHERE e.job_table = '$table'
                      AND e.job_id = p.id
                      AND e.error_detail = p.last_error
               )
            """.trimIndent(),
        )
    }
}
