package jp.co.tenposinfo.register

internal data class HeldTicketSplitUiValidation(
    val movedQuantities: Map<Int, Int>,
    val movedCount: Int,
    val remainingCount: Int,
    val canConfirm: Boolean,
    val message: String?,
)

internal object HeldTicketOperationsUiPolicy {
    fun validateSplit(
        items: List<CartItem>,
        rawQuantities: List<String>,
        rawName: String,
    ): HeldTicketSplitUiValidation {
        val totalCount = items.sumOf { it.quantity }
        if (items.isEmpty()) {
            return invalid(emptyMap(), 0, 0, "分割元の伝票に明細がありません")
        }
        if (rawName.trim().isEmpty()) {
            return invalid(emptyMap(), 0, totalCount, "新しい伝票名を入力してください")
        }

        val parsed = mutableListOf<Int>()
        items.forEachIndexed { index, item ->
            val raw = rawQuantities.getOrNull(index)?.trim().orEmpty()
            val quantity = if (raw.isEmpty()) 0 else raw.toIntOrNull()
            if (quantity == null || quantity < 0) {
                return invalid(emptyMap(), 0, totalCount, "移動数量は0以上の数字で入力してください")
            }
            if (quantity > item.quantity) {
                return invalid(emptyMap(), 0, totalCount, "${item.product.name}の移動数量が元数量を超えています")
            }
            parsed += quantity
        }

        val movedCountLong = parsed.sumOf { it.toLong() }
        if (movedCountLong == 0L) {
            return invalid(emptyMap(), 0, totalCount, "移動する商品数量を入力してください")
        }
        if (movedCountLong >= totalCount.toLong()) {
            return invalid(emptyMap(), movedCountLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 0, "元伝票を空にはできません。1点以上残してください")
        }
        val movedCount = movedCountLong.toInt()
        return HeldTicketSplitUiValidation(
            movedQuantities = parsed.mapIndexedNotNull { index, quantity ->
                quantity.takeIf { it > 0 }?.let { index to it }
            }.toMap(),
            movedCount = movedCount,
            remainingCount = totalCount - movedCount,
            canConfirm = true,
            message = "${movedCount}点を新しい伝票へ分割します",
        )
    }

    private fun invalid(
        movedQuantities: Map<Int, Int>,
        movedCount: Int,
        remainingCount: Int,
        message: String,
    ) = HeldTicketSplitUiValidation(
        movedQuantities = movedQuantities,
        movedCount = movedCount,
        remainingCount = remainingCount,
        canConfirm = false,
        message = message,
    )
}
