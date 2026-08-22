package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HeldTicket(
    val id: Long,
    val name: String,
    val operatorName: String,
    val createdAt: Long,
    val itemCount: Int,
    val totalAmount: Long,
    val guestCount: Int = 0,
)

class RegisterDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val applicationContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        createProductsTable(db)
        createCartTable(db)
        createHeldTicketTables(db)
        createSalesTables(db)
        createSalePaymentsTable(db)
        createPrintJobsTable(db)
        insertSeedProducts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        LegacyDatabaseMigrationV084.migrate(db, oldVersion)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        CartCorrectionSchemaV135.ensure(db)
        SaleGuestCountRuntimeV135.ensureSchema(db)
        SaleTaxSnapshotStoreV136.ensureSchema(db)
    }

    fun loadProducts(): List<Product> {
        readableDatabase.query(
            "products",
            arrayOf("id", "name", "unit_price", "tax_category", "display_order"),
            null,
            null,
            null,
            null,
            "display_order ASC",
        ).use { cursor ->
            val result = mutableListOf<Product>()
            while (cursor.moveToNext()) {
                result += Product(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    unitPrice = cursor.getLong(2),
                    taxCategory = TaxCategory.valueOf(cursor.getString(3)),
                    displayOrder = cursor.getInt(4),
                )
            }
            return result
        }
    }

    fun loadCart(): List<CartItem> {
        readableDatabase.query(
            "cart_items",
            CART_COLUMNS,
            null,
            null,
            null,
            null,
            "line_no ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) result += cursor.toCartItem()
            return LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_CART, 0L, result)
        }
    }

    fun saveCart(items: List<CartItem>) {
        writableDatabase.runInTransaction {
            delete("cart_items", null, null)
            items.forEachIndexed { index, item ->
                insertOrThrow("cart_items", null, item.toContentValues().apply { put("line_no", index + 1) })
            }
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, items)
        }
    }

    fun loadCartCorrections(): List<CartCorrectionRecordV135> =
        CartCorrectionSchemaV135.load(readableDatabase)

    fun clearCartCorrections() {
        writableDatabase.runInTransaction {
            CartCorrectionSchemaV135.clear(this)
        }
    }

    /**
     * COR-002/COR-003: active cart rewrite and cancellation history append are committed atomically.
     * Cancellation history is not part of [CartItem], so tax/payment/sale totals only see active rows.
     */
    fun applyCartCorrection(
        targetIndex: Int,
        cancelQuantity: Int,
        correctionType: CartCorrectionTypeV135,
        operatorName: String,
    ): CartCorrectionResultV135 {
        require(operatorName.isNotBlank()) { "担当者が必要です" }
        return writableDatabase.runInTransactionWithResult {
            val rawItems = query(
                "cart_items",
                CART_COLUMNS,
                null,
                null,
                null,
                null,
                "line_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toCartItem())
                }
            }
            val currentItems = LineTaxSnapshotStore.apply(
                this,
                LineTaxSnapshotStore.SCOPE_CART,
                0L,
                rawItems,
            )
            val result = CartCorrectionPolicyV135.apply(
                items = currentItems,
                targetIndex = targetIndex,
                cancelQuantity = cancelQuantity,
                correctionType = correctionType,
                operatorName = operatorName,
                createdAt = System.currentTimeMillis(),
            )

            delete("cart_items", null, null)
            result.items.forEachIndexed { index, item ->
                insertOrThrow(
                    "cart_items",
                    null,
                    item.toContentValues().apply { put("line_no", index + 1) },
                )
            }
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, result.items)
            val historyId = CartCorrectionSchemaV135.insert(this, result.record)
            result.copy(record = result.record.copy(id = historyId))
        }
    }

    fun holdCart(name: String, operatorName: String, items: List<CartItem>): Long {
        require(items.isNotEmpty()) { "Cannot hold an empty cart" }
        return writableDatabase.runInTransactionWithResult {
            val ticketId = insertOrThrow(
                "held_tickets",
                null,
                ContentValues().apply {
                    put("name", name)
                    put("operator_name", operatorName)
                    put("created_at", System.currentTimeMillis())
                },
            )
            items.forEach { item ->
                insertOrThrow(
                    "held_ticket_items",
                    null,
                    item.toContentValues().apply { put("ticket_id", ticketId) },
                )
            }
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_HELD, ticketId, items)
            ticketId
        }
    }

    fun listHeldTickets(): List<HeldTicket> {
        readableDatabase.rawQuery(
            """
            SELECT t.id, t.name, t.operator_name, t.created_at,
                   COALESCE(SUM(i.quantity), 0) AS item_count,
                   COALESCE(MAX(g.guest_count), 0) AS guest_count
            FROM held_tickets t
            LEFT JOIN held_ticket_items i ON i.ticket_id = t.id
            LEFT JOIN held_ticket_guest_count_v135 g ON g.ticket_id = t.id
            GROUP BY t.id, t.name, t.operator_name, t.created_at
            ORDER BY t.created_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val result = mutableListOf<HeldTicket>()
            while (cursor.moveToNext()) {
                val items = loadHeldTicket(cursor.getLong(0))
                result += HeldTicket(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    operatorName = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                    itemCount = cursor.getInt(4),
                    guestCount = cursor.getInt(5),
                    totalAmount = TaxEngine.calculate(items).grossAmount,
                )
            }
            return result
        }
    }

    fun loadHeldTicket(ticketId: Long): List<CartItem> {
        readableDatabase.query(
            "held_ticket_items",
            CART_COLUMNS,
            "ticket_id = ?",
            arrayOf(ticketId.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) result += cursor.toCartItem()
            return LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_HELD, ticketId, result)
        }
    }

    fun deleteHeldTicket(ticketId: Long) {
        writableDatabase.runInTransaction {
            delete("held_tickets", "id = ?", arrayOf(ticketId.toString()))
            delete("line_tax_snapshots", "scope = ? AND owner_id = ?", arrayOf(LineTaxSnapshotStore.SCOPE_HELD, ticketId.toString()))
        }
    }

    /**
     * 売上・明細・支払・印刷キュー・税snapshotを同一SQLiteトランザクションで確定する。
     */
    fun saveSale(
        operatorName: String,
        items: List<CartItem>,
        paymentState: PaymentState,
        commitKey: String? = null,
    ): Long {
        require(items.isNotEmpty()) { "Cannot save an empty sale" }
        val taxSettings = TaxInvoiceSettingsStore(applicationContext).load()
        val mixedTaxPolicy = taxSettings.mixedTaxPolicy
        val printerConfiguration = PrinterPaperSettingPolicy.currentConfiguration(applicationContext)
        val paperWidthMm = PrinterPaperSettingPolicy.normalizeWidthMm(printerConfiguration.paperWidthMm)
        TaxEngine.validateMixedTax(items, mixedTaxPolicy)
        val summary = TaxEngine.calculate(items)
        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }
        val normalizedCommitKey = commitKey?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedCommitKey != null) {
            require(PaymentCommitKey.isValid(normalizedCommitKey)) { "Invalid sale commit key" }
        }
        val cartFingerprint = PaymentDraftFingerprint.of(items)
        SaleCommitIdempotencySchema.ensure(writableDatabase)
        SaleCommitIdempotencySchema.cleanup(writableDatabase)
        BusinessSessionSchema.ensure(writableDatabase)
        val createdAt = System.currentTimeMillis()
        return writableDatabase.runInTransactionWithResult {
            if (normalizedCommitKey != null) {
                val existing = SaleCommitIdempotencySchema.find(this, normalizedCommitKey)
                if (existing != null) {
                    SaleCommitIdempotencySchema.requireCompatible(
                        existing = existing,
                        cartFingerprint = cartFingerprint,
                        totalAmount = summary.grossAmount,
                    )
                    delete("cart_items", null, null)
                    delete(
                        "line_tax_snapshots",
                        "scope = ? AND owner_id = ?",
                        arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),
                    )
                    CartCorrectionSchemaV135.clear(this)
                    return@runInTransactionWithResult existing.saleId
                }
            }
            val businessLink = BusinessSessionSchema.currentOpen(this)
                ?: throw IllegalStateException("営業開始後に会計してください")
            val saleId = insertOrThrow(
                "sales",
                null,
                ContentValues().apply {
                    put("operator_name", operatorName)
                    put("payment_method", paymentState.allocations.joinToString("+") { it.method.displayName })
                    put("net_amount", summary.netAmount)
                    put("tax_amount", summary.taxAmount)
                    put("total_amount", summary.grossAmount)
                    put("deposit_amount", paymentState.allocations.sumOf { it.receivedAmount })
                    put("change_amount", paymentState.changeAmount)
                    businessLink.sessionId?.let { put("business_session_id", it) }
                    put("business_date", businessLink.businessDate)
                    put("created_at", createdAt)
                    put("print_count", 0)
                },
            )
            items.forEach { item ->
                insertOrThrow(
                    "sale_items",
                    null,
                    ContentValues().apply {
                        put("sale_id", saleId)
                        put("product_id", item.product.id)
                        put("product_name", item.product.name)
                        put("unit_price", item.unitPrice)
                        put("tax_category", item.product.taxCategory.name)
                        put("quantity", item.quantity)
                        put("discount_amount", item.discountAmount)
                        put("note", item.note)
                    },
                )
            }
            paymentState.allocations.forEachIndexed { index, payment ->
                insertOrThrow(
                    "sale_payments",
                    null,
                    ContentValues().apply {
                        put("sale_id", saleId)
                        put("sequence_no", index + 1)
                        put("payment_method", payment.method.name)
                        put("applied_amount", payment.appliedAmount)
                        put("received_amount", payment.receivedAmount)
                    },
                )
            }
            if (ReceiptAutoPrintPolicyV136.shouldCreateAutomaticReceiptJob(
                    printerConfiguration.receiptAutoPrintEnabled,
                )
            ) {
                insertPrintJob(this, saleId, paperWidthMm, createdAt)
            }
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)
            SaleTaxSnapshotStoreV136.save(
                db = this,
                saleId = saleId,
                items = items,
                summary = summary,
                settings = taxSettings,
                recordedAt = createdAt,
            )
            JournalOutboxSchema.recordSale(
                db = this,
                saleId = saleId,
                totalAmount = summary.grossAmount,
                taxAmount = summary.taxAmount,
                createdAt = createdAt,
                businessDate = businessLink.businessDate,
                folderName = DriveSyncSettingsStore.load(applicationContext).folderName,
            )
            SaleTaxSnapshotStoreV136.enrichSaleJournal(this, saleId)
            if (normalizedCommitKey != null) {
                SaleCommitIdempotencySchema.record(
                    db = this,
                    commitKey = normalizedCommitKey,
                    saleId = saleId,
                    cartFingerprint = cartFingerprint,
                    totalAmount = summary.grossAmount,
                    createdAt = createdAt,
                )
            }
            // 売上確定と作業中カート消去を同一トランザクションに含める。
            // 確定直後にプロセスが停止しても、確定済み明細を未会計として復元しない。
            delete("cart_items", null, null)
            delete(
                "line_tax_snapshots",
                "scope = ? AND owner_id = ?",
                arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),
            )
            CartCorrectionSchemaV135.clear(this)
            saleId
        }
    }

    fun listSales(limit: Int = 200): List<SaleSummaryRecord> {
        readableDatabase.query(
            "sales",
            arrayOf(
                "id",
                "operator_name",
                "payment_method",
                "total_amount",
                "tax_amount",
                "change_amount",
                "created_at",
                "print_count",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 1_000).toString(),
        ).use { cursor ->
            val result = mutableListOf<SaleSummaryRecord>()
            while (cursor.moveToNext()) result += cursor.toSaleSummary()
            return result
        }
    }

    fun loadSaleDetail(saleId: Long): SaleDetailRecord? {
        val summary = readableDatabase.query(
            "sales",
            arrayOf(
                "id",
                "operator_name",
                "payment_method",
                "total_amount",
                "tax_amount",
                "change_amount",
                "created_at",
                "print_count",
            ),
            "id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toSaleSummary() else null } ?: return null

        val items = readableDatabase.query(
            "sale_items",
            arrayOf(
                "product_id",
                "product_name",
                "unit_price",
                "tax_category",
                "quantity",
                "discount_amount",
                "note",
            ),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) {
                val category = TaxCategory.valueOf(cursor.getString(3))
                val product = Product(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    unitPrice = cursor.getLong(2),
                    taxCategory = category,
                    displayOrder = result.size + 1,
                )
                result += CartItem(
                    product = product,
                    quantity = cursor.getInt(4),
                    unitPrice = cursor.getLong(2),
                    discountAmount = cursor.getLong(5),
                    note = cursor.getString(6),
                )
            }
            result
        }

        val payments = readableDatabase.query(
            "sale_payments",
            arrayOf("payment_method", "applied_amount", "received_amount"),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            "sequence_no ASC",
        ).use { cursor ->
            val result = mutableListOf<PaymentAllocation>()
            while (cursor.moveToNext()) {
                result += PaymentAllocation(
                    method = PaymentMethod.valueOf(cursor.getString(0)),
                    appliedAmount = cursor.getLong(1),
                    receivedAmount = cursor.getLong(2),
                )
            }
            result
        }
        val snapshotItems = LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)
        val saleTaxSnapshot = SaleTaxSnapshotStoreV136.load(readableDatabase, saleId)
        return SaleDetailRecord(
            summary = summary,
            items = snapshotItems,
            payments = payments,
            taxSummary = saleTaxSnapshot?.toTaxSummary() ?: TaxEngine.calculate(snapshotItems),
            invoiceAggregationBasis = saleTaxSnapshot?.invoiceAggregationBasis
                ?: InvoiceAggregationBasisV136.TAX_INCLUDED,
            taxSnapshotLegacyFallback = saleTaxSnapshot == null,
        )
    }

    fun enqueueReprint(saleId: Long, actor: String = "SYSTEM"): Long {
        val detail = loadSaleDetail(saleId) ?: throw IllegalArgumentException("Sale not found")
        val normalizedActor = actor.trim().ifBlank { "SYSTEM" }.take(100)
        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(applicationContext)
        // RCP-004: 後レシートは自動発行OFFでも可能。初回自動ジョブと区別するため
        // 売上確定時刻より必ず後の作成時刻を持たせ、登録操作を監査記録と同一transactionで確定する。
        val now = maxOf(System.currentTimeMillis(), detail.summary.createdAt + 1L)
        return writableDatabase.runInTransactionWithResult {
            OperationAuditSchemaV136.ensure(this)
            val previousReprintCount = query(
                "operation_audit",
                arrayOf("COUNT(*)"),
                "event_type = ? AND reference_id = ?",
                arrayOf("SALE_RECEIPT_REPRINT_ENQUEUED", saleId.toString()),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            val normalizedWidth = if (paperWidthMm >= 80) 80 else 58
            val jobId = insertOrThrow(
                "print_jobs",
                null,
                ContentValues().apply {
                    put("sale_id", saleId)
                    put("paper_width_mm", normalizedWidth)
                    put("status", PrintJobStatus.PENDING.name)
                    put("attempt_count", 0)
                    putNull("last_error")
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "SALE_RECEIPT_REPRINT_ENQUEUED")
                    put("reference_id", saleId)
                    put(
                        "detail",
                        "再発行回数=${previousReprintCount + 1}; print_job_id=$jobId; paper_width_mm=$normalizedWidth",
                    )
                    put("operator_name", normalizedActor)
                    put("created_at", now)
                },
            )
            jobId
        }
    }

    fun listPrintJobs(limit: Int = 100): List<PrintJobRecord> {
        readableDatabase.query(
            "print_jobs",
            PRINT_JOB_COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            val result = mutableListOf<PrintJobRecord>()
            while (cursor.moveToNext()) result += cursor.toPrintJob()
            return result
        }
    }

    fun loadPrintJob(jobId: Long): PrintJobRecord? = loadPrintJob(readableDatabase, jobId)

    fun nextPrintableJob(): PrintJobRecord? = readableDatabase.query(
        "print_jobs",
        PRINT_JOB_COLUMNS,
        "status IN (?, ?)",
        arrayOf(PrintJobStatus.PENDING.name, PrintJobStatus.RETRY.name),
        null,
        null,
        "created_at ASC, id ASC",
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPrintJob() else null }

    fun claimNextPrintableJob(): PrintJobRecord? = writableDatabase.runInTransactionWithResult {
        val candidate = query(
            "print_jobs",
            PRINT_JOB_COLUMNS,
            "status IN (?, ?)",
            arrayOf(PrintJobStatus.PENDING.name, PrintJobStatus.RETRY.name),
            null,
            null,
            "created_at ASC, id ASC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toPrintJob() else null }
            ?: return@runInTransactionWithResult null
        claimPrintJobWithin(this, candidate)
    }

    fun claimPrintJob(jobId: Long): PrintJobRecord? = writableDatabase.runInTransactionWithResult {
        val candidate = loadPrintJob(this, jobId) ?: return@runInTransactionWithResult null
        claimPrintJobWithin(this, candidate)
    }

    fun markPrintStarted(jobId: Long) {
        check(claimPrintJob(jobId) != null) { "印刷ジョブの状態が変更されたため開始できませんでした" }
    }

    fun markPrintCompleted(jobId: Long) {
        writableDatabase.runInTransactionWithResult {
            val current = loadPrintJob(this, jobId)
                ?: error("印刷ジョブが見つかりません")
            if (current.status == PrintJobStatus.COMPLETED) return@runInTransactionWithResult Unit
            check(current.status == PrintJobStatus.PRINTING) {
                "印刷中ではないジョブを完了へ変更できません"
            }
            val updated = update(
                "print_jobs",
                ContentValues().apply {
                    put("status", PrintJobStatus.COMPLETED.name)
                    putNull("last_error")
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ? AND status = ? AND attempt_count = ?",
                arrayOf(jobId.toString(), PrintJobStatus.PRINTING.name, current.attemptCount.toString()),
            )
            check(updated == 1) { "印刷ジョブの状態が変更されたため完了を確定できませんでした" }
            execSQL("UPDATE sales SET print_count = print_count + 1 WHERE id = ?", arrayOf(current.saleId))
            Unit
        }
    }

    fun markPrintFailed(jobId: Long, error: String, permanent: Boolean = false) {
        writableDatabase.runInTransactionWithResult {
            val current = loadPrintJob(this, jobId) ?: return@runInTransactionWithResult Unit
            if (current.status != PrintJobStatus.PRINTING) return@runInTransactionWithResult Unit
            val manualConfirmation = error.contains("送信結果が不明") || error.contains("自動再試行しません")
            val status = if (permanent || manualConfirmation || current.attemptCount >= 4) {
                PrintJobStatus.FAILED
            } else {
                PrintJobStatus.RETRY
            }
            update(
                "print_jobs",
                ContentValues().apply {
                    put("status", status.name)
                    put("last_error", error.take(500))
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ? AND status = ? AND attempt_count = ?",
                arrayOf(jobId.toString(), PrintJobStatus.PRINTING.name, current.attemptCount.toString()),
            )
            Unit
        }
    }

    fun retryPrintJob(jobId: Long) {
        writableDatabase.runInTransactionWithResult {
            val current = loadPrintJob(this, jobId)
                ?: throw IllegalArgumentException("印刷ジョブが見つかりません")
            require(current.status != PrintJobStatus.COMPLETED) { "完了済みジョブは再送できません。再印字を登録してください" }
            require(current.status != PrintJobStatus.DISCARDED) { "破棄済みジョブは再送できません" }
            require(current.status != PrintJobStatus.PRINTING) { "印刷中のジョブは操作できません" }
            require(PrintQueueAtomicityV115.mayRetry(current.status)) { "このジョブは再送できません" }
            val updated = update(
                "print_jobs",
                ContentValues().apply {
                    put("status", PrintJobStatus.RETRY.name)
                    putNull("last_error")
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ? AND status = ? AND attempt_count = ?",
                arrayOf(jobId.toString(), current.status.name, current.attemptCount.toString()),
            )
            check(updated == 1) { "印刷ジョブの状態が変更されたため再試行へ戻せませんでした" }
            Unit
        }
    }

    fun discardPrintJob(
        jobId: Long,
        reason: String,
        auditDetail: String,
        actor: String,
    ) {
        val current = loadPrintJob(jobId)
            ?: throw IllegalArgumentException("売上印刷ジョブが見つかりません")
        require(current.status != PrintJobStatus.COMPLETED) { "完了済みジョブは破棄できません" }
        require(current.status != PrintJobStatus.DISCARDED) { "このジョブは既に破棄済みです" }
        require(current.status != PrintJobStatus.PRINTING) { "印刷中のジョブは破棄できません" }
        require(reason.trim().length >= 4) { "破棄理由を4文字以上で入力してください" }
        require(actor.isNotBlank()) { "監査担当者が必要です" }
        writableDatabase.runInTransaction {
            val updated = update(
                "print_jobs",
                ContentValues().apply {
                    put("status", PrintJobStatus.DISCARDED.name)
                    put("last_error", "破棄理由：${reason.trim()}".take(500))
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ? AND status NOT IN (?, ?, ?)",
                arrayOf(
                    jobId.toString(),
                    PrintJobStatus.COMPLETED.name,
                    PrintJobStatus.DISCARDED.name,
                    PrintJobStatus.PRINTING.name,
                ),
            )
            check(updated == 1) { "印刷ジョブの状態が変更されたため破棄できませんでした" }
            insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "PRINT_JOB_DISCARDED")
                    put("reference_id", jobId)
                    put("detail", auditDetail.trim().take(1_000))
                    put("operator_name", actor.trim().take(100))
                    put("created_at", System.currentTimeMillis())
                },
            )
        }
    }

    private fun loadPrintJob(db: SQLiteDatabase, jobId: Long): PrintJobRecord? = db.query(
        "print_jobs",
        PRINT_JOB_COLUMNS,
        "id = ?",
        arrayOf(jobId.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPrintJob() else null }

    private fun claimPrintJobWithin(db: SQLiteDatabase, candidate: PrintJobRecord): PrintJobRecord? {
        if (!PrintQueueAtomicityV115.mayClaim(candidate.status)) return null
        val now = System.currentTimeMillis()
        val attempt = candidate.attemptCount + 1
        val updated = db.update(
            "print_jobs",
            ContentValues().apply {
                put("status", PrintJobStatus.PRINTING.name)
                put("attempt_count", attempt)
                putNull("last_error")
                put("updated_at", now)
            },
            "id = ? AND status = ? AND attempt_count = ?",
            arrayOf(candidate.id.toString(), candidate.status.name, candidate.attemptCount.toString()),
        )
        return if (updated == 1) {
            candidate.copy(
                status = PrintJobStatus.PRINTING,
                attemptCount = attempt,
                lastError = null,
                updatedAt = now,
            )
        } else {
            null
        }
    }

    private fun Cursor.toSaleSummary() = SaleSummaryRecord(
        id = getLong(0),
        operatorName = getString(1),
        paymentLabel = getString(2),
        totalAmount = getLong(3),
        taxAmount = getLong(4),
        changeAmount = getLong(5),
        createdAt = getLong(6),
        printCount = getInt(7),
    )

    private fun Cursor.toPrintJob() = PrintJobRecord(
        id = getLong(0),
        saleId = getLong(1),
        paperWidthMm = getInt(2),
        status = PrintJobStatus.valueOf(getString(3)),
        attemptCount = getInt(4),
        lastError = if (isNull(5)) null else getString(5),
        createdAt = getLong(6),
        updatedAt = getLong(7),
    )

    private fun Cursor.toCartItem(): CartItem {
        val product = Product(
            id = getString(0),
            name = getString(1),
            unitPrice = getLong(2),
            taxCategory = TaxCategory.valueOf(getString(3)),
            displayOrder = getInt(4),
        )
        return CartItem(
            product = product,
            quantity = getInt(5),
            unitPrice = getLong(2),
            discountAmount = getLong(6),
            note = getString(7),
            lineId = getString(8),
        )
    }

    private fun CartItem.toContentValues() = ContentValues().apply {
        put("product_id", product.id)
        put("product_name", product.name)
        put("unit_price", unitPrice)
        put("tax_category", product.taxCategory.name)
        put("display_order", product.displayOrder)
        put("quantity", quantity)
        put("discount_amount", discountAmount)
        put("note", note)
        put("line_id", lineId)
    }

    private fun createProductsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE products (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                display_order INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createCartTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cart_items (
                line_no INTEGER PRIMARY KEY,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                display_order INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                line_id TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
    }

    private fun createHeldTicketTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE held_tickets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE held_ticket_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ticket_id INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                display_order INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                line_id TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(ticket_id) REFERENCES held_tickets(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createSalesTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sales (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operator_name TEXT NOT NULL,
                payment_method TEXT NOT NULL,
                net_amount INTEGER NOT NULL,
                tax_amount INTEGER NOT NULL,
                total_amount INTEGER NOT NULL,
                deposit_amount INTEGER NOT NULL,
                change_amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                print_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sale_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createSalePaymentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sale_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                payment_method TEXT NOT NULL,
                applied_amount INTEGER NOT NULL,
                received_amount INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createPrintJobsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS print_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                paper_width_mm INTEGER NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_print_jobs_status ON print_jobs(status, created_at)")
    }

    private fun insertPrintJob(db: SQLiteDatabase, saleId: Long, paperWidthMm: Int, now: Long) {
        db.insertOrThrow(
            "print_jobs",
            null,
            ContentValues().apply {
                put("sale_id", saleId)
                put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
                put("status", PrintJobStatus.PENDING.name)
                put("attempt_count", 0)
                putNull("last_error")
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    private fun migrateCartToLineNumber(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE cart_items RENAME TO cart_items_v3")
        createCartTable(db)
        db.execSQL(
            """
            INSERT INTO cart_items (
                line_no, product_id, product_name, unit_price, tax_category,
                display_order, quantity, discount_amount, note
            )
            SELECT rowid, product_id, product_name, unit_price, tax_category,
                   display_order, quantity, discount_amount, note
            FROM cart_items_v3
            ORDER BY rowid
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE cart_items_v3")
    }

    private fun insertSeedProducts(db: SQLiteDatabase) {
        val products = listOf(
            Product("P0001", "生ビール", 600, TaxCategory.INCLUDED_10, 1),
            Product("P0002", "ハイボール", 520, TaxCategory.INCLUDED_10, 2),
            Product("P0003", "ウーロン茶", 300, TaxCategory.INCLUDED_10, 3),
            Product("P0010", "枝豆", 420, TaxCategory.INCLUDED_10, 4),
            Product("P0011", "唐揚げ", 680, TaxCategory.INCLUDED_10, 5),
            Product("P0012", "刺身盛合せ", 1680, TaxCategory.INCLUDED_10, 6),
            Product("P0020", "焼き鳥", 180, TaxCategory.INCLUDED_10, 7),
            Product("P0021", "弁当", 800, TaxCategory.EXCLUDED_8, 8),
            Product("P0022", "お土産", 1200, TaxCategory.EXCLUDED_10, 9),
            Product("P0030", "サービス品", 100, TaxCategory.NON_TAXABLE, 10),
        )
        products.forEach { product ->
            db.insertOrThrow(
                "products",
                null,
                ContentValues().apply {
                    put("id", product.id)
                    put("name", product.name)
                    put("unit_price", product.unitPrice)
                    put("tax_category", product.taxCategory.name)
                    put("display_order", product.displayOrder)
                },
            )
        }
    }

    companion object {
        private const val DATABASE_NAME = "register.db"
        private const val DATABASE_VERSION = 4

        private val CART_COLUMNS = arrayOf(
            "product_id",
            "product_name",
            "unit_price",
            "tax_category",
            "display_order",
            "quantity",
            "discount_amount",
            "note",
            "line_id",
        )

        private val PRINT_JOB_COLUMNS = arrayOf(
            "id",
            "sale_id",
            "paper_width_mm",
            "status",
            "attempt_count",
            "last_error",
            "created_at",
            "updated_at",
        )
    }
}

private inline fun <T> SQLiteDatabase.runInTransactionWithResult(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
