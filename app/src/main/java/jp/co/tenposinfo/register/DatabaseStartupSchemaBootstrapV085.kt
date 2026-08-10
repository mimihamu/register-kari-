package jp.co.tenposinfo.register

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * v0.85: 復元・上書き更新後、画面を表示する前に主要な後付けスキーマを整備する。
 *
 * RegisterDatabase の user_version に依存せず毎起動で冪等に実行するため、
 * DATABASE_VERSION=4 の既存DBでも、各機能を初めて開いた瞬間まで CREATE/ALTER を遅延させない。
 * 業務データの DELETE / DROP / UPDATE は行わず、既存各Storeの ensureSchema 系処理だけを利用する。
 */
internal object DatabaseStartupSchemaBootstrapV085 {
    fun ensureBeforeUi(context: Context) {
        val appContext = context.applicationContext

        // 基本DBを最初に開き、v0.84以前のSQLiteOpenHelperマイグレーションが必要なら先に完了させる。
        openAndCloseBaseDatabase(appContext)

        // 営業日、入出金、返品取消、点検精算、監査、帳票印刷キュー等。
        OperationsStore(appContext).close()

        // 領収書の発行・再発行台帳。
        ReceiptVoucherStore(appContext).close()

        // 通常レシート再印字の追記専用監査台帳。
        SaleReceiptReprintAuditStore(appContext).close()

        // 会計確定の冪等性テーブルも販売開始前に準備する。
        val verificationDatabase = RegisterDatabase(appContext)
        try {
            val db = verificationDatabase.writableDatabase
            SaleCommitIdempotencySchema.ensure(db)
            BusinessSessionSchema.ensure(db)
            SettlementSnapshotSchemaV027.ensure(db)
            DocumentPrintSafetySchema.ensure(db)
            verifyCoreSchema(db)
        } finally {
            verificationDatabase.close()
        }
    }

    private fun openAndCloseBaseDatabase(context: Context) {
        val database = RegisterDatabase(context)
        try {
            database.writableDatabase
        } finally {
            database.close()
        }
    }

    private fun verifyCoreSchema(db: SQLiteDatabase) {
        REQUIRED_TABLES.forEach { table ->
            check(tableExists(db, table)) {
                "起動前DBスキーマ確認に失敗しました: 必須テーブルがありません ($table)"
            }
        }
        REQUIRED_COLUMNS.forEach { (table, columns) ->
            columns.forEach { column ->
                check(hasColumn(db, table, column)) {
                    "起動前DBスキーマ確認に失敗しました: 必須列がありません ($table.$column)"
                }
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }

    private val REQUIRED_TABLES = setOf(
        "products",
        "cart_items",
        "held_tickets",
        "held_ticket_items",
        "sales",
        "sale_items",
        "sale_payments",
        "print_jobs",
        "business_sessions",
        "cash_movements",
        "reversal_transactions",
        "reversal_payments",
        "reversal_items",
        "document_print_jobs",
        "settlement_reports",
        "operation_commit_keys",
        "operation_audit",
        "receipt_voucher_batches",
        "receipt_voucher_issuances",
        "receipt_voucher_reprints",
        "sale_receipt_reprint_requests",
    )

    private val REQUIRED_COLUMNS = mapOf(
        "cart_items" to setOf("line_no", "discount_amount", "note"),
        "sales" to setOf("print_count", "business_session_id", "business_date"),
        "sale_items" to setOf("discount_amount", "note"),
        "held_ticket_items" to setOf("discount_amount", "note"),
        "reversal_items" to setOf(
            "tax_key",
            "tax_label",
            "tax_rate_percent",
            "tax_included",
            "taxable",
            "reduced",
            "tax_symbol",
        ),
        "settlement_reports" to setOf("opening_cash", "cash_in", "cash_out", "snapshot_version"),
    )
}