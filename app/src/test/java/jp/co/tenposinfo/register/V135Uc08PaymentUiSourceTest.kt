package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Uc08PaymentUiSourceTest {
    @Test
    fun initialReleaseTenderSetIsComplete() {
        assertEquals(
            listOf("現金", "クレジット", "商品券", "掛売", "その他"),
            PaymentMethod.entries.map { it.displayName },
        )
    }

    @Test
    fun paymentScreenExposesEveryInitialReleaseTender() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PaymentScreenV135.kt").readText()
        assertTrue("add(PaymentMethod.CASH)" in source)
        assertTrue("add(PaymentMethod.CARD)" in source)
        assertTrue("add(PaymentMethod.GIFT_CERTIFICATE)" in source)
        assertTrue("add(PaymentMethod.ACCOUNT_RECEIVABLE)" in source)
        assertTrue("add(PaymentMethod.OTHER)" in source)
        assertTrue("\"クレジット\"" in source)
        assertTrue("\"その他\"" in source)
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
