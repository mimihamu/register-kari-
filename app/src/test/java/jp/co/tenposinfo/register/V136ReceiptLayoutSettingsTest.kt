package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptLayoutSettingsTest {
    private fun sampleData(layout: ReceiptLayoutSettingsV136): ReceiptData {
        val product = Product(
            id = "P-001",
            name = "テスト商品",
            unitPrice = 1_100L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        )
        val item = CartItem(product = product, quantity = 1)
        return ReceiptData(
            storeName = "テスト店舗",
            storeAddress = "東京都テスト1-2-3",
            storePhone = "03-1234-5678",
            registrationNumber = "",
            saleId = 123L,
            createdAt = 0L,
            operatorName = "担当A",
            items = listOf(item),
            taxSummary = TaxEngine.calculate(listOf(item)),
            payments = listOf(PaymentAllocation(PaymentMethod.CASH, 1_100L, 1_100L)),
            changeAmount = 0L,
            layoutSettings = layout,
        )
    }

    @Test
    fun formalDefaultsMatchV25() {
        val settings = ReceiptLayoutSettingsV136()
        assertEquals(1L, settings.defaultPrinterId)
        assertTrue(settings.showLogo)
        assertTrue(settings.showAddress)
        assertTrue(settings.showPhone)
        assertTrue(settings.showOperator)
        assertFalse(settings.showProductCode)
        assertEquals(ReceiptTaxDisplayModeV136.DETAIL, settings.taxDisplayMode)
        assertEquals(ReceiptReprintAuthV136.SELLER, settings.reprintAuth)
        assertEquals(ReceiptQrModeV136.NONE, settings.qrMode)
    }

    @Test
    fun rendererHonorsAddressPhoneOperatorAndProductCodeVisibility() {
        val hidden = ReceiptRenderer.render(
            sampleData(
                ReceiptLayoutSettingsV136(
                    showAddress = false,
                    showPhone = false,
                    showOperator = false,
                    showProductCode = false,
                ),
            ),
            ReceiptPaper.MM80,
        )
        assertFalse(hidden.contains("東京都テスト1-2-3"))
        assertFalse(hidden.contains("03-1234-5678"))
        assertFalse(hidden.contains("担当A"))
        assertFalse(hidden.contains("P-001"))

        val shown = ReceiptRenderer.render(
            sampleData(
                ReceiptLayoutSettingsV136(
                    showAddress = true,
                    showPhone = true,
                    showOperator = true,
                    showProductCode = true,
                ),
            ),
            ReceiptPaper.MM80,
        )
        assertTrue(shown.contains("東京都テスト1-2-3"))
        assertTrue(shown.contains("03-1234-5678"))
        assertTrue(shown.contains("担当 担当A"))
        assertTrue(shown.contains("商品コード P-001"))
    }

    @Test
    fun qualifiedIssuerForcesDetailedTaxMode() {
        assertEquals(
            ReceiptTaxDisplayModeV136.MINIMUM,
            ReceiptLayoutSettingsPolicyV136.effectiveTaxDisplayMode(
                ReceiptTaxDisplayModeV136.MINIMUM,
                "",
            ),
        )
        assertEquals(
            ReceiptTaxDisplayModeV136.DETAIL,
            ReceiptLayoutSettingsPolicyV136.effectiveTaxDisplayMode(
                ReceiptTaxDisplayModeV136.MINIMUM,
                "T1234567890123",
            ),
        )
    }

    @Test
    fun saleSnapshotAndStampCaptureFreezeVisibilitySettings() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val database = File(root, "RegisterDatabase.kt").readText()
        val frozen = File(root, "Syn003FrozenPrintPayloadV136.kt").readText()
        val stamp = File(root, "ReceiptStampSnapshotV136.kt").readText()

        assertTrue(database.contains("ReceiptLayoutSettingsStoreV136(applicationContext).load()"))
        assertTrue(database.contains("receiptLayoutSettings = receiptLayoutSettings"))
        assertTrue(frozen.contains("\"receiptLayoutConfigSnapshot\""))
        assertTrue(frozen.contains("\"showProductCode\""))
        assertTrue(frozen.contains("layoutSettings = receiptLayoutSettings.copy"))
        assertTrue(stamp.contains("if (layoutSettings.showLogo) imageStore.printPrefix(paper) else ByteArray(0)"))
        assertTrue(stamp.contains("ReceiptTextStampFieldV136.ADDRESS -> layoutSettings.showAddress"))
        assertTrue(stamp.contains("ReceiptTextStampFieldV136.PHONE -> layoutSettings.showPhone"))
    }

    @Test
    fun scr640AndReprintScreenExposeAndEnforceFormalControls() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val settingsUi = File(root, "ReceiptSettingsActivity.kt").readText()
        val reprint = File(root, "SaleReceiptReprintActivity.kt").readText()

        listOf(
            "画像ロゴを使用",
            "住所を印字",
            "電話番号を印字",
            "担当者を印字",
            "商品コードを印字",
            "再印字権限",
            "表示・再印字設定を保存",
        ).forEach { label ->
            assertTrue("missing SCR-640 label: $label", settingsUi.contains(label))
        }

        assertTrue(reprint.contains("reprintAuth == ReceiptReprintAuthV136.SELLER || current.isManager"))
        assertTrue(reprint.contains("再印字には責任者権限が必要です"))
        assertTrue(reprint.contains("enabled = reprintAllowed"))
    }
}
