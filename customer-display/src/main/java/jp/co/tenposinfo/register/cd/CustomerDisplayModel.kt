package jp.co.tenposinfo.register.cd

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

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
    val quantityHundredths: Long = quantity.toLong() * 100L,
    val quantityText: String = quantity.toString(),
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
                    put("quantityHundredths", item.quantityHundredths)
                    put("quantityScaled", item.quantityHundredths)
                    put("quantityScale", 2)
                    put("quantityText", item.quantityText)
                    put("unitPrice", item.unitPrice)
                    put("amount", item.amount)
                    put("lineAmount", item.amount)
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
                        // The v2.5 scaled integer is authoritative whenever present.
                        // Legacy v1 snapshots continue to support quantityHundredths/quantity.
                        if (item.has("quantityScale") && !item.isNull("quantityScale")) {
                            require(item.getInt("quantityScale") == 2) { "quantityScale must be 2" }
                        }
                        val scaled = when {
                            item.has("quantityScaled") && !item.isNull("quantityScaled") ->
                                item.strictLong("quantityScaled")
                            item.has("quantityHundredths") && !item.isNull("quantityHundredths") ->
                                item.strictLong("quantityHundredths")
                            else -> Math.multiplyExact(item.optInt("quantity").toLong(), 100L)
                        }
                        if (item.has("quantityHundredths") && item.has("quantityScaled") &&
                            !item.isNull("quantityHundredths") && !item.isNull("quantityScaled")
                        ) {
                            require(item.strictLong("quantityHundredths") == scaled) {
                                "quantityScaled and quantityHundredths disagree"
                            }
                        }
                        add(
                            CustomerDisplayOrderItem(
                                productId = item.optString("productId"),
                                name = item.optString("name"),
                                quantity = item.optInt("quantity"),
                                quantityHundredths = scaled,
                                quantityText = item.optString("quantityText").ifBlank {
                                    BigDecimal.valueOf(scaled, 2).stripTrailingZeros().toPlainString()
                                },
                                unitPrice = item.optLong("unitPrice"),
                                amount = if (item.has("lineAmount")) {
                                    item.getLong("lineAmount")
                                } else item.optLong("amount"),
                                latest = item.optBoolean("latest"),
                                cancelled = item.optBoolean("cancelled", item.optBoolean("isVoided")),
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

// org.json getLong can silently truncate decimal numbers; exact quantities must not.
private fun JSONObject.strictLong(name: String): Long {
    val raw = get(name).toString()
    require(Regex("-?[0-9]+").matches(raw)) { "$name must be an integer" }
    return raw.toLong()
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
        // Transport 101 handshake is not a verified business snapshot.
        // The old on-disk / previous-session amount stays hidden until received().
        connected = false,
        statusMessage = "最新の表示データを受信中",
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
        // Equal sequence is a fresh full snapshot on reconnect. It is authoritative
        // even when an identical sequence was persisted before the transport failed.
        // Strictly older data from the same server must never resurrect old totals.
        if (!serverInstanceChanged && incoming.sequence < current.snapshot.sequence) {
            return current
        }
        if (!serverInstanceChanged && incoming.sequence == current.snapshot.sequence && current.connected) {
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
