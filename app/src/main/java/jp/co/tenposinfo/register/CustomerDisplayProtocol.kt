package jp.co.tenposinfo.register

import org.json.JSONArray
import org.json.JSONObject

internal const val CUSTOMER_DISPLAY_SCHEMA_VERSION = 1
internal const val CUSTOMER_DISPLAY_PATH = "/customer-display/v1"

enum class CustomerDisplayMode {
    STANDBY,
    SALES,
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
                })
            }
        })
    }.toString()
}

object CustomerDisplaySnapshotFactory {
    fun standby(storeName: String): CustomerDisplaySnapshot = CustomerDisplaySnapshot(
        mode = CustomerDisplayMode.STANDBY,
        storeName = storeName,
        message = "いらっしゃいませ",
    )

    fun sales(
        items: List<CartItem>,
        storeName: String,
        latestProductId: String? = null,
    ): CustomerDisplaySnapshot {
        if (items.isEmpty()) return standby(storeName)
        val total = TaxEngine.calculate(items).grossAmount
        return CustomerDisplaySnapshot(
            mode = CustomerDisplayMode.SALES,
            storeName = storeName,
            numberOfProducts = items.sumOf { it.quantity },
            subtotalAmount = total,
            totalAmount = total,
            orderItems = orderItems(items, latestProductId),
        )
    }

    fun accounting(
        items: List<CartItem>,
        paymentState: PaymentState,
        storeName: String,
    ): CustomerDisplaySnapshot {
        if (items.isEmpty()) return standby(storeName)
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
        )
    }

    fun complete(
        detail: SaleDetailRecord,
        storeName: String,
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
        )
    }
}
