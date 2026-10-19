package jp.co.tenposinfo.register.cd

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

internal const val CUSTOMER_DISPLAY_SCHEMA_VERSION = 1
internal const val CUSTOMER_DISPLAY_PATH = "/customer-display/v1"

enum class CustomerDisplayMessageType {
    SNAPSHOT,
    STATE_UPDATE,
}

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
    val lineId: String = "",
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
    val sessionId: String? = null,
    val messageType: CustomerDisplayMessageType = CustomerDisplayMessageType.SNAPSHOT,
) {
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("sequence", sequence)
        put("sessionId", sessionId ?: JSONObject.NULL)
        put("messageType", messageType.name)
        put("serverInstanceId", serverInstanceId ?: JSONObject.NULL)
        put("sentAtMillis", sentAtMillis)
        put("sentAt", if (sentAtMillis > 0L) java.time.Instant.ofEpochMilli(sentAtMillis).toString() else JSONObject.NULL)
        put("mode", mode.name)
        put("transactionId", transactionId ?: JSONObject.NULL)
        put("storeName", storeName)
        put("numberOfProducts", numberOfProducts)
        put("itemCount", numberOfProducts)
        put("latestLineId", orderItems.lastOrNull { it.latest }?.lineId ?: JSONObject.NULL)
        put("subtotalAmount", subtotalAmount)
        put("totalAmount", totalAmount)
        put("paymentMethod", paymentMethod ?: JSONObject.NULL)
        put("receivedAmount", receivedAmount)
        put("shortageAmount", shortageAmount)
        put("remainingAmount", shortageAmount)
        put("changeAmount", changeAmount)
        put("payment", paymentMethod?.let { name -> JSONObject().apply {
            put("receiptName", name)
            put("tenderedAmount", receivedAmount)
            put("appliedAmount", (totalAmount - shortageAmount).coerceAtLeast(0L))
        } } ?: JSONObject.NULL)
        put("message", message ?: JSONObject.NULL)
        put("presentation", presentation.toJsonObject())
        put("orderItems", JSONArray().apply {
            orderItems.forEach { item ->
                put(JSONObject().apply {
                    put("productId", item.productId)
                    put("lineId", item.lineId)
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
                    put("isVoided", item.cancelled)
                    put("taxSymbol", item.taxSymbol)
                })
            }
        })
        // v2.5 §30.6: canonical items; orderItems remains for older CD installations.
        put("items", JSONArray(getJSONArray("orderItems").toString()))
    }.toString()

    companion object {
        fun parse(json: String): CustomerDisplaySnapshot {
            val root = JSONObject(json)
            // Accept formal v2.5 string "1.0" and legacy numeric 1; reject unknown majors.
            val rawSchema = root.get("schemaVersion").toString()
            require(rawSchema == "1" || rawSchema == "1.0") {
                "E-CD-005: 未対応の通信形式です（schemaVersion=$rawSchema）"
            }
            val schemaVersion = CUSTOMER_DISPLAY_SCHEMA_VERSION
            // Canonical v2.5 "items" takes precedence; keep legacy compatibility.
            val items = if (root.has("items") && !root.isNull("items")) {
                root.getJSONArray("items")
            } else {
                root.optJSONArray("orderItems")
            }
            require((items?.length() ?: 0) <= 200) { "items exceeds 200 lines" }
            val canonicalItems = root.has("items") && !root.isNull("items")
            val latestLineId = root.optNullableString("latestLineId")
            // Canonical payloads must never show a guessed zero for absent money values.
            fun money(name: String): Long {
                if (!root.has(name) || root.isNull(name)) {
                    require(!canonicalItems) { "E-CD-005: $name is required" }
                    return 0L // legacy wire only
                }
                return root.strictLong(name)
            }
            val payment = root.optJSONObject("payment")
            if (payment != null) {
                payment.strictLong("tenderedAmount")
                payment.strictLong("appliedAmount")
            }
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
                                lineId = item.optString("lineId").ifBlank { "legacy-line-${index + 1}" },
                                name = item.optString("name"),
                                quantity = item.optInt("quantity"),
                                quantityHundredths = scaled,
                                quantityText = item.optString("quantityText").ifBlank {
                                    BigDecimal.valueOf(scaled, 2).stripTrailingZeros().toPlainString()
                                },
                                unitPrice = if (canonicalItems) item.strictLong("unitPrice") else item.optLong("unitPrice"),
                                amount = if (item.has("lineAmount")) {
                                    item.strictLong("lineAmount")
                                } else item.optLong("amount"),
                                latest = if (latestLineId != null) latestLineId == item.optString("lineId")
                                    else item.optBoolean("latest"),
                                cancelled = if (item.has("isVoided")) item.getBoolean("isVoided")
                                    else item.optBoolean("cancelled"),
                                taxSymbol = item.optString("taxSymbol"),
                            ),
                        )
                    }
                }
            }
            return CustomerDisplaySnapshot(
                schemaVersion = schemaVersion,
                sequence = root.strictLong("sequence"),
                sessionId = root.optNullableString("sessionId"),
                messageType = CustomerDisplayMessageType.valueOf(
                    root.optString("messageType", "SNAPSHOT"),
                ),
                serverInstanceId = root.optNullableString("serverInstanceId"),
                sentAtMillis = root.optLong("sentAtMillis"),
                mode = when (root.getString("mode")) {
                    "CART" -> CustomerDisplayMode.SALES
                    "PAYMENT", "CASH_RECEIVED" -> CustomerDisplayMode.ACCOUNTING
                    "CHANGE" -> CustomerDisplayMode.COMPLETE
                    else -> CustomerDisplayMode.valueOf(root.getString("mode"))
                },
                transactionId = root.optNullableString("transactionId"),
                storeName = root.optString("storeName", "つぐレジ"),
                numberOfProducts = if (root.has("itemCount")) root.getInt("itemCount") else root.optInt("numberOfProducts"),
                subtotalAmount = money("subtotalAmount"),
                totalAmount = money("totalAmount"),
                paymentMethod = root.optNullableString("paymentMethod") ?: payment?.optNullableString("receiptName"),
                receivedAmount = if (root.has("receivedAmount")) money("receivedAmount") else payment?.strictLong("tenderedAmount") ?: 0L,
                shortageAmount = if (root.has("remainingAmount")) money("remainingAmount") else money("shortageAmount"),
                changeAmount = money("changeAmount").also { require(it >= 0L) { "E-CD-005: changeAmount must be nonnegative" } },
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
        val sessionChanged = !incoming.sessionId.isNullOrBlank() &&
            incoming.sessionId != current.snapshot.sessionId
        if (!serverInstanceChanged && !sessionChanged &&
            incoming.sequence < current.snapshot.sequence) {
            return current
        }
        if (!serverInstanceChanged && !sessionChanged &&
            incoming.sequence == current.snapshot.sequence && current.connected) {
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
