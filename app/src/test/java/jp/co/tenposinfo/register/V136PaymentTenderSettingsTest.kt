package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PaymentTenderSettingsTest {
    @Test
    fun formalTenderDefaultsAreSafeAndCashIsCanonical() {
        val cash = PaymentSettingsStoreV136.defaultSetting(PaymentMethod.CASH)
        val card = PaymentSettingsStoreV136.defaultSetting(PaymentMethod.CARD)
        val gift = PaymentSettingsStoreV136.defaultSetting(PaymentMethod.GIFT_CERTIFICATE)

        assertTrue(cash.enabled)
        assertTrue(cash.allowOverpay)
        assertTrue(cash.givesChange)
        assertTrue(cash.drawerOpen)
        assertEquals("現金", cash.receiptName)

        assertFalse(card.allowOverpay)
        assertFalse(card.givesChange)
        assertFalse(card.drawerOpen)
        assertEquals(PaymentTerminalAdapterV136.NONE, card.gateway)

        assertFalse(gift.allowOverpay)
        assertFalse(gift.givesChange)
        assertEquals("商品券", gift.receiptName)
    }

    @Test
    fun nonCashOverpayAndChangeAreIndependent() {
        val total = 1_000L

        val blocked = PaymentTenderPolicyV136(
            allowOverpaymentWithChange = false,
            allowOverpay = false,
            givesChange = false,
        )
        assertTrue(
            runCatching {
                PaymentEngine.addPayment(
                    PaymentState(),
                    total,
                    PaymentMethod.GIFT_CERTIFICATE,
                    1_200L,
                    blocked,
                )
            }.isFailure,
        )

        val noChange = PaymentTenderPolicyV136(
            allowOverpaymentWithChange = false,
            allowOverpay = true,
            givesChange = false,
        )
        val acceptedNoChange = PaymentEngine.addPayment(
            PaymentState(),
            total,
            PaymentMethod.GIFT_CERTIFICATE,
            1_200L,
            noChange,
        )
        assertEquals(1_000L, acceptedNoChange.allocations.single().appliedAmount)
        assertEquals(1_000L, acceptedNoChange.allocations.single().receivedAmount)
        assertEquals(0L, acceptedNoChange.changeAmount)

        val withChange = PaymentTenderPolicyV136(
            allowOverpaymentWithChange = true,
            allowOverpay = true,
            givesChange = true,
        )
        val acceptedWithChange = PaymentEngine.addPayment(
            PaymentState(),
            total,
            PaymentMethod.GIFT_CERTIFICATE,
            1_200L,
            withChange,
        )
        assertEquals(1_000L, acceptedWithChange.allocations.single().appliedAmount)
        assertEquals(1_200L, acceptedWithChange.allocations.single().receivedAmount)
        assertEquals(200L, acceptedWithChange.changeAmount)
    }

    @Test
    fun paymentSettingsExposeReceiptGatewayAndDrawerContracts() {
        val customGift = PaymentSettingsStoreV136.defaultSetting(PaymentMethod.GIFT_CERTIFICATE).copy(
            receiptName = "御食事券",
            allowOverpay = true,
            givesChange = false,
            allowOverpaymentWithChange = false,
        )
        val customCash = PaymentSettingsStoreV136.defaultSetting(PaymentMethod.CASH).copy(
            drawerOpen = false,
        )
        val settings = PaymentSettingsV136(listOf(customCash, customGift))

        assertEquals("御食事券", settings.receiptNameFor(PaymentMethod.GIFT_CERTIFICATE))
        assertEquals(PaymentTerminalAdapterV136.NONE, settings.gatewayFor(PaymentMethod.GIFT_CERTIFICATE))
        assertFalse(settings.drawerOpenFor(PaymentMethod.CASH))
        assertFalse(settings.drawerOpenFor(PaymentMethod.GIFT_CERTIFICATE))

        val policy = settings.tenderPolicyFor(PaymentMethod.GIFT_CERTIFICATE)
        assertTrue(policy.allowOverpay)
        assertFalse(policy.givesChange)
        assertFalse(policy.allowOverpaymentWithChange)
    }

    @Test
    fun operatorUiAndRuntimeAreWiredToFormalTenderSettings() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val model = File(root, "PaymentSettingsV136.kt").readText()
        val ui = File(root, "PaymentSettingsActivity.kt").readText()
        val receipt = File(root, "Receipt.kt").readText()
        val drawer = File(root, "ReceiptAutoPrintV136.kt").readText()
        val app = File(root, "RegisterApplication.kt").readText()

        listOf("allow_overpay", "gives_change", "gateway", "drawer_open", "receipt_name").forEach {
            assertTrue("missing persisted tender key: $it", model.contains(it))
        }
        assertTrue(model.contains("レシート表示名は1～16文字"))
        assertTrue(model.contains("PaymentSettingsRegistryV136"))

        assertTrue(ui.contains("レシート表示名（1～16文字）"))
        assertTrue(ui.contains("残額を超える支払を許可"))
        assertTrue(ui.contains("超過分を現金の釣銭として返す"))
        assertTrue(ui.contains("会計確定時にドロアを開く"))
        assertTrue(ui.contains("決済端末連携："))

        assertTrue(receipt.contains("PaymentSettingsRegistryV136.current().receiptNameFor(payment.method)"))
        assertTrue(drawer.contains("PaymentSettingsRegistryV136.current().drawerOpenFor(PaymentMethod.CASH)"))
        assertTrue(app.contains("PaymentSettingsRegistryV136.reload(this)"))
    }
}
