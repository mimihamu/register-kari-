package jp.co.tenposinfo.register

import org.json.JSONArray
import org.json.JSONObject

internal const val CUSTOMER_DISPLAY_SCHEMA_VERSION = 1
internal const val CUSTOMER_DISPLAY_PATH = "/customer-display/v1"

enum class CustomerDisplayMode {
    STANDBY,
    SALES,
    SUBTOTAL,
    ACCOUNTING,
    COMPLETE,
    DISCONNECTED,
}

data class CustomerDisplayOrderItem(
    val productId: String,
    val name: String,
    val quantity: Int,
    val unitPrice: Long,
    val amount: Long,
    val latest: Boolean = false,
    val cancelled: Boolean = false,
    val taxSymbol: String = "",
)

data class CustomerDisplaySnapshot(
    val schemaVersion: Int = CUSTOMER_DISPLAY_SCHEMA_VERSION,
    val sequence: Long = 0L,
    val serverInstanceId: String? = null,
    val sentAtMillis: Long = 0L,
    val mode: CustomerDisplayMode,
    val transactionId: String? = null,
    val storeName: String,
    val numberOfProducts: Int = 0,
    val subtotalAmount: Long = 0L,
    val totalAmount: Long = 0L,
    val paymentMethod: String? = null,
    val receivedAmount: Long = 0L,
    val shortageAmount: Long = 0L,
    val changeAmount: Long = 0L,
    val message: String? = null,
    val orderItems: List<CustomerDisplayOrderItem> = emptyList(),
    val presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
) {
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("sequence", sequence)
        put("serverInstanceId", serverInstanceId ?: JSONObject.NULL)
        put("sentAtMillis", sentAtMillis)
        put("mode", mode.name)
        put("transactionId", transactionId ?: JSONObject.NULL)
        put("storeName", storeName)
        put("numberOfProducts", numberOfProducts)
        put("subtotalAmount", subtotalAmount)
        put("totalAmount", totalAmount)
        put("paymentMethod", paymentMethod ?: JSONObject.NULL)
        put("receivedAmount", receivedAmount)
        put("shortageAmount", shortageAmount)
        put("changeAmount", changeAmount)
        put("message", message ?: JSONObject.NULL)
        put("presentation", presentation.toJsonObject())
        put("orderItems", JSONArray().apply {
            orderItems.forEach { item ->
                put(JSONObject().apply {
                    put("productId", item.productId)
                    put("name", item.name)
                    put("quantity", item.quantity)
                    put("unitPrice", item.unitPrice)
                    put("amount", item.amount)
                    put("latest", item.latest)
                    put("cancelled", item.cancelled)
                    put("taxSymbol", item.taxSymbol)
                })
            }
        })
    }.toString()
}

object CustomerDisplaySnapshotFactory {
    fun standby(
        storeName: String,
        presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
    ): CustomerDisplaySnapshot = CustomerDisplaySnapshot(
        mode = CustomerDisplayMode.STANDBY,
        storeName = storeName,
        message = presentation.standbyMessage,
        presentation = presentation,
    )

    fun sales(
        items: List<CartItem>,
        storeName: String,
        latestProductId: String? = null,
        presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
    ): CustomerDisplaySnapshot {
        if (items.isEmpty()) return standby(storeName, presentation)
        val total = TaxEngine.calculate(items).grossAmount
        return CustomerDisplaySnapshot(
            mode = CustomerDisplayMode.SALES,
            storeName = storeName,
            numberOfProducts = items.sumOf { it.quantity },
            subtotalAmount = total,
            totalAmount = total,
            orderItems = orderItems(items, latestProductId),
            presentation = presentation,
        )
    }

    /**
     * v2.5 UC-07: SCR-100 の小計／会計押下時に送る明示的な小計 snapshot。
     * SALES / ACCOUNTING の完全な v2.5 状態名移行は v1.37 で行う。
     */
    fun subtotal(
        items: List<CartItem>,
        storeName: String,
        presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
    ): CustomerDisplaySnapshot {
        if (items.isEmpty()) return standby(storeName, presentation)
        val total = TaxEngine.calculate(items).grossAmount
        return CustomerDisplaySnapshot(
            mode = CustomerDisplayMode.SUBTOTAL,
            storeName = storeName,
            numberOfProducts = items.sumOf { it.quantity },
            subtotalAmount = total,
            totalAmount = total,
            message = "小計をご確認ください",
            orderItems = orderItems(items),
            presentation = presentation,
        )
    }

    fun accounting(
        items: List<CartItem>,
        paymentState: PaymentState,
        storeName: String,
        presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
    ): CustomerDisplaySnapshot {
        if (items.isEmpty()) return standby(storeName, presentation)
        val total = TaxEngine.calculate(items).grossAmount
        val methods = paymentState.allocations
            .map { it.method.displayName }
            .distinct()
            .joinToString("＋")
            .ifBlank { "未選択" }
        return CustomerDisplaySnapshot(
            mode = CustomerDisplayMode.ACCOUNTING,
            storeName = storeName,
            numberOfProducts = items.sumOf { it.quantity },
            subtotalAmount = total,
            totalAmount = total,
            paymentMethod = methods,
            receivedAmount = paymentState.allocations.sumOf { it.receivedAmount },
            shortageAmount = paymentState.remaining(total),
            changeAmount = paymentState.changeAmount,
            message = if (paymentState.remaining(total) > 0L) "お支払い金額をご確認ください" else "お支払いを確認しました",
            orderItems = orderItems(items),
            presentation = presentation,
        )
    }

    fun complete(
        detail: SaleDetailRecord,
        storeName: String,
        presentation: CustomerDisplayPresentation = CustomerDisplayPresentation(),
    ): CustomerDisplaySnapshot = CustomerDisplaySnapshot(
        mode = CustomerDisplayMode.COMPLETE,
        transactionId = detail.summary.id.toString(),
        storeName = storeName,
        numberOfProducts = detail.items.sumOf { it.quantity },
        subtotalAmount = detail.taxSummary.grossAmount,
        totalAmount = detail.summary.totalAmount,
        paymentMethod = detail.summary.paymentLabel,
        receivedAmount = detail.payments.sumOf { it.receivedAmount },
        shortageAmount = 0L,
        changeAmount = detail.summary.changeAmount,
        message = if (detail.summary.changeAmount > 0L) "お釣りをご確認ください" else "ありがとうございました",
        orderItems = orderItems(detail.items),
        presentation = presentation,
    )

    private fun orderItems(
        items: List<CartItem>,
        latestProductId: String? = null,
    ): List<CustomerDisplayOrderItem> = items.map { item ->
        CustomerDisplayOrderItem(
            productId = item.product.id,
            name = item.product.name,
            quantity = item.quantity,
            unitPrice = item.unitPrice,
            amount = item.baseAmount,
            latest = item.product.id == latestProductId,
            taxSymbol = item.product.taxSymbol,
        )
    }
}
