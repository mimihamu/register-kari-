package jp.co.tenposinfo.register

import android.content.ContentValues
import java.util.concurrent.atomic.AtomicBoolean

internal data class HeldTicketLoadResult(
    val loadedItems: List<CartItem>,
    val parkedTicketId: Long?,
    val message: String,
)

internal object HeldTicketSafetyPolicy {
    const val MAX_NAME_LENGTH = 40

    fun normalizeName(raw: String, fallback: String): String = raw
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(MAX_NAME_LENGTH)
        .ifEmpty { fallback.take(MAX_NAME_LENGTH) }

    fun defaultName(existingNames: Collection<String>, prefix: String = "伝票"): String {
        val used = existingNames.map { it.trim() }.toSet()
        var number = 1
        while ("$prefix$number" in used) number++
        return "$prefix$number"
    }

    fun parkedName(operatorName: String, timestampMillis: Long = System.currentTimeMillis()): String {
        val suffix = java.text.SimpleDateFormat("HHmmss", java.util.Locale.JAPAN)
            .format(java.util.Date(timestampMillis))
        return normalizeName("作業中退避-$operatorName-$suffix", "作業中退避-$suffix")
    }
}

/**
 * 作業中伝票の退避、保留伝票の呼出、呼出元削除を同一SQLiteトランザクションで行う。
 * 途中で例外・電源断・プロセス停止が発生した場合は、全変更をロールバックする。
 */
internal class HeldTicketSafetyCoordinator(
    private val database: RegisterDatabase,
) {
    fun loadSafely(
        ticket: HeldTicket,
        currentCart: List<CartItem>,
        operatorName: String,
    ): HeldTicketLoadResult {
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            val selected = database.loadHeldTicket(ticket.id)
            require(selected.isNotEmpty()) { "呼出対象の伝票が空か、既に削除されています" }

            val parkedId = if (currentCart.isNotEmpty()) {
                insertHeldTicket(
                    name = HeldTicketSafetyPolicy.parkedName(operatorName),
                    operatorName = operatorName,
                    items = currentCart,
                )
            } else {
                null
            }

            db.delete("cart_items", null, null)
            selected.forEachIndexed { index, item ->
                db.insertOrThrow(
                    "cart_items",
                    null,
                    item.toDatabaseValues().apply { put("line_no", index + 1) },
                )
            }
            LineTaxSnapshotStore.save(db, LineTaxSnapshotStore.SCOPE_CART, 0L, selected)

            val deleted = db.delete("held_tickets", "id = ?", arrayOf(ticket.id.toString()))
            require(deleted == 1) { "呼出対象の伝票を確定できませんでした" }
            db.delete(
                "line_tax_snapshots",
                "scope = ? AND owner_id = ?",
                arrayOf(LineTaxSnapshotStore.SCOPE_HELD, ticket.id.toString()),
            )

            db.setTransactionSuccessful()
            HeldTicketLoadResult(
                loadedItems = selected,
                parkedTicketId = parkedId,
                message = if (parkedId == null) {
                    "${ticket.name}を呼び出しました"
                } else {
                    "作業中伝票を退避して${ticket.name}を呼び出しました"
                },
            )
        } finally {
            db.endTransaction()
        }
    }

    fun rename(ticketId: Long, rawName: String, fallback: String): Boolean {
        val normalized = HeldTicketSafetyPolicy.normalizeName(rawName, fallback)
        val updated = database.writableDatabase.update(
            "held_tickets",
            ContentValues().apply { put("name", normalized) },
            "id = ?",
            arrayOf(ticketId.toString()),
        )
        return updated == 1
    }

    private fun insertHeldTicket(
        name: String,
        operatorName: String,
        items: List<CartItem>,
    ): Long {
        val db = database.writableDatabase
        val ticketId = db.insertOrThrow(
            "held_tickets",
            null,
            ContentValues().apply {
                put("name", name)
                put("operator_name", operatorName)
                put("created_at", System.currentTimeMillis())
            },
        )
        items.forEach { item ->
            db.insertOrThrow(
                "held_ticket_items",
                null,
                item.toDatabaseValues().apply { put("ticket_id", ticketId) },
            )
        }
        LineTaxSnapshotStore.save(db, LineTaxSnapshotStore.SCOPE_HELD, ticketId, items)
        return ticketId
    }

    private fun CartItem.toDatabaseValues(): ContentValues = ContentValues().apply {
        put("product_id", product.id)
        put("product_name", product.name)
        put("unit_price", unitPrice)
        put("tax_category", product.taxCategory.name)
        put("display_order", product.displayOrder)
        put("quantity", quantity)
        put("discount_amount", discountAmount)
        put("note", note)
    }
}

/**
 * 会計確定ボタンの連打・複数コールバックによる二重売上を同一プロセス内で防止する。
 * DB確定成功後はロックを維持し、新規会計開始時にresetする。
 */
internal class SaleCommitGuard {
    private val inFlightOrCommitted = AtomicBoolean(false)

    fun tryBegin(): Boolean = inFlightOrCommitted.compareAndSet(false, true)

    fun releaseAfterFailure() {
        inFlightOrCommitted.set(false)
    }

    fun resetForNewPayment() {
        inFlightOrCommitted.set(false)
    }

    fun isLocked(): Boolean = inFlightOrCommitted.get()
}
