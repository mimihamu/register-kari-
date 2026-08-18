package jp.co.tenposinfo.register.cd

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
    val latest: Boolean,
    val cancelled: Boolean,
    val taxSymbol: String = "",
)

data class CustomerDisplaySnapshot(
    val schemaVersion: Int,
    val sequence: Long,
    val serverInstanceId: String? = null,
    val sentAtMillis: Long = 0L,
    val mode: CustomerDisplayMode,
    val transactionId: String?,
    val storeName: String,
    val numberOfProducts: Int,
    val subtotalAmount: Long,
    val totalAmount: Long,
    val paymentMethod: String?,
    val receivedAmount: Long,
    val shortageAmount: Long,
    val changeAmount: Long,
    val message: String?,
    val orderItems: List<CustomerDisplayOrderItem>,
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

    companion object {
        fun parse(json: String): CustomerDisplaySnapshot {
            val root = JSONObject(json)
            val schemaVersion = root.getInt("schemaVersion")
            require(schemaVersion == CUSTOMER_DISPLAY_SCHEMA_VERSION) {
                "未対応の通信形式です（schemaVersion=$schemaVersion）"
            }
            val items = root.optJSONArray("orderItems")
            val orderItems = buildList {
                if (items != null) {
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        add(
                            CustomerDisplayOrderItem(
                                productId = item.optString("productId"),
                                name = item.optString("name"),
                                quantity = item.optInt("quantity"),
                                unitPrice = item.optLong("unitPrice"),
                                amount = item.optLong("amount"),
                                latest = item.optBoolean("latest"),
                                cancelled = item.optBoolean("cancelled"),
                                taxSymbol = item.optString("taxSymbol"),
                            ),
                        )
                    }
                }
            }
            return CustomerDisplaySnapshot(
                schemaVersion = schemaVersion,
                sequence = root.getLong("sequence"),
                serverInstanceId = root.optNullableString("serverInstanceId"),
                sentAtMillis = root.optLong("sentAtMillis"),
                mode = CustomerDisplayMode.valueOf(root.getString("mode")),
                transactionId = root.optNullableString("transactionId"),
                storeName = root.optString("storeName", "つぐレジ"),
                numberOfProducts = root.optInt("numberOfProducts"),
                subtotalAmount = root.optLong("subtotalAmount"),
                totalAmount = root.optLong("totalAmount"),
                paymentMethod = root.optNullableString("paymentMethod"),
                receivedAmount = root.optLong("receivedAmount"),
                shortageAmount = root.optLong("shortageAmount"),
                changeAmount = root.optLong("changeAmount"),
                message = root.optNullableString("message"),
                orderItems = orderItems,
                presentation = CustomerDisplayPresentation.fromJsonObject(
                    root.optJSONObject("presentation"),
                ),
            )
        }

        fun initial(): CustomerDisplaySnapshot = CustomerDisplaySnapshot(
            schemaVersion = CUSTOMER_DISPLAY_SCHEMA_VERSION,
            sequence = 0L,
            serverInstanceId = null,
            sentAtMillis = 0L,
            mode = CustomerDisplayMode.STANDBY,
            transactionId = null,
            storeName = "つぐレジ",
            numberOfProducts = 0,
            subtotalAmount = 0L,
            totalAmount = 0L,
            paymentMethod = null,
            receivedAmount = 0L,
            shortageAmount = 0L,
            changeAmount = 0L,
            message = "いらっしゃいませ",
            orderItems = emptyList(),
            presentation = CustomerDisplayPresentation(),
        )
    }
}

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

data class CustomerDisplayUiState(
    val connected: Boolean = false,
    val snapshot: CustomerDisplaySnapshot = CustomerDisplaySnapshot.initial(),
    val statusMessage: String = "未接続",
    val lastError: String? = null,
)

object CustomerDisplayStateReducer {
    fun connected(current: CustomerDisplayUiState): CustomerDisplayUiState = current.copy(
        connected = true,
        statusMessage = "接続中",
        lastError = null,
    )

    fun received(
        current: CustomerDisplayUiState,
        incoming: CustomerDisplaySnapshot,
    ): CustomerDisplayUiState {
        if (incoming.schemaVersion != CUSTOMER_DISPLAY_SCHEMA_VERSION) {
            return current.copy(lastError = "未対応の通信形式です")
        }
        val currentInstance = current.snapshot.serverInstanceId
        val incomingInstance = incoming.serverInstanceId
        val incomingIdentifiedServer = !incomingInstance.isNullOrBlank()
        val serverInstanceChanged = incomingIdentifiedServer && incomingInstance != currentInstance
        if (!serverInstanceChanged && incoming.sequence <= current.snapshot.sequence) {
            return current
        }
        return current.copy(
            connected = true,
            snapshot = incoming,
            statusMessage = "接続中",
            lastError = null,
        )
    }

    fun disconnected(
        current: CustomerDisplayUiState,
        reason: String,
    ): CustomerDisplayUiState = current.copy(
        connected = false,
        statusMessage = "再接続中",
        lastError = reason,
    )
}
