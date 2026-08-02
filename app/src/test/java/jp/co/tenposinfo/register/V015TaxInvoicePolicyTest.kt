package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V015TaxInvoicePolicyTest {
    private fun item(
        id: String,
        price: Long,
        category: TaxCategory,
        quantity: Int = 1,
        discount: Long = 0,
    ) = CartItem(
        product = Product(id, id, price, category, 1),
        quantity = quantity,
        discountAmount = discount,
    )

    @Test
    fun standardTaxCategoriesAndNonTaxableAreCalculatedAfterDiscount() {
        val rows = listOf(
            item("included10", 1_100, TaxCategory.INCLUDED_10),
            item("excluded10", 1_000, TaxCategory.EXCLUDED_10, discount = 100),
            item("included8", 1_080, TaxCategory.INCLUDED_8),
            item("excluded8", 1_000, TaxCategory.EXCLUDED_8),
            item("nonTax", 500, TaxCategory.NON_TAXABLE),
        )

        val summary = TaxEngine.calculate(rows)

        assertEquals(4_750L, summary.grossAmount)
        assertEquals(350L, summary.taxAmount)
        assertEquals(4_400L, summary.netAmount)
        assertEquals(setOf(10, 8, 0), summary.buckets.map { it.ratePercent }.toSet())
    }

    @Test
    fun externalTaxRoundsOnceAfterRateAggregation() {
        val summary = TaxEngine.calculate(
            listOf(
                item("a", 5, TaxCategory.EXCLUDED_10),
                item("b", 5, TaxCategory.EXCLUDED_10),
            ),
        )

        assertEquals(10L, summary.netAmount)
        assertEquals(1L, summary.taxAmount)
        assertEquals(11L, summary.grossAmount)
    }

    @Test
    fun sameRateIncludedAndExcludedPreservesChargeAndRoundsDisplayedTaxOnce() {
        val summary = TaxEngine.calculate(
            listOf(
                item("course", 6_000, TaxCategory.INCLUDED_10),
                item("single", 157, TaxCategory.EXCLUDED_10, quantity = 10),
            ),
        )

        assertEquals(7_727L, summary.grossAmount)
        assertEquals(702L, summary.taxAmount)
        assertEquals(7_025L, summary.netAmount)
    }

    @Test
    fun mixedTaxPoliciesAllowWarnAndBlockDifferently() {
        val rows = listOf(
            item("included", 1_100, TaxCategory.INCLUDED_10),
            item("excluded", 1_000, TaxCategory.EXCLUDED_10),
        )

        val allow = TaxEngine.validateMixedTax(rows, MixedTaxPolicy.ALLOW)
        val warn = TaxEngine.validateMixedTax(rows, MixedTaxPolicy.WARN)

        assertTrue(allow.hasMixedTax)
        assertEquals(null, allow.message)
        assertTrue(warn.hasMixedTax)
        assertTrue(warn.message!!.contains("10%"))
        val blocked = runCatching { TaxEngine.validateMixedTax(rows, MixedTaxPolicy.BLOCK) }
        assertTrue(blocked.isFailure)
    }

    @Test
    fun receiptContainsIssuerRateBreakdownAndTaxSymbols() {
        val previous = TaxInvoiceSettingsRegistry.current()
        try {
            TaxInvoiceSettingsRegistry.replace(
                TaxInvoiceSettings(
                    issuer = InvoiceIssuerProfile(
                        storeName = "つぐ食堂",
                        address = "東京都千代田区1-1",
                        phone = "03-1234-5678",
                        registrationNumber = "T1234567890123",
                    ),
                ),
            )
            val rows = listOf(
                item("normal", 1_100, TaxCategory.INCLUDED_10),
                item("reduced", 1_000, TaxCategory.EXCLUDED_8),
                item("free", 300, TaxCategory.NON_TAXABLE),
            )
            val receipt = ReceiptRenderer.render(
                ReceiptFactory.fromCurrentSale(
                    saleId = 1,
                    createdAt = 0,
                    operatorName = "担当者",
                    items = rows,
                    payments = listOf(PaymentAllocation(PaymentMethod.CASH, 2_480, 3_000)),
                    changeAmount = 520,
                ),
                ReceiptPaper.MM80,
            )

            assertTrue(receipt.contains("つぐ食堂"))
            assertTrue(receipt.contains("東京都千代田区1-1"))
            assertTrue(receipt.contains("03-1234-5678"))
            assertTrue(receipt.contains("登録番号 T1234567890123"))
            assertTrue(receipt.contains("10%対象額（税込）"))
            assertTrue(receipt.contains("8%対象額（税込）"))
            assertTrue(receipt.contains("消費税等"))
            assertTrue(receipt.contains("内"))
            assertTrue(receipt.contains("外※"))
            assertTrue(receipt.contains("非"))
        } finally {
            TaxInvoiceSettingsRegistry.replace(previous)
        }
    }

    @Test
    fun blankRegistrationNumberIsNotPrinted() {
        val data = ReceiptData(
            storeName = "店舗",
            registrationNumber = "",
            saleId = 1,
            createdAt = 0,
            operatorName = "担当",
            items = listOf(item("free", 100, TaxCategory.NON_TAXABLE)),
            taxSummary = TaxEngine.calculate(listOf(item("free", 100, TaxCategory.NON_TAXABLE))),
            payments = listOf(PaymentAllocation(PaymentMethod.CASH, 100, 100)),
            changeAmount = 0,
        )

        assertFalse(ReceiptRenderer.render(data, ReceiptPaper.MM58).contains("登録番号"))
    }
}
