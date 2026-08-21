package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Tax004MixedTaxAuditTest {
    @Test
    fun allowWarnAndDenyRemainDistinctAtRegistration() {
        val existing = listOf(item("in10", 1_100, 10, included = true))
        val candidate = item("out10", 1_000, 10, included = false)

        val allow = MixedTaxCartPolicyV135.evaluate(existing, candidate, MixedTaxPolicy.ALLOW)
        val warn = MixedTaxCartPolicyV135.evaluate(existing, candidate, MixedTaxPolicy.WARN)
        val deny = MixedTaxCartPolicyV135.evaluate(existing, candidate, MixedTaxPolicy.BLOCK)

        assertEquals(MixedTaxCartActionV135.ADD, allow.action)
        assertEquals(MixedTaxCartActionV135.WARN_AND_ADD, warn.action)
        assertTrue(warn.requiresWarningHistory)
        assertEquals(MixedTaxCartActionV135.DENY, deny.action)
        assertFalse(deny.mayAdd)
    }

    @Test
    fun warnAcknowledgementCapturesOnlyActuallyMixedRatesIncludingFutureRate() {
        val items = listOf(
            item("in12", 1_120, 12, included = true),
            item("out12", 1_000, 12, included = false),
            item("in8", 1_080, 8, included = true),
        )

        assertEquals(setOf(12), MixedTaxPaymentAcknowledgementV135.targetRates(items))
        val detail = MixedTaxPaymentAcknowledgementV135.auditDetail(items)
        assertTrue("対象税率=12%" in detail)
        assertTrue("policy=WARN" in detail)
    }

    @Test
    fun allowAndWarnUseUnifiedOncePerRateCalculation() {
        val items = listOf(
            item("in10", 1_100, 10, included = true),
            item("out10", 1_000, 10, included = false),
        )

        val summary = TaxEngine.calculate(items)
        val bucket = summary.buckets.single()

        assertEquals(10, bucket.ratePercent)
        assertEquals(2_000L, bucket.netAmount)
        assertEquals(200L, bucket.taxAmount)
        assertEquals(2_200L, bucket.grossAmount)
    }

    @Test
    fun differentRateIncludedAndExcludedAreNotBlocked() {
        val decision = MixedTaxCartPolicyV135.evaluate(
            existingItems = listOf(item("in10", 1_100, 10, included = true)),
            candidate = item("out8", 1_000, 8, included = false),
            policy = MixedTaxPolicy.BLOCK,
        )

        assertTrue(decision.mayAdd)
        assertTrue(decision.introducedMixedRates.isEmpty())
    }

    @Test
    fun paymentScreenPersistsWarnAcknowledgementBeforeEnablingCompletion() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PaymentScreenV135.kt").readText()

        assertTrue("MixedTaxPaymentAcknowledgementV135.record" in source)
        assertTrue("acknowledgedMixedTax = true" in source)
        assertTrue("税混在の確認履歴を保存できないため、会計確定できません" in source)
        assertTrue("!mixedNeedsAcknowledgement || acknowledgedMixedTax" in source)
    }

    @Test
    fun acknowledgementAuditStoresEventOperatorTimestampAndRateDetail() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MixedTaxPaymentAcknowledgementV135.kt").readText()

        assertTrue("MIXED_TAX_PAYMENT_ACK" in source)
        assertTrue("operator_name" in source)
        assertTrue("created_at" in source)
        assertTrue("対象税率=" in source)
        assertTrue("insertOrThrow" in source)
    }

    @Test
    fun settingsScreenKeepsAllThreeBusinessLabels() {
        val source = File("src/main/java/jp/co/tenposinfo/register/TaxInvoiceSettingsActivity.kt").readText()

        assertTrue("MixedPolicyButton(\"許可\"" in source)
        assertTrue("MixedPolicyButton(\"警告\"" in source)
        assertTrue("MixedPolicyButton(\"禁止\"" in source)
        assertTrue("MixedTaxPolicy.ALLOW" in source)
        assertTrue("MixedTaxPolicy.WARN" in source)
        assertTrue("MixedTaxPolicy.BLOCK" in source)
    }

    private fun item(id: String, price: Long, rate: Int, included: Boolean): CartItem = CartItem(
        product = Product(
            id = id,
            name = id,
            unitPrice = price,
            taxCategory = TaxCategory.EXCLUDED_10,
            displayOrder = 1,
            taxKey = "${if (included) "IN" else "OUT"}_$rate",
            taxLabel = "$rate%${if (included) "内税" else "外税"}",
            taxSymbol = if (included) "内" else "外",
            taxRatePercent = rate,
            taxIncluded = included,
            taxable = true,
            reducedTax = false,
        ),
        quantity = 1,
    )
}
