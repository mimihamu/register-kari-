package jp.co.tenposinfo.register

import android.content.Context
import android.database.sqlite.SQLiteDatabase

internal enum class SaleReversalStateV135(
    val displayLabel: String,
    val blocksFurtherReversal: Boolean,
) {
    ACTIVE("", false),
    PARTIAL_RETURN("一部返品", false),
    RETURNED("返品済", true),
    VOIDED("取消済（VOIDED）", true),
}

internal data class SaleReversalReferenceV135(
    val reversalId: Long,
    val type: ReversalType,
    val grossAmount: Long,
    val businessDate: String?,
    val createdAt: Long,
    val hasItems: Boolean,
)

internal data class SaleReversalTraceV135(
    val state: SaleReversalStateV135,
    val references: List<SaleReversalReferenceV135>,
) {
    val hasReversal: Boolean get() = state != SaleReversalStateV135.ACTIVE
    val blocksFurtherReversal: Boolean get() = state.blocksFurtherReversal

    companion object {
        val ACTIVE = SaleReversalTraceV135(SaleReversalStateV135.ACTIVE, emptyList())
    }
}

internal object SaleReversalTracePolicyV135 {
    fun resolve(
        originalQuantity: Int,
        returnedQuantity: Int,
        references: List<SaleReversalReferenceV135>,
    ): SaleReversalTraceV135 {
        if (references.isEmpty()) return SaleReversalTraceV135.ACTIVE

        val state = when {
            references.any { it.type == ReversalType.CANCEL } -> SaleReversalStateV135.VOIDED
            references.any { !it.hasItems } -> SaleReversalStateV135.RETURNED
            originalQuantity > 0 && returnedQuantity >= originalQuantity -> SaleReversalStateV135.RETURNED
            else -> SaleReversalStateV135.PARTIAL_RETURN
        }
        return SaleReversalTraceV135(state, references)
    }
}

/**
 * COR-007: normal sales lookup read model for original-sale <-> reversal cross references.
 *
 * This store is read-only. It never updates the original sale or reversal rows.
 * Missing legacy reversal tables are treated as "no trace" so the sales lookup itself
 * remains usable on old/partially migrated databases.
 */
internal class SaleReversalTraceReadStoreV135(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase get() = database.readableDatabase

    fun load(saleIds: Collection<Long>): Map<Long, SaleReversalTraceV135> {
        val ids = saleIds.filter { it > 0L }.distinct()
        if (ids.isEmpty() || !hasTable("reversal_transactions")) return emptyMap()

        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map(Long::toString).toTypedArray()
        val hasReversalItems = hasTable("reversal_items")
        val businessDateExpression = if (
            SchemaMigration.hasColumn(db, "reversal_transactions", "business_date")
        ) {
            "rt.business_date"
        } else {
            "NULL"
        }

        val referencesBySale = linkedMapOf<Long, MutableList<SaleReversalReferenceV135>>()
        val hasItemsExpression = if (hasReversalItems) {
            "CASE WHEN EXISTS (SELECT 1 FROM reversal_items ri WHERE ri.reversal_id = rt.id) THEN 1 ELSE 0 END"
        } else {
            "0"
        }
        db.rawQuery(
            """
            SELECT rt.id, rt.original_sale_id, rt.reversal_type, rt.gross_amount,
                   $businessDateExpression AS business_date, rt.created_at,
                   $hasItemsExpression AS has_items
            FROM reversal_transactions rt
            WHERE rt.original_sale_id IN ($placeholders)
            ORDER BY rt.created_at ASC, rt.id ASC
            """.trimIndent(),
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = runCatching { ReversalType.valueOf(cursor.getString(2)) }.getOrNull()
                    ?: continue
                val saleId = cursor.getLong(1)
                referencesBySale.getOrPut(saleId) { mutableListOf() } += SaleReversalReferenceV135(
                    reversalId = cursor.getLong(0),
                    type = type,
                    grossAmount = cursor.getLong(3),
                    businessDate = if (cursor.isNull(4)) null else cursor.getString(4),
                    createdAt = cursor.getLong(5),
                    hasItems = cursor.getInt(6) != 0,
                )
            }
        }

        if (referencesBySale.isEmpty()) return emptyMap()

        val quantitiesBySale = if (hasReversalItems && hasTable("sale_items")) {
            loadQuantities(ids, placeholders, args)
        } else {
            emptyMap()
        }

        return referencesBySale.mapValues { (saleId, references) ->
            val quantities = quantitiesBySale[saleId]
            SaleReversalTracePolicyV135.resolve(
                originalQuantity = quantities?.first ?: 0,
                returnedQuantity = quantities?.second ?: 0,
                references = references,
            )
        }
    }

    fun loadOne(saleId: Long): SaleReversalTraceV135 =
        load(listOf(saleId))[saleId] ?: SaleReversalTraceV135.ACTIVE

    override fun close() = database.close()

    private fun loadQuantities(
        ids: List<Long>,
        placeholders: String,
        args: Array<String>,
    ): Map<Long, Pair<Int, Int>> {
        if (ids.isEmpty()) return emptyMap()
        val result = linkedMapOf<Long, Pair<Int, Int>>()
        db.rawQuery(
            """
            SELECT si.sale_id,
                   COALESCE(SUM(si.quantity), 0) AS original_quantity,
                   COALESCE(SUM(COALESCE(returned.return_quantity, 0)), 0) AS returned_quantity
            FROM sale_items si
            LEFT JOIN (
                SELECT sale_item_id, SUM(return_quantity) AS return_quantity
                FROM reversal_items
                GROUP BY sale_item_id
            ) returned ON returned.sale_item_id = si.id
            WHERE si.sale_id IN ($placeholders)
            GROUP BY si.sale_id
            """.trimIndent(),
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getLong(0)] = cursor.getInt(1) to cursor.getInt(2)
            }
        }
        return result
    }

    private fun hasTable(name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }
}
