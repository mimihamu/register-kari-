package jp.co.tenposinfo.register

import android.view.KeyEvent

data class ProductQuantityKeyDecisionV135(
    val selectedLineQuantity: Int? = null,
    val pendingProductQuantity: Int? = null,
)

object ProductQuantityKeyPolicyV135 {
    fun decide(raw: String, hasSelectedLine: Boolean): ProductQuantityKeyDecisionV135? {
        val quantity = raw.toIntOrNull()?.takeIf { it in 1..99_999 } ?: return null
        return if (hasSelectedLine) {
            ProductQuantityKeyDecisionV135(selectedLineQuantity = quantity)
        } else {
            ProductQuantityKeyDecisionV135(pendingProductQuantity = quantity)
        }
    }
}

object ProductLookupPolicyV135 {
    fun findExact(products: List<Product>, raw: String): Product? {
        val token = raw.trim()
        if (token.isBlank()) return null
        val matches = products.filter { product ->
            product.id.equals(token, ignoreCase = true) ||
                product.barcode.isNotBlank() && product.barcode == token
        }.distinctBy { it.id }
        return matches.singleOrNull()
    }

    fun search(products: List<Product>, raw: String): List<Product> {
        val query = raw.trim()
        if (query.isBlank()) return products.sortedBy { it.id }
        return products
            .mapNotNull { product ->
                val exactCode = product.id.equals(query, ignoreCase = true)
                val exactBarcode = product.barcode.isNotBlank() && product.barcode == query
                val codePrefix = product.id.startsWith(query, ignoreCase = true)
                val nameMatch = product.name.contains(query, ignoreCase = true)
                val kanaMatch = product.kana.contains(query, ignoreCase = true)
                val codeMatch = product.id.contains(query, ignoreCase = true)
                val barcodeMatch = product.barcode.isNotBlank() && product.barcode.contains(query)
                if (!(exactCode || exactBarcode || codePrefix || nameMatch || kanaMatch || codeMatch || barcodeMatch)) {
                    null
                } else {
                    val score = when {
                        exactCode || exactBarcode -> 0
                        codePrefix -> 1
                        nameMatch || kanaMatch -> 2
                        else -> 3
                    }
                    score to product
                }
            }
            .sortedWith(compareBy<Pair<Int, Product>> { it.first }.thenBy { it.second.id })
            .map { it.second }
    }
}

/** Keyboard-wedge scanners typically emit a rapid ASCII sequence followed by Enter. */
object BarcodeScannerRuntimeV135 {
    private const val MAX_INTER_KEY_MS = 180L
    private const val MIN_TOKEN_LENGTH = 4
    private const val MAX_TOKEN_LENGTH = 128
    private val lock = Any()
    private val buffer = StringBuilder()
    private var lastKeyAt = 0L
    @Volatile private var listener: ((String) -> Unit)? = null

    fun setListener(value: (String) -> Unit) {
        synchronized(lock) {
            buffer.setLength(0)
            lastKeyAt = 0L
            listener = value
        }
    }

    fun clearListener(expected: (String) -> Unit) {
        synchronized(lock) {
            if (listener === expected) {
                listener = null
                buffer.setLength(0)
                lastKeyAt = 0L
            }
        }
    }

    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val target = listener ?: return false
        val token = synchronized(lock) {
            val now = event.eventTime
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val captured = buffer.toString().trim()
                    buffer.setLength(0)
                    lastKeyAt = 0L
                    captured.takeIf { it.length >= MIN_TOKEN_LENGTH }
                }
                else -> {
                    val unicode = event.unicodeChar
                    if (unicode in 0x21..0x7e) {
                        if (lastKeyAt > 0L && now - lastKeyAt > MAX_INTER_KEY_MS) buffer.setLength(0)
                        if (buffer.length < MAX_TOKEN_LENGTH) buffer.append(unicode.toChar())
                        lastKeyAt = now
                    }
                    null
                }
            }
        }
        if (token == null) return false
        target(token)
        return true
    }
}
