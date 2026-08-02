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
 * 保留伝票呼出時のデータ消失を防ぐ。
 * 1. 現在の作業中伝票を先に保留保存
 * 2. 呼出対象を作業中カートへ保存
 * 3. 保存成功後にだけ呼出元を削除
 *
 * 全処理を単一DBトランザクションにできない旧構造でも、障害時は重複が残るだけで
 * 作業中伝票または呼出伝票の両方を失わない順序を採用する。
 */
internal class HeldTicketSafetyCoordinator(
    private val database: RegisterDatabase,
) {
    fun loadSafely(
        ticket: HeldTicket,
        currentCart: List<CartItem>,
        operatorName: String,
    ): HeldTicketLoadResult {
        val selected = database.loadHeldTicket(ticket.id)
        require(selected.isNotEmpty()) { "呼出対象の伝票が空か、既に削除されています" }

        var parkedId: Long? = null
        if (currentCart.isNotEmpty()) {
            val parkedName = HeldTicketSafetyPolicy.parkedName(operatorName)
            parkedId = database.holdCart(parkedName, operatorName, currentCart)
        }

        database.saveCart(selected)
        database.deleteHeldTicket(ticket.id)
        return HeldTicketLoadResult(
            loadedItems = selected,
            parkedTicketId = parkedId,
            message = if (parkedId == null) {
                "${ticket.name}を呼び出しました"
            } else {
                "作業中伝票を退避して${ticket.name}を呼び出しました"
            },
        )
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
