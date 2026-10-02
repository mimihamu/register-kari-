package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class V136QuantityContractTest {
    @Test
    fun acceptsWholeOneAndTwoDecimalQuantitiesExactly() {
        assertEquals("2", QuantityV136.parse("2").format())
        assertEquals("1.5", QuantityV136.parse("1.5").format())
        assertEquals("0.25", QuantityV136.parse("0.25").format())
        assertEquals(25L, QuantityV136.parse("0.25").hundredths)
    }

    @Test
    fun rejectsMoreThanTwoDecimalPlacesAndZero() {
        assertThrows(IllegalArgumentException::class.java) { QuantityV136.parse("1.234") }
        assertThrows(IllegalArgumentException::class.java) { QuantityV136.parse("0") }
    }

    @Test
    fun unitPriceMeansPricePerOnePointZeroZero() {
        assertEquals(375L, QuantityV136.parse("1.5").multiplyYen(250L))
        assertEquals(62L, QuantityV136.parse("0.25").multiplyYen(250L))
    }
}
