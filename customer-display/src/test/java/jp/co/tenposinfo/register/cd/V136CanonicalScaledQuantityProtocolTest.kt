package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136CanonicalScaledQuantityProtocolTest {
    private fun envelope(item: String): String =
        """{"schemaVersion":1,"sequence":1,"mode":"SALES","storeName":"つぐレジ","orderItems":[$item]}"""

    @Test
    fun canonicalScaledQuantityHasPrecedenceAndPreservesExactText() {
        val wire = envelope("""{"productId":"w","name":"量り売り","quantity":1,"quantityScaled":50,"quantityScale":2,"unitPrice":200,"lineAmount":100}""")
        val parsed = CustomerDisplaySnapshot.parse(wire).orderItems.single()
        assertEquals(50L, parsed.quantityHundredths)
        assertEquals("0.5", parsed.quantityText)
        assertEquals(100L, parsed.amount)
    }

    @Test
    fun legacyHundredthsFallbackDoesNotRoundAwayFractionalQuantity() {
        val wire = envelope("""{"productId":"w","name":"量り売り","quantity":1,"quantityHundredths":125,"amount":250}""")
        val parsed = CustomerDisplaySnapshot.parse(wire).orderItems.single()
        assertEquals(125L, parsed.quantityHundredths)
        assertEquals("1.25", parsed.quantityText)
    }

    @Test
    fun conflictingOrFloatingExactQuantityIsRejected() {
        val mismatch = envelope("""{"quantity":1,"quantityScaled":50,"quantityHundredths":100,"quantityScale":2}""")
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(mismatch) }.isFailure)

        val fractionalInteger = envelope("""{"quantity":1,"quantityScaled":0.5,"quantityScale":2}""")
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(fractionalInteger) }.isFailure)

        val wrongScale = envelope("""{"quantity":1,"quantityScaled":50,"quantityScale":3}""")
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(wrongScale) }.isFailure)
    }
}
