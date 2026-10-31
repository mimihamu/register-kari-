package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Uc08PaymentUiSourceTest {
    @Test
    fun initialReleaseTenderSetIncludesConfiguredNonCashMethods() {
        assertEquals(
            listOf("現金", "クレジット", "電子マネー", "QR", "商品券", "掛売", "その他"),
            PaymentMethod.entries.map { it.displayName },
        )
    }

    @Test
    fun paymentScreenUsesConfiguredTenderSetAndOrder() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PaymentScreenV135.kt").readText()
        assertTrue("PaymentSettingsStoreV136" in source)
        assertTrue("paymentSettings.enabledMethods()" in source)
        assertTrue("enabledNonCashMethods.chunked(3)" in source)
        assertTrue("add(PaymentMethod.CASH)" in source)
        assertTrue("paymentSettings.tenderPolicyFor(method)" in source)
    }

    @Test
    fun paymentScreenKeepsCommitDisabledUntilPaidAndTaxGuardSatisfied() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PaymentScreenV135.kt").readText()
        assertTrue("remaining == 0L" in source)
        assertTrue("!mixedBlocked" in source)
        assertTrue("!mixedNeedsAcknowledgement || acknowledgedMixedTax" in source)
        assertTrue("!completing" in source)
    }
}
