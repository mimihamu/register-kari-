package jp.co.tenposinfo.register

import android.content.ContentValues
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal data class PaymentDraftLoadResult(
    val state: PaymentState,
    val restored: Boolean,
    val updatedAt: Long?,
    val commitKey: String?,
    val integrityIssue: String? = null,
    val recoveredConfirmedSaleId: Long? = null,
    val pendingExternalV188: ExternalTenderPendingV188? = null,
)

internal object PaymentDraftFingerprint {
    fun of(items: List<CartItem>): String = digest(items, exactIdentity = true)

    /** Only for detecting pre-v1.81 interrupted drafts; never use for restoring tender. */
    fun legacyV180Of(items: List<CartItem>): String = digest(items, exactIdentity = false)

    private fun digest(items: List<CartItem>, exactIdentity: Boolean): String {
        val canonical = items.mapIndexed { index, item ->
            buildList<Any> {
                add(index)
                add(item.product.id)
                add(item.product.name)
                add(item.quantity)
                if (exactIdentity) {
                    add(item.quantityHundredths)
                    add(item.lineId)
                }
                add(item.unitPrice)
                add(item.discountAmount)
                add(item.note)
                add(item.product.taxKey)
                add(item.product.taxRatePercent)
                add(item.product.taxIncluded)
                add(item.product.taxable)
            }.joinToString("\u001F")
        }.joinToString("\u001E")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal object PaymentCommitKey {
    fun newKey(): String = UUID.randomUUID().toString()

    fun isValid(value: String?): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess
}

/**
 * 支払入力途中の割当と会計冪等キーをSQLiteへ保存する。
 * カート指紋が一致する場合だけ復元し、別伝票の支払を誤適用しない。
 */
internal class PaymentDraftStore(
    private val database: RegisterDatabase,
) {
    fun save(items: List<CartItem>, state: PaymentState, commitKey: String) {
        require(PaymentCommitKey.isValid(commitKey)) { "会計キーが不正です" }
        if (items.isEmpty()) {
            require(state.allocations.isEmpty()) { "空の伝票へ支払を保存できません" }
            clear()
            return
        }
        require(PaymentDraftIntegrityV181.validAllocations(state.allocations, TaxEngine.calculate(items).grossAmount)) {
            "支払配分が伝票合計と一致しません"
        }
        val db = database.writableDatabase
        ensureSchema()
        db.beginTransaction()
        try {
            ExternalTenderLedgerV188.requireAbsent(db)
            // v1.86: save() must not bypass the v1.85 clear() protection by
            // replacing another interrupted transaction's persisted tender.
            val hasSavedAllocations = db.query(
                TABLE_ALLOCATIONS, arrayOf("sequence_no"),
                null, null, null, null, null, "1",
            ).use { it.moveToFirst() }
            var savedAllocationCountV188 = 0
            if (hasSavedAllocations) {
                PaymentDraftSequenceV191.requireContiguous(db)
                val savedIdentity = db.query(
                    TABLE_META, arrayOf("cart_fingerprint", "commit_key"),
                    "id = ?", arrayOf(SINGLETON_ID.toString()),
                    null, null, null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else (
                        cursor.getString(0) to
                            (if (cursor.isNull(1)) null else cursor.getString(1))
                    )
                }
                PaymentDraftSaveFenceV186.requireSameDraft(
                    hasSavedAllocations = true,
                    savedFingerprint = savedIdentity?.first,
                    savedCommitKey = savedIdentity?.second,
                    nextFingerprint = PaymentDraftFingerprint.of(items),
                    nextCommitKey = commitKey,
                )
                // Corrupt or orphaned tender evidence must not be overwritten either.
                val previousAllocations = db.query(
                    TABLE_ALLOCATIONS,
                    arrayOf("payment_method", "applied_amount", "received_amount"),
                    null, null, null, null, "sequence_no ASC",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                PaymentAllocation(
                                    PaymentMethod.valueOf(cursor.getString(0)),
                                    cursor.getLong(1),
                                    cursor.getLong(2),
                                ),
                            )
                        }
                    }
                }
                require(PaymentDraftIntegrityV181.validAllocations(
                    previousAllocations, TaxEngine.calculate(items).grossAmount,
                )) { "保存済みの支払記録を確認できません。再決済せず売上履歴を確認してください" }
                // A normal save may append a tender, never replace or remove one.
                // Cancellation must use cancelAllocation() so audit and mutation are atomic.
                PaymentTenderCancellationPolicyV187.requireAppendOnly(
                    previousAllocations, state.allocations,
                )
                savedAllocationCountV188 = previousAllocations.size
            }
            // No normal save path may introduce a new terminal payment: begin
            // an audited, durable external handoff and resolve it instead.
            ExternalTenderLedgerV188.requireNoDirectTerminalAppend(
                savedAllocationCountV188, state.allocations,
            )
            // Even with no saved allocations, a committed key must never be reused.
            require(SaleCommitIdempotencySchema.find(db, commitKey) == null) {
                "確定済みの会計キーは再利用できません。売上履歴を確認してください"
            }
            db.delete(TABLE_ALLOCATIONS, null, null)
            db.delete(TABLE_META, null, null)
            val updatedAt = System.currentTimeMillis()
            db.insertOrThrow(
                TABLE_META,
                null,
                ContentValues().apply {
                    put("id", SINGLETON_ID)
                    put("cart_fingerprint", PaymentDraftFingerprint.of(items))
                    put("commit_key", commitKey)
                    put("updated_at", updatedAt)
                },
            )
            state.allocations.forEachIndexed { index, allocation ->
                db.insertOrThrow(
                    TABLE_ALLOCATIONS,
                    null,
                    ContentValues().apply {
                        put("sequence_no", index + 1)
                        put("payment_method", allocation.method.name)
                        put("applied_amount", allocation.appliedAmount)
                        put("received_amount", allocation.receivedAmount)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Cancel exactly one persisted allocation and record an audit row in the
     * same SQLite transaction. Non-cash removal requires operator verification
     * of the external reversal; this app does not itself cancel a CAT payment.
     */
    fun cancelAllocation(
        items: List<CartItem>,
        expectedState: PaymentState,
        commitKey: String,
        allocationIndex: Int,
        operatorName: String,
        reversalVerified: Boolean,
    ): PaymentState {
        require(items.isNotEmpty()) { "会計中の伝票がありません" }
        require(PaymentCommitKey.isValid(commitKey)) { "会計キーが不正です" }
        require(operatorName.isNotBlank()) { "担当者を確認してください" }
        val fingerprint = PaymentDraftFingerprint.of(items)
        val total = TaxEngine.calculate(items).grossAmount
        val db = database.writableDatabase
        ensureSchema()
        db.beginTransaction()
        try {
            ExternalTenderLedgerV188.requireAbsent(db)
            val savedIdentity = db.query(
                TABLE_META, arrayOf("cart_fingerprint", "commit_key"),
                "id = ?", arrayOf(SINGLETON_ID.toString()),
                null, null, null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else (
                    cursor.getString(0) to
                        (if (cursor.isNull(1)) null else cursor.getString(1))
                )
            }
            require(savedIdentity?.first == fingerprint && savedIdentity?.second == commitKey) {
                "支払データが現在の伝票と一致しません。再決済せず履歴を確認してください"
            }
            require(SaleCommitIdempotencySchema.find(db, commitKey) == null) {
                "売上確定済みのため支払取消はできません。売上履歴を確認してください"
            }
            PaymentDraftSequenceV191.requireContiguous(db)
            val persisted = db.query(
                TABLE_ALLOCATIONS,
                arrayOf("payment_method", "applied_amount", "received_amount"),
                null, null, null, null, "sequence_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(PaymentAllocation(
                            PaymentMethod.valueOf(cursor.getString(0)),
                            cursor.getLong(1), cursor.getLong(2),
                        ))
                    }
                }
            }
            require(persisted == expectedState.allocations &&
                PaymentDraftIntegrityV181.validAllocations(persisted, total)
            ) { "保存済みの支払記録に変更があります。会計を確認してください" }
            require(allocationIndex in persisted.indices) { "取消する支払が見つかりません" }
            val removed = persisted[allocationIndex]
            PaymentTenderCancellationPolicyV187.requireReversalVerification(
                removed.method, reversalVerified,
            )
            val next = PaymentEngine.removeAt(expectedState, allocationIndex)

            // Insert audit first; if insertion or the following rewrite fails,
            // the whole transaction rolls back, retaining original tender.
            OperationAuditSchemaV136.ensure(db)
            db.insertOrThrow(
                "operation_audit", null,
                ContentValues().apply {
                    put("event_type", "PAYMENT_TENDER_CANCELLED")
                    put("reference_id", 0L) // draft has no sale ID yet
                    put(
                        "detail",
                        "commit_key=$commitKey; cart_fingerprint=$fingerprint; " +
                            "sequence_no=${allocationIndex + 1}; " +
                            "method=${removed.method.name}; " +
                            "applied_amount=${removed.appliedAmount}; " +
                            "received_amount=${removed.receivedAmount}; " +
                            "external_reversal_verified=$reversalVerified",
                    )
                    put("operator_name", operatorName.trim().take(100))
                    put("created_at", System.currentTimeMillis())
                },
            )
            db.delete(TABLE_ALLOCATIONS, null, null)
            next.allocations.forEachIndexed { index, allocation ->
                db.insertOrThrow(
                    TABLE_ALLOCATIONS, null,
                    ContentValues().apply {
                        put("sequence_no", index + 1)
                        put("payment_method", allocation.method.name)
                        put("applied_amount", allocation.appliedAmount)
                        put("received_amount", allocation.receivedAmount)
                    },
                )
            }
            check(db.update(
                TABLE_META,
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id = ? AND commit_key = ?",
                arrayOf(SINGLETON_ID.toString(), commitKey),
            ) == 1) { "支払記録の確定に失敗しました" }
            db.setTransactionSuccessful()
            return next
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Persist terminal work BEFORE the operator starts an external payment.
     * A crash leaves this record in place until the terminal outcome is checked.
     */
    fun beginExternalTender(
        items: List<CartItem>,
        expectedState: PaymentState,
        proposedState: PaymentState,
        commitKey: String,
        operatorName: String,
    ): ExternalTenderPendingV188 {
        require(items.isNotEmpty()) { "会計中の伝票がありません" }
        require(PaymentCommitKey.isValid(commitKey)) { "会計キーが不正です" }
        val fingerprint = PaymentDraftFingerprint.of(items)
        val total = TaxEngine.calculate(items).grossAmount
        val oldAllocations = expectedState.allocations
        require(proposedState.allocations.size == oldAllocations.size + 1 &&
            proposedState.allocations.take(oldAllocations.size) == oldAllocations
        ) { "外部決済の支払配分が一致しません" }
        val last = proposedState.allocations.last()
        require(ExternalTenderLedgerV188.requiresHandoff(last.method)) {
            "この支払方法は外部決済確認の対象外です"
        }
        require(PaymentDraftIntegrityV181.validAllocations(proposedState.allocations, total)) {
            "外部決済額が伝票合計を超えています"
        }
        val db = database.writableDatabase
        ensureSchema()
        db.beginTransaction()
        try {
            ExternalTenderLedgerV188.requireAbsent(db)
            val identity = db.query(
                TABLE_META, arrayOf("cart_fingerprint", "commit_key"),
                "id = ?", arrayOf(SINGLETON_ID.toString()), null, null, null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else (
                    cursor.getString(0) to
                        (if (cursor.isNull(1)) null else cursor.getString(1))
                )
            }
            require(identity?.first == fingerprint && identity?.second == commitKey) {
                ExternalTenderLedgerV188.REVIEW_MESSAGE
            }
            require(SaleCommitIdempotencySchema.find(db, commitKey) == null) {
                "売上確定済みの会計では外部決済を開始できません"
            }
            PaymentDraftSequenceV191.requireContiguous(db)
            val persisted = db.query(
                TABLE_ALLOCATIONS,
                arrayOf("payment_method", "applied_amount", "received_amount"),
                null, null, null, null, "sequence_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(PaymentAllocation(
                            PaymentMethod.valueOf(cursor.getString(0)),
                            cursor.getLong(1), cursor.getLong(2),
                        ))
                    }
                }
            }
            require(persisted == oldAllocations &&
                PaymentDraftIntegrityV181.validAllocations(persisted, total)
            ) { ExternalTenderLedgerV188.REVIEW_MESSAGE }
            val pending = ExternalTenderPendingV188(
                fingerprint, commitKey, last, System.currentTimeMillis(),
            )
            ExternalTenderLedgerV188.insert(db, pending)
            ExternalTenderLedgerV188.audit(
                db, "PAYMENT_EXTERNAL_HANDOFF_STARTED", pending, operatorName,
            )
            db.setTransactionSuccessful()
            return pending
        } finally {
            db.endTransaction()
        }
    }

    /**
     * These resolutions are the operator's manual attestation, never a CAT API
     * response. Both the allocation mutation and audit are atomic.
     */
    fun resolveExternalTender(
        items: List<CartItem>,
        expectedState: PaymentState,
        expectedPending: ExternalTenderPendingV188,
        commitKey: String,
        operatorName: String,
        resolution: ExternalTenderResolutionV188,
    ): PaymentState {
        require(PaymentCommitKey.isValid(commitKey)) { "会計キーが不正です" }
        val total = TaxEngine.calculate(items).grossAmount
        val fingerprint = PaymentDraftFingerprint.of(items)
        val db = database.writableDatabase
        ensureSchema()
        db.beginTransaction()
        try {
            val pending = ExternalTenderLedgerV188.read(db)
            require(pending == expectedPending && pending != null) {
                ExternalTenderLedgerV188.REVIEW_MESSAGE
            }
            val identity = db.query(
                TABLE_META, arrayOf("cart_fingerprint", "commit_key"),
                "id = ?", arrayOf(SINGLETON_ID.toString()), null, null, null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else (
                    cursor.getString(0) to
                        (if (cursor.isNull(1)) null else cursor.getString(1))
                )
            }
            require(identity?.first == fingerprint && identity?.second == commitKey &&
                ExternalTenderLedgerV188.matchesDraft(
                    pending, fingerprint, commitKey, expectedState.allocations, total,
                )
            ) { ExternalTenderLedgerV188.REVIEW_MESSAGE }
            require(SaleCommitIdempotencySchema.find(db, commitKey) == null) {
                "売上確定済みの会計は変更できません"
            }
            PaymentDraftSequenceV191.requireContiguous(db)
            val persisted = db.query(
                TABLE_ALLOCATIONS,
                arrayOf("payment_method", "applied_amount", "received_amount"),
                null, null, null, null, "sequence_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(PaymentAllocation(
                            PaymentMethod.valueOf(cursor.getString(0)),
                            cursor.getLong(1), cursor.getLong(2),
                        ))
                    }
                }
            }
            require(persisted == expectedState.allocations) {
                ExternalTenderLedgerV188.REVIEW_MESSAGE
            }
            val approved = resolution == ExternalTenderResolutionV188.APPROVED_BY_OPERATOR
            val next = if (approved) {
                expectedState.copy(allocations = persisted + pending.allocation)
            } else expectedState
            ExternalTenderLedgerV188.audit(
                db,
                if (approved) "PAYMENT_EXTERNAL_APPROVED_BY_OPERATOR"
                else "PAYMENT_EXTERNAL_NOT_EXECUTED_OR_REVERSED_BY_OPERATOR",
                pending,
                operatorName,
            )
            if (approved) {
                db.insertOrThrow(
                    TABLE_ALLOCATIONS, null, ContentValues().apply {
                        put("sequence_no", persisted.size + 1)
                        put("payment_method", pending.allocation.method.name)
                        put("applied_amount", pending.allocation.appliedAmount)
                        put("received_amount", pending.allocation.receivedAmount)
                    },
                )
            }
            ExternalTenderLedgerV188.remove(db, pending)
            check(db.update(
                TABLE_META,
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id = ? AND commit_key = ?",
                arrayOf(SINGLETON_ID.toString(), commitKey),
            ) == 1) { ExternalTenderLedgerV188.REVIEW_MESSAGE }
            db.setTransactionSuccessful()
            return next
        } finally {
            db.endTransaction()
        }
    }

    fun loadOrCreate(items: List<CartItem>): PaymentDraftLoadResult {
        require(items.isNotEmpty()) { "空の伝票では支払を開始できません" }
        val loaded = load(items)
        if (loaded.integrityIssue != null) return loaded
        if (PaymentCommitKey.isValid(loaded.commitKey)) return loaded
        val key = PaymentCommitKey.newKey()
        save(items, loaded.state, key)
        return loaded.copy(commitKey = key)
    }

    fun load(items: List<CartItem>): PaymentDraftLoadResult {
        ensureSchema()
        if (items.isEmpty()) {
            clear()
            return PaymentDraftLoadResult(PaymentState(), false, null, null)
        }
        val db = database.readableDatabase
        val metadata = db.query(
            TABLE_META,
            arrayOf("cart_fingerprint", "updated_at", "commit_key"),
            "id = ?",
            arrayOf(SINGLETON_ID.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Triple(
                    cursor.getString(0),
                    cursor.getLong(1),
                    if (cursor.isNull(2)) null else cursor.getString(2),
                )
            }
        } ?: run {
            // Metadata can be missing after an interrupted / incomplete migration.
            // The remaining allocation rows must never be silently discarded.
            val orphanedAllocations = runCatching {
                ExternalTenderLedgerV188.read(db) != null || db.query(
                    TABLE_ALLOCATIONS,
                    arrayOf("sequence_no"),
                    null, null, null, null, null, "1",
                ).use { it.moveToFirst() }
            }.getOrDefault(true)
            return if (orphanedAllocations) {
                PaymentDraftLoadResult(
                    PaymentState(), false, null, null,
                    PaymentDraftRecoveryPolicyV182.ORPHAN_REVIEW_MESSAGE,
                )
            } else {
                PaymentDraftLoadResult(PaymentState(), false, null, null)
            }
        }

        val allocations = runCatching {
            PaymentDraftSequenceV191.requireContiguous(db)
            db.query(
                TABLE_ALLOCATIONS,
                arrayOf("payment_method", "applied_amount", "received_amount"),
                null,
                null,
                null,
                null,
                "sequence_no ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PaymentAllocation(
                                method = PaymentMethod.valueOf(cursor.getString(0)),
                                appliedAmount = cursor.getLong(1),
                                receivedAmount = cursor.getLong(2),
                            ),
                        )
                    }
                }
            }
        }.getOrElse {
            return PaymentDraftLoadResult(
                PaymentState(), false, metadata.second, metadata.third,
                PaymentDraftIntegrityV181.REVIEW_MESSAGE,
            )
        }

        val pendingExternalV188 = runCatching {
            ExternalTenderLedgerV188.read(db)
        }.getOrElse {
            return PaymentDraftLoadResult(
                PaymentState(), false, metadata.second, metadata.third,
                ExternalTenderLedgerV188.REVIEW_MESSAGE,
            )
        }
        if (pendingExternalV188 != null && !ExternalTenderLedgerV188.matchesDraft(
                pendingExternalV188, metadata.first, metadata.third,
                allocations, TaxEngine.calculate(items).grossAmount,
            )
        ) {
            return PaymentDraftLoadResult(
                PaymentState(), false, metadata.second, metadata.third,
                ExternalTenderLedgerV188.REVIEW_MESSAGE,
            )
        }

        // On restart the committed sale may already have atomically cleared the cart,
        // while the separate payment-draft cleanup was interrupted. Verify the sale row
        // AND its idempotency key before releasing any persisted tender evidence.
        val confirmedSale = metadata.third
            ?.takeIf(PaymentCommitKey::isValid)
            ?.let { SaleCommitIdempotencySchema.findConfirmed(db, it) }
        if (confirmedSale != null) {
            if (pendingExternalV188 != null) {
                return PaymentDraftLoadResult(
                    PaymentState(), false, metadata.second, metadata.third,
                    ExternalTenderLedgerV188.REVIEW_MESSAGE,
                )
            }
            if (!PaymentDraftCommittedRecoveryV185.canRelease(
                    metadata.first, allocations, confirmedSale,
                ) || !SaleCommitIdempotencySchema.recordedPaymentsMatch(
                    db, confirmedSale.saleId, allocations,
                )
            ) {
                return PaymentDraftLoadResult(
                    PaymentState(), false, metadata.second, metadata.third,
                    PaymentDraftIntegrityV181.REVIEW_MESSAGE,
                )
            }
            clear()
            return PaymentDraftLoadResult(
                PaymentState(), false, null, null,
                recoveredConfirmedSaleId = confirmedSale.saleId,
            )
        }

        if (metadata.first != PaymentDraftFingerprint.of(items)) {
            // Do not silently erase a paid draft for a completely different cart either.
            // Every non-empty persisted allocation is evidence of a possibly processed tender.
            val matchingOldFormat =
                metadata.first == PaymentDraftFingerprint.legacyV180Of(items)
            if (pendingExternalV188 != null || PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(allocations.size)) {
                return PaymentDraftLoadResult(
                    PaymentState(), false, metadata.second, metadata.third,
                    if (matchingOldFormat) PaymentDraftIntegrityV181.REVIEW_MESSAGE
                    else PaymentDraftRecoveryPolicyV182.OTHER_CART_REVIEW_MESSAGE,
                )
            }
            // Only an empty draft is safe to retire without an operator investigation.
            clear()
            return PaymentDraftLoadResult(PaymentState(), false, null, null)
        }
        if (!PaymentDraftIntegrityV181.validAllocations(
                allocations, TaxEngine.calculate(items).grossAmount,
            ) || (allocations.isNotEmpty() && !PaymentCommitKey.isValid(metadata.third))
        ) {
            return PaymentDraftLoadResult(
                PaymentState(), false, metadata.second, metadata.third,
                PaymentDraftIntegrityV181.REVIEW_MESSAGE,
            )
        }

        return PaymentDraftLoadResult(
            state = PaymentState(allocations),
            restored = allocations.isNotEmpty() || pendingExternalV188 != null,
            updatedAt = metadata.second,
            commitKey = metadata.third,
            pendingExternalV188 = pendingExternalV188,
        )
    }

    fun clear() {
        ensureSchema()
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            ExternalTenderLedgerV188.requireAbsent(db)
            val hasAllocations = db.query(
                TABLE_ALLOCATIONS, arrayOf("sequence_no"),
                null, null, null, null, null, "1",
            ).use { it.moveToFirst() }
            if (hasAllocations) {
                // A call from cancel/hold/correction must never silently erase an
                // unconfirmed or externally processed tender, even after a restart.
                val identity = db.query(
                    TABLE_META, arrayOf("cart_fingerprint", "commit_key"),
                    "id = ?", arrayOf(SINGLETON_ID.toString()),
                    null, null, null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else (
                        cursor.getString(0) to
                            (if (cursor.isNull(1)) null else cursor.getString(1))
                    )
                }
                require(identity != null) { "未確認の支払履歴が残っています（伝票識別情報なし）" }
                PaymentDraftSequenceV191.requireContiguous(db)
                val persisted = db.query(
                    TABLE_ALLOCATIONS,
                    arrayOf("payment_method", "applied_amount", "received_amount"),
                    null, null, null, null, "sequence_no ASC",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                PaymentAllocation(
                                    method = PaymentMethod.valueOf(cursor.getString(0)),
                                    appliedAmount = cursor.getLong(1),
                                    receivedAmount = cursor.getLong(2),
                                ),
                            )
                        }
                    }
                }
                val confirmed = identity.second
                    ?.takeIf(PaymentCommitKey::isValid)
                    ?.let { SaleCommitIdempotencySchema.findConfirmed(db, it) }
                require(PaymentDraftCommittedRecoveryV185.canRelease(
                    identity.first, persisted, confirmed,
                ) && confirmed != null &&
                    SaleCommitIdempotencySchema.recordedPaymentsMatch(
                        db, confirmed.saleId, persisted,
                    )
                ) { "保存済み支払と売上支払内訳が一致しません。履歴・決済端末を確認してください" }
            }
            db.delete(TABLE_ALLOCATIONS, null, null)
            db.delete(TABLE_META, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureSchema() {
        val db = database.writableDatabase
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                id INTEGER PRIMARY KEY,
                cart_fingerprint TEXT NOT NULL,
                commit_key TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val metadataColumns = db.rawQuery("PRAGMA table_info($TABLE_META)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        if ("commit_key" !in metadataColumns) {
            db.execSQL("ALTER TABLE $TABLE_META ADD COLUMN commit_key TEXT")
        }
        ExternalTenderLedgerV188.ensure(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALLOCATIONS (
                sequence_no INTEGER PRIMARY KEY,
                payment_method TEXT NOT NULL,
                applied_amount INTEGER NOT NULL,
                received_amount INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    companion object {
        private const val SINGLETON_ID = 1L
        private const val TABLE_META = "payment_draft_meta"
        private const val TABLE_ALLOCATIONS = "payment_draft_allocations"
    }
}
