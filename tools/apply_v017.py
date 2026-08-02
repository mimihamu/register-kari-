from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / "app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt"
DOC = ROOT / "docs/V0.17_OPERATION_ATOMICITY.md"

text = STORE.read_text(encoding="utf-8")

reversal_start = text.index("    fun createFullReversal(")
reversal_end = text.index("\n    fun isSaleReversed(", reversal_start)
new_reversal = r'''    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        operatorName: String,
    ): Long {
        require(reason.isNotBlank()) { "理由を入力してください" }
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        val operationKey = OperationsIdempotencyPolicy.reversalKey(originalSaleId)

        return db.transaction {
            claimOperationKey(
                operationKey = operationKey,
                operationType = type.name,
                duplicateMessage = "この売上は既に返品または取消済みです",
                createdAt = now,
            )
            val alreadyReversed = rawQuery(
                "SELECT COUNT(*) FROM reversal_transactions WHERE original_sale_id = ?",
                arrayOf(originalSaleId.toString()),
            ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0 }
            check(!alreadyReversed) { "この売上は既に返品または取消済みです" }

            val sale = query(
                "sales",
                arrayOf("total_amount", "payment_method"),
                "id = ?",
                arrayOf(originalSaleId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getLong(0) to cursor.getString(1)
            } ?: throw IllegalArgumentException("元売上が見つかりません")

            val reversalId = insertOrThrow(
                "reversal_transactions",
                null,
                ContentValues().apply {
                    put("original_sale_id", originalSaleId)
                    put("reversal_type", type.name)
                    put("gross_amount", sale.first)
                    put("reason", reason.trim())
                    put("operator_name", operatorName.trim())
                    put("created_at", now)
                },
            )

            var paymentRows = 0
            query(
                "sale_payments",
                arrayOf("payment_method", "applied_amount"),
                "sale_id = ?",
                arrayOf(originalSaleId.toString()),
                null,
                null,
                "sequence_no ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    insertOrThrow(
                        "reversal_payments",
                        null,
                        ContentValues().apply {
                            put("reversal_id", reversalId)
                            put("payment_method", cursor.getString(0))
                            put("amount", cursor.getLong(1))
                        },
                    )
                    paymentRows++
                }
            }
            if (paymentRows == 0) {
                val fallbackMethod = if (sale.second.contains("現金")) PaymentMethod.CASH.name else "OTHER"
                insertOrThrow(
                    "reversal_payments",
                    null,
                    ContentValues().apply {
                        put("reversal_id", reversalId)
                        put("payment_method", fallbackMethod)
                        put("amount", sale.first)
                    },
                )
            }

            insertAudit(
                eventType = type.name,
                referenceId = reversalId,
                detail = "元売上 No.$originalSaleId / ${sale.first}円 / ${reason.trim()}",
                operatorName = operatorName,
                createdAt = now,
            )
            bindOperationKey(operationKey, reversalId)
            reversalId
        }
    }
'''
text = text[:reversal_start] + new_reversal + text[reversal_end:]

settlement_start = text.index("    fun recordSettlement(")
settlement_end = text.index("\n    fun recentSettlements(", settlement_start)
new_settlement = r'''    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        operatorName: String,
        date: LocalDate = LocalDate.now(),
    ): Long {
        require(operatorName.isNotBlank()) { "担当者を入力してください" }
        val now = System.currentTimeMillis()
        val operationKey = OperationsIdempotencyPolicy.settlementKey(type, date)

        return db.transaction {
            if (operationKey != null) {
                claimOperationKey(
                    operationKey = operationKey,
                    operationType = type.name,
                    duplicateMessage = "この営業日は既にZ精算済みです",
                    createdAt = now,
                )
            }
            val summary = dailySummary(date)
            if (type == SettlementReportType.Z_SETTLEMENT && summary.settled) {
                throw IllegalStateException("この営業日は既にZ精算済みです")
            }
            val actual = actualCash ?: summary.expectedCash
            require(actual >= 0) { "現金実査額は0円以上で入力してください" }
            val variance = OperationsMath.variance(actual, summary.expectedCash)

            val id = insertOrThrow(
                "settlement_reports",
                null,
                ContentValues().apply {
                    put("business_date", summary.businessDate)
                    put("report_type", type.name)
                    put("sales_gross", summary.salesGross)
                    put("reversal_gross", summary.reversalGross)
                    put("net_sales", summary.netSales)
                    put("expected_cash", summary.expectedCash)
                    put("actual_cash", actual)
                    put("variance", variance)
                    put("transaction_count", summary.transactionCount)
                    put("reversal_count", summary.reversalCount)
                    put("pending_prints", summary.pendingPrints)
                    put("held_tickets", summary.heldTickets)
                    put("operator_name", operatorName.trim())
                    put("created_at", now)
                },
            )
            insertAudit(
                eventType = type.name,
                referenceId = id,
                detail = "営業日 ${summary.businessDate} / 純売上 ${summary.netSales}円 / 現金差異 ${variance}円",
                operatorName = operatorName,
                createdAt = now,
            )
            if (operationKey != null) bindOperationKey(operationKey, id)
            id
        }
    }
'''
text = text[:settlement_start] + new_settlement + text[settlement_end:]

