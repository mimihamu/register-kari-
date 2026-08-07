package jp.co.tenposinfo.register

import android.content.ContentValues
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean

internal data class HeldTicketLoadResult(
    val loadedItems: List<CartItem>,
    val parkedTicketId: Long?,
    val message: String,
)

internal data class HeldTicketMergeResult(
    val targetTicketId: Long,
    val itemCount: Int,
    val message: String,
)

internal data class HeldTicketSplitResult(
    val sourceTicketId: Long,
    val newTicketId: Long,
    val movedItemCount: Int,
    val message: String,
)

internal data class HeldTicketSplitPlan(
    val remainingItems: List<CartItem>,
    val movedItems: List<CartItem>,
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

    fun splitName(sourceName: String, existingNames: Collection<String>): String {
        val used = existingNames.map { it.trim() }.toSet()
        val base = normalizeName("$sourceName-分割", "分割伝票")
        if (base !in used) return base
        var number = 2
        while (true) {
            val suffix = "-$number"
            val candidate = normalizeName(
                base.take((MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)) + suffix,
                "分割伝票$number",
            )
            if (candidate !in used) return candidate
            number++
        }
    }

    fun parkedName(operatorName: String, timestampMillis: Long = System.currentTimeMillis()): String {
        val suffix = java.text.SimpleDateFormat("HHmmss", java.util.Locale.JAPAN)
            .format(java.util.Date(timestampMillis))
        return normalizeName("作業中退避-$operatorName-$suffix", "作業中退避-$suffix")
    }
}

internal object HeldTicketMergeSplitPolicy {
    fun mergeItems(targetItems: List<CartItem>, sourceItems: List<CartItem>): List<CartItem> {
        require(targetItems.isNotEmpty()) { "結合先の伝票が空です" }
        require(sourceItems.isNotEmpty()) { "結合元の伝票が空です" }
        return targetItems + sourceItems
    }

    fun splitItems(items: List<CartItem>, movedQuantities: Map<Int, Int>): HeldTicketSplitPlan {
        require(items.isNotEmpty()) { "分割元の伝票が空です" }
        val remaining = mutableListOf<CartItem>()
        val moved = mutableListOf<CartItem>()

        items.forEachIndexed { index, item ->
            val movedQuantity = movedQuantities[index] ?: 0
            require(movedQuantity in 0..item.quantity) { "分割数量が明細数量を超えています" }
            when {
                movedQuantity == 0 -> remaining += item
                movedQuantity == item.quantity -> moved += item
                else -> {
                    val movedDiscount = proportionalDiscount(
                        totalDiscount = item.discountAmount,
                        partQuantity = movedQuantity,
                        wholeQuantity = item.quantity,
                    )
                    moved += item.copy(
                        quantity = movedQuantity,
                        discountAmount = movedDiscount,
                    )
                    remaining += item.copy(
                        quantity = item.quantity - movedQuantity,
                        discountAmount = item.discountAmount - movedDiscount,
                    )
                }
            }
        }

        require(moved.isNotEmpty()) { "分割する商品を1点以上指定してください" }
        require(remaining.isNotEmpty()) { "全商品を移動する場合は伝票の名称変更を使用してください" }
        return HeldTicketSplitPlan(
            remainingItems = remaining,
            movedItems = moved,
        )
    }

    private fun proportionalDiscount(
        totalDiscount: Long,
        partQuantity: Int,
        wholeQuantity: Int,
    ): Long {
        if (totalDiscount == 0L) return 0L
        return BigInteger.valueOf(totalDiscount)
            .multiply(BigInteger.valueOf(partQuantity.toLong()))
            .divide(BigInteger.valueOf(wholeQuantity.toLong()))
            .longValueExact()
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

    fun merge(
        sourceTicket: HeldTicket,
        targetTicket: HeldTicket,
    ): HeldTicketMergeResult {
        require(sourceTicket.id != targetTicket.id) { "同じ伝票同士は結合できません" }
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            val sourceItems = database.loadHeldTicket(sourceTicket.id)
            val targetItems = database.loadHeldTicket(targetTicket.id)
            val mergedItems = HeldTicketMergeSplitPolicy.mergeItems(targetItems, sourceItems)

            db.delete("held_ticket_items", "ticket_id = ?", arrayOf(targetTicket.id.toString()))
            mergedItems.forEach { item ->
                db.insertOrThrow(
                    "held_ticket_items",
                    null,
                    item.toDatabaseValues().apply { put("ticket_id", targetTicket.id) },
                )
            }
            LineTaxSnapshotStore.save(
                db,
                LineTaxSnapshotStore.SCOPE_HELD,
                targetTicket.id,
                mergedItems,
            )

            val deleted = db.delete("held_tickets", "id = ?", arrayOf(sourceTicket.id.toString()))
            require(deleted == 1) { "結合元の伝票を確定できませんでした" }
            db.delete(
                "line_tax_snapshots",
                "scope = ? AND owner_id = ?",
                arrayOf(LineTaxSnapshotStore.SCOPE_HELD, sourceTicket.id.toString()),
            )

            db.setTransactionSuccessful()
            HeldTicketMergeResult(
                targetTicketId = targetTicket.id,
                itemCount = mergedItems.sumOf { it.quantity },
                message = "${sourceTicket.name}を${targetTicket.name}へ結合しました",
            )
        } finally {
            db.endTransaction()
        }
    }

    fun split(
        ticket: HeldTicket,
        movedQuantities: Map<Int, Int>,
        newTicketName: String,
        operatorName: String,
    ): HeldTicketSplitResult {
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            val originalItems = database.loadHeldTicket(ticket.id)
            require(originalItems.isNotEmpty()) { "分割元の伝票が空か、既に削除されています" }
            val plan = HeldTicketMergeSplitPolicy.splitItems(originalItems, movedQuantities)
            val normalizedName = HeldTicketSafetyPolicy.normalizeName(newTicketName, "${ticket.name}-分割")

            db.delete("held_ticket_items", "ticket_id = ?", arrayOf(ticket.id.toString()))
            plan.remainingItems.forEach { item ->
                db.insertOrThrow(
                    "held_ticket_items",
                    null,
                    item.toDatabaseValues().apply { put("ticket_id", ticket.id) },
                )
            }
            LineTaxSnapshotStore.save(
                db,
                LineTaxSnapshotStore.SCOPE_HELD,
                ticket.id,
                plan.remainingItems,
            )

            val newTicketId = insertHeldTicket(
                name = normalizedName,
                operatorName = operatorName,
                items = plan.movedItems,
            )

            db.setTransactionSuccessful()
            HeldTicketSplitResult(
                sourceTicketId = ticket.id,
                newTicketId = newTicketId,
                movedItemCount = plan.movedItems.sumOf { it.quantity },
                message = "${ticket.name}から${plan.movedItems.sumOf { it.quantity }}点を$normalizedNameへ分割しました",
            )
        } finally {
            db.endTransaction()
        }
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
