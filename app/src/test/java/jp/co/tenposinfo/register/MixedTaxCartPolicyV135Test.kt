package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedTaxCartPolicyV135Test {
    @Test
    fun allow_sameRateMix_addsWithoutWarning() {
        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = listOf(item("in10", 10, included = true)),
            candidate = item("out10", 10, included = false),
            policy = MixedTaxPolicy.ALLOW,
        )

        assertEquals(MixedTaxCartActionV135.ADD, decision.action)
        assertTrue(decision.mayAdd)
        assertEquals(setOf(10), decision.introducedMixedRates)
        assertNull(decision.message)
    }

    @Test
    fun warn_sameRateMix_addsAndRequiresHistory() {
        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = listOf(item("in10", 10, included = true)),
            candidate = item("out10", 10, included = false),
            policy = MixedTaxPolicy.WARN,
        )

        assertEquals(MixedTaxCartActionV135.WARN_AND_ADD, decision.action)
        assertTrue(decision.mayAdd)
        assertTrue(decision.requiresWarningHistory)
        assertTrue(decision.message?.contains("会計確定前") == true)
    }

    @Test
    fun block_sameRateMix_rejectsRegistration() {
        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = listOf(item("in10", 10, included = true)),
            candidate = item("out10", 10, included = false),
            policy = MixedTaxPolicy.BLOCK,
        )

        assertEquals(MixedTaxCartActionV135.DENY, decision.action)
        assertFalse(decision.mayAdd)
        assertTrue(decision.message?.contains("追加できません") == true)
    }

    @Test
    fun differentRates_doNotTriggerMixedPolicy() {
        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = listOf(item("in10", 10, included = true)),
            candidate = item("out8", 8, included = false),
            policy = MixedTaxPolicy.BLOCK,
        )

        assertEquals(MixedTaxCartActionV135.ADD, decision.action)
        assertTrue(decision.introducedMixedRates.isEmpty())
    }

    @Test
    fun nonTaxable_doesNotTriggerMixedPolicy() {
        val existing = listOf(item("in10", 10, included = true))
        val candidate = CartItem(
            Product(
                id = "non",
                name = "非課税",
                unitPrice = 100,
                taxCategory = TaxCategory.NON_TAXABLE,
                displayOrder = 1,
            ),
            1,
        )

        val decision = MixedTaxCartPolicyV135.evaluate(existing, candidate, MixedTaxPolicy.BLOCK)

        assertEquals(MixedTaxCartActionV135.ADD, decision.action)
    }

    @Test
    fun secondMixedRate_isDetectedEvenWhenAnotherRateIsAlreadyMixed() {
        val existing = listOf(
            item("in10", 10, included = true),
            item("out10", 10, included = false),
            item("in8", 8, included = true),
        )

        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = existing,
            candidate = item("out8", 8, included = false),
            policy = MixedTaxPolicy.WARN,
        )

        assertEquals(setOf(8), decision.introducedMixedRates)
        assertEquals(MixedTaxCartActionV135.WARN_AND_ADD, decision.action)
    }

    @Test
    fun existingMixedRate_doesNotRepeatWarningForUnrelatedProduct() {
        val existing = listOf(
            item("in10", 10, included = true),
            item("out10", 10, included = false),
        )

        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = existing,
            candidate = item("in8", 8, included = true),
            policy = MixedTaxPolicy.WARN,
        )

        assertEquals(MixedTaxCartActionV135.ADD, decision.action)
        assertTrue(decision.introducedMixedRates.isEmpty())
    }

    private fun item(id: String, rate: Int, included: Boolean): CartItem = CartItem(
        product = Product(
            id = id,
            name = id,
            unitPrice = 1_000,
            taxCategory = TaxCategory.EXCLUDED_10,
            displayOrder = 1,
            taxKey = "${if (included) "IN" else "OUT"}_$rate",
            taxLabel = "$rate%${if (included) "内税" else "外税"}",
            taxSymbol = if (included) "内" else "外",
            taxRatePercent = rate,
            taxIncluded = included,
            taxable = true,
            reducedTax = rate == 8,
        ),
        quantity = 1,
    )
}