schema_marker = '''        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_audit ('''
operation_key_schema = r'''        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_commit_keys (
                operation_key TEXT PRIMARY KEY,
                operation_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
'''
if schema_marker not in text:
    raise RuntimeError("operation_audit schema marker not found")
text = text.replace(schema_marker, operation_key_schema + schema_marker, 1)

helper_marker = "    private fun SQLiteDatabase.insertAudit(\n"
operation_key_helpers = r'''    private fun SQLiteDatabase.claimOperationKey(
        operationKey: String,
        operationType: String,
        duplicateMessage: String,
        createdAt: Long,
    ) {
        val inserted = insertWithOnConflict(
            "operation_commit_keys",
            null,
            ContentValues().apply {
                put("operation_key", operationKey)
                put("operation_type", operationType)
                put("reference_id", 0L)
                put("created_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L) throw IllegalStateException(duplicateMessage)
    }

    private fun SQLiteDatabase.bindOperationKey(operationKey: String, referenceId: Long) {
        val updated = update(
            "operation_commit_keys",
            ContentValues().apply { put("reference_id", referenceId) },
            "operation_key = ?",
            arrayOf(operationKey),
        )
        check(updated == 1) { "操作キーの確定に失敗しました" }
    }

'''
if helper_marker not in text:
    raise RuntimeError("audit helper marker not found")
text = text.replace(helper_marker, operation_key_helpers + helper_marker, 1)

if "require(!isSaleReversed(originalSaleId))" in text:
    raise RuntimeError("reversal precheck remains outside transaction")
if "val summary = dailySummary(date)\n        if (type == SettlementReportType.Z_SETTLEMENT" in text:
    raise RuntimeError("settlement summary remains outside transaction")

STORE.write_text(text, encoding="utf-8")

DOC.write_text(r'''# v0.17 返品・取消・精算の二重実行防止と原子性強化

## 返品・取消

- 元売上の存在確認、既返品判定、反対取引、反対支払、監査記録、永続操作キー確定を単一SQLiteトランザクションで処理する。
- 操作キーは `REVERSAL:<元売上ID>` とし、同じ売上への再実行・競合を拒否する。
- 過去バージョンで作成済みの反対取引も、トランザクション内の既存判定で拒否する。

## 点検・精算

- Z精算は `Z_SETTLEMENT:<営業日>` を永続操作キーとする。
- 売上集計、Z精算済み判定、精算記録、監査記録、操作キー確定を単一SQLiteトランザクションで処理する。
- X点検は永続操作キーを持たず、繰り返し実行可能な仕様を維持する。

## 連打防止

- `OperationExecutionGuard` により、同一プロセス内の同一返品・取消、同一営業日の点検・精算の多重実行を拒否する。
- 例外発生時はガードを必ず解放し、修正後の再試行を可能にする。

## 実機確認

- 返品・取消ボタンを連打しても反対取引が1件だけ作成されること。
- 2画面・2操作から同じ売上を返品しても1件だけ成立すること。
- Z精算を連打・再実行しても同一営業日に1件だけ作成されること。
- X点検は連続実行後も複数履歴が残ること。
- 精算中に売上を同時確定する運用は避け、実端末で営業締め手順を確認すること。
''', encoding="utf-8")

print("v0.17 operation atomicity patch applied")
