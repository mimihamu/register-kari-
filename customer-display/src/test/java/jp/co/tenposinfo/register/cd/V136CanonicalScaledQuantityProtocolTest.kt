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
    fun canonicalItemsArrayIsAcceptedWithoutLegacyAlias() {
        val wire = """{"schemaVersion":1,"sequence":2,"mode":"SALES","storeName":"つぐレジ","itemCount":1,"items":[{"productId":"a","name":"量り売り","quantity":1,"quantityScaled":125,"quantityScale":2,"unitPrice":200,"lineAmount":250,"isVoided":true}]}"""
        val parsed = CustomerDisplaySnapshot.parse(wire)
        assertEquals(1, parsed.numberOfProducts)
        assertEquals("1.25", parsed.orderItems.single().quantityText)
        assertEquals(250L, parsed.orderItems.single().amount)
        assertTrue(parsed.orderItems.single().cancelled)
    }

    @Test
    fun canonicalItemsTakePrecedenceOverOldAlias() {
        val wire = """{"schemaVersion":1,"sequence":3,"mode":"SALES","items":[{"name":"NEW","quantityScaled":50,"quantityScale":2}],"orderItems":[{"name":"OLD","quantity":1}]}"""
        assertEquals("NEW", CustomerDisplaySnapshot.parse(wire).orderItems.single().name)
    }

    @Test
    fun canonicalItemsRejectMoreThanTwoHundredRows() {
        val items = (1..201).joinToString(",") { """{"name":"row$it","quantityScaled":100,"quantityScale":2}""" }
        val wire = """{"schemaVersion":1,"sequence":4,"mode":"SALES","items":[$items]}"""
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(wire) }.isFailure)
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
