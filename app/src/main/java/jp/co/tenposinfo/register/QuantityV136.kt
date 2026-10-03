package jp.co.tenposinfo.register

import java.math.BigDecimal
import java.math.RoundingMode

@JvmInline
value class QuantityV136 private constructor(val hundredths: Long) {
    init { require(hundredths > 0) { "quantity must be greater than zero" } }

    fun format(): String {
        val whole = hundredths / SCALE
        val fraction = hundredths % SCALE
        return if (fraction == 0L) whole.toString()
        else if (fraction % 10L == 0L) "$whole.${fraction / 10L}"
        else "$whole.${fraction.toString().padStart(2, '0')}"
    }

    fun multiplyYen(unitPrice: Long): Long {
        require(unitPrice >= 0)
        return BigDecimal.valueOf(unitPrice)
            .multiply(BigDecimal.valueOf(hundredths))
            .divide(BigDecimal.valueOf(SCALE), 0, RoundingMode.DOWN)
            .longValueExact()
    }

    companion object {
        const val SCALE = 100L
        fun ofWhole(value: Int): QuantityV136 = QuantityV136(Math.multiplyExact(value.toLong(), SCALE))
        fun fromHundredths(value: Long): QuantityV136 = QuantityV136(value)
        fun parse(value: String): QuantityV136 {
            val normalized = value.trim()
            require(Regex("\\d+(?:\\.\\d{1,2})?").matches(normalized)) {
                "数量は整数または小数2桁までで入力してください"
            }
            val scaled = BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact()
            return QuantityV136(scaled)
        }
    }
}
