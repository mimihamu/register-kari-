package jp.co.tenposinfo.register

import android.view.KeyEvent
import java.util.Locale

internal data class BarcodeScannedV136(
    val code: String,
    val symbology: String? = null,
    val timestamp: Long,
)

/**
 * Central input route for scanner events. UI code subscribes here instead of consuming raw KeyEvent data.
 */
internal object InputRouterV136 {
    private val lock = Any()
    @Volatile private var barcodeListener: ((BarcodeScannedV136) -> Unit)? = null

    fun setBarcodeListener(listener: (BarcodeScannedV136) -> Unit) {
        synchronized(lock) { barcodeListener = listener }
    }

    fun clearBarcodeListener(expected: (BarcodeScannedV136) -> Unit) {
        synchronized(lock) {
            if (barcodeListener === expected) barcodeListener = null
        }
    }

    fun barcodeScanned(event: BarcodeScannedV136) {
        barcodeListener?.invoke(event)
    }
}

/** Pure keyboard-wedge decoder. USB/Bluetooth HID scanners normally send text followed by Enter. */
internal class HidBarcodeDecoderV136(
    private val maxInterKeyMillis: Long = MAX_INTER_KEY_MS,
    private val maxTokenLength: Int = MAX_TOKEN_LENGTH,
) {
    private val buffer = StringBuilder()
    private var lastKeyAt = 0L

    fun accept(character: Char?, enter: Boolean, timestamp: Long): String? {
        if (enter) {
            val captured = buffer.toString().trim()
            reset()
            return captured.takeIf { it.isNotEmpty() }
        }
        val value = character ?: return null
        if (value.code !in 0x21..0x7e) return null
        if (lastKeyAt > 0L && timestamp - lastKeyAt > maxInterKeyMillis) buffer.setLength(0)
        if (buffer.length >= maxTokenLength) {
            reset()
            return null
        }
        buffer.append(value)
        lastKeyAt = timestamp
        return null
    }

    fun reset() {
        buffer.setLength(0)
        lastKeyAt = 0L
    }

    companion object {
        const val MAX_INTER_KEY_MS = 180L
        const val MAX_TOKEN_LENGTH = 64
    }
}

/**
 * Android HID ScannerGateway adapter. HID does not expose JAN/Code128 etc. symbology metadata,
 * so symbology is null while the exact scanned code and timestamp are preserved.
 */
internal class ScannerGatewayV136(
    private val decoder: HidBarcodeDecoderV136 = HidBarcodeDecoderV136(),
) {
    @Volatile private var started = false

    fun start() {
        decoder.reset()
        started = true
    }

    fun stop() {
        started = false
        decoder.reset()
    }

    fun handle(event: KeyEvent): Boolean {
        if (!started || event.action != KeyEvent.ACTION_DOWN) return false
        val isEnter = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val char = if (isEnter) null else event.unicodeChar.takeIf { it != 0 }?.toChar()
        val token = decoder.accept(char, isEnter, event.eventTime)
        if (token != null) {
            InputRouterV136.barcodeScanned(
                BarcodeScannedV136(
                    code = token,
                    symbology = null,
                    timestamp = event.eventTime,
                ),
            )
            return true
        }
        return false
    }
}

/** O(1) exact index used by SAL-006 scanner registration. Ambiguous matches fail closed. */
internal class BarcodeProductIndexV136(products: List<Product>) {
    private val byCode = products
        .groupBy { it.id.lowercase(Locale.ROOT) }
        .mapValues { (_, matches) -> matches.distinctBy { it.id }.singleOrNull() }
    private val byBarcode = products
        .filter { it.barcode.isNotBlank() }
        .groupBy { it.barcode }
        .mapValues { (_, matches) -> matches.distinctBy { it.id }.singleOrNull() }

    fun findExact(raw: String): Product? {
        val token = raw.trim()
        if (token.isBlank()) return null
        val codeMatch = byCode[token.lowercase(Locale.ROOT)]
        val barcodeMatch = byBarcode[token]
        return when {
            codeMatch == null -> barcodeMatch
            barcodeMatch == null -> codeMatch
            codeMatch.id == barcodeMatch.id -> codeMatch
            else -> null
        }
    }
}

internal object TemporaryBarcodeProductPolicyV136 {
    const val NAME = "未登録商品"
    const val MAX_UNIT_PRICE = 9_999_999L

    fun create(code: String, unitPrice: Long, taxCategory: TaxCategory): Product {
        val normalized = CatalogValidation.normalizeBarcode(code)
        require(normalized.isNotBlank()) { "読取コードが空です" }
        require(unitPrice in 1..MAX_UNIT_PRICE) { "仮商品の単価は1～${MAX_UNIT_PRICE}円です" }
        return Product(
            id = normalized,
            name = NAME,
            unitPrice = unitPrice,
            taxCategory = taxCategory,
            displayOrder = Int.MAX_VALUE,
            barcode = normalized,
        )
    }
}
