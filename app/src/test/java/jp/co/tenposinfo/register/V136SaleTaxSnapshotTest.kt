package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136SaleTaxSnapshotTest {
    private fun mixedTenPercentItems(
        includedGross: Long = 1_100L,
        excludedNet: Long = 1_000L,
    ): List<CartItem> = listOf(
        CartItem(
            product = Product("IN10", "内税10", includedGross, TaxCategory.INCLUDED_10, 1),
            quantity = 1,
        ),
        CartItem(
            product = Product("OUT10", "外税10", excludedNet, TaxCategory.EXCLUDED_10, 2),
            quantity = 1,
        ),
    )

    @Test
    fun taxIncludedBasisStoresOneRateBucketAndBothSourceModes() {
        val items = mixedTenPercentItems()
        val summary = TaxEngine.calculate(items)
        val snapshot = SaleTaxSnapshotStoreV136.build(
            saleId = 77L,
            items = items,
            summary = summary,
            settings = TaxInvoiceSettings(
                mixedTaxPolicy = MixedTaxPolicy.WARN,
                invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_INCLUDED,
            ),
            recordedAt = 1234L,
        )

        val bucket = snapshot.buckets.single()
        assertEquals(10, bucket.ratePercent)
        assertEquals(1_100L, bucket.includedGrossSourceAmount)
        assertEquals(1_000L, bucket.excludedNetSourceAmount)
        assertEquals(2_200L, bucket.taxableAmount)
        assertEquals(2_000L, bucket.netAmount)
        assertEquals(200L, bucket.taxAmount)
        assertEquals(2_200L, bucket.grossAmount)
        assertEquals(MixedTaxPolicy.WARN, snapshot.sameRateMixedModePolicy)
        assertEquals(SaleTaxSnapshotStoreV136.ROUNDING_MODE_FLOOR, snapshot.taxRoundingMode)
        assertEquals(SaleTaxSnapshotStoreV136.ROUND_UNIT_RATE_PER_INVOICE, snapshot.taxRoundUnit)
    }

    @Test
    fun taxExcludedBasisChangesInvoiceTaxableAmountWithoutChangingTaxOrTotal() {
        val items = mixedTenPercentItems()
        val summary = TaxEngine.calculate(items)
        val included = SaleTaxSnapshotStoreV136.build(
            saleId = 1L,
            items = items,
            summary = summary,
            settings = TaxInvoiceSettings(invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_INCLUDED),
            recordedAt = 1L,
        )
        val excluded = SaleTaxSnapshotStoreV136.build(
            saleId = 2L,
            items = items,
            summary = summary,
            settings = TaxInvoiceSettings(invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_EXCLUDED),
            recordedAt = 2L,
        )

        assertEquals(2_200L, included.buckets.single().taxableAmount)
        assertEquals(2_000L, excluded.buckets.single().taxableAmount)
        assertEquals(included.toTaxSummary().taxAmount, excluded.toTaxSummary().taxAmount)
        assertEquals(included.toTaxSummary().grossAmount, excluded.toTaxSummary().grossAmount)
    }

    @Test
    fun roundingDeltaCapturesUnifiedRateRoundingDifference() {
        val items = mixedTenPercentItems(includedGross = 109L, excludedNet = 9L)
        val summary = TaxEngine.calculate(items)
        val snapshot = SaleTaxSnapshotStoreV136.build(
            saleId = 3L,
            items = items,
            summary = summary,
            settings = TaxInvoiceSettings(
                mixedTaxPolicy = MixedTaxPolicy.ALLOW,
                invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_INCLUDED,
            ),
            recordedAt = 3L,
        )

        assertEquals(1L, snapshot.buckets.single().roundingDelta)
    }

    @Test
    fun snapshotRebuildCsvAndJournalUseSameFixedAmounts() {
        val items = mixedTenPercentItems()
        val snapshot = SaleTaxSnapshotStoreV136.build(
            saleId = 88L,
            items = items,
            summary = TaxEngine.calculate(items),
            settings = TaxInvoiceSettings(invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_EXCLUDED),
            recordedAt = 88L,
        )
        val rebuilt = snapshot.toTaxSummary()
        val csv = SaleTaxSnapshotCsvV136.rows(snapshot).single()
        val journal = SaleTaxSnapshotStoreV136.toJournalPayload(snapshot)

        assertEquals(200L, rebuilt.taxAmount)
        assertEquals(2_200L, rebuilt.grossAmount)
        assertTrue(csv.contains("\"TAX_EXCLUDED\""))
        assertTrue(csv.contains("\"2000\""))
        assertTrue(csv.contains("\"200\""))
        assertTrue(journal.contains("\"invoiceAggregationBasis\":\"TAX_EXCLUDED\""))
        assertTrue(journal.contains("\"taxableAmount\":2000"))
        assertTrue(journal.contains("\"taxAmount\":200"))
    }

    @Test
    fun receiptUsesHistoricalAggregationBasisInsteadOfCurrentAssumption() {
        val items = mixedTenPercentItems()
        val snapshot = SaleTaxSnapshotStoreV136.build(
            saleId = 99L,
            items = items,
            summary = TaxEngine.calculate(items),
            settings = TaxInvoiceSettings(invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_EXCLUDED),
            recordedAt = 0L,
        )
        val receipt = ReceiptData(
            storeName = "テスト店",
            registrationNumber = "",
            saleId = 99L,
            createdAt = 0L,
            operatorName = "担当",
            items = items,
            taxSummary = snapshot.toTaxSummary(),
            payments = emptyList(),
            changeAmount = 0L,
            invoiceAggregationBasis = snapshot.invoiceAggregationBasis,
            reprint = true,
        )

        val text = ReceiptRenderer.render(receipt, ReceiptPaper.MM80)
        assertTrue(text.contains("10%対象額（税抜）"))
        assertFalse(text.contains("10%対象額（税込）"))
        assertTrue(text.contains("【再発行】"))
    }

    @Test
    fun notice001BuildFreezesIssuerAndJournalUsesSaleTimeRegistration() {
        val items = mixedTenPercentItems()
        val issuer = InvoiceIssuerProfile(
            storeName = "売上時店舗",
            address = "埼玉県越谷市1-2-3",
            phone = "048-000-0000",
            registrationNumber = "T1234567890123",
        )
        val snapshot = SaleTaxSnapshotStoreV136.build(
            saleId = 101L,
            items = items,
            summary = TaxEngine.calculate(items),
            settings = TaxInvoiceSettings(
                issuer = issuer,
                invoiceAggregationBasis = InvoiceAggregationBasisV136.TAX_INCLUDED,
            ),
            recordedAt = 101L,
        )

        assertEquals(issuer, snapshot.invoiceIssuer)
        val journal = SaleTaxSnapshotStoreV136.toJournalPayload(snapshot)
        assertTrue(journal.contains("\"storeName\":\"売上時店舗\""))
        assertTrue(journal.contains("\"registrationNumber\":\"T1234567890123\""))
    }

    @Test
    fun notice001ReprintUsesSaleIssuerEvenAfterCurrentSettingsChange() {
        val items = mixedTenPercentItems()
        val previous = TaxInvoiceSettingsRegistry.current()
        val saleIssuer = InvoiceIssuerProfile(
            storeName = "売上時店舗",
            address = "売上時住所",
            phone = "048-111-2222",
            registrationNumber = "T1234567890123",
        )
        val snapshot = SaleTaxSnapshotStoreV136.build(
            saleId = 102L,
            items = items,
            summary = TaxEngine.calculate(items),
            settings = TaxInvoiceSettings(issuer = saleIssuer),
            recordedAt = 102L,
        )
        val detail = SaleDetailRecord(
            summary = SaleSummaryRecord(
                id = 102L,
                operatorName = "担当",
                paymentLabel = "現金",
                totalAmount = snapshot.toTaxSummary().grossAmount,
                taxAmount = snapshot.toTaxSummary().taxAmount,
                changeAmount = 0L,
                createdAt = 0L,
                printCount = 1,
            ),
            items = items,
            payments = emptyList(),
            taxSummary = snapshot.toTaxSummary(),
            invoiceAggregationBasis = snapshot.invoiceAggregationBasis,
        )

        try {
            SaleInvoiceIssuerSnapshotRegistryV136.remember(snapshot)
            TaxInvoiceSettingsRegistry.replace(
                TaxInvoiceSettings(
                    issuer = InvoiceIssuerProfile(
                        storeName = "現在設定店舗",
                        registrationNumber = "T9999999999999",
                    ),
                ),
            )

            val receipt = ReceiptFactory.fromSale(detail, reprint = true)
            assertEquals("売上時店舗", receipt.storeName)
            assertEquals("売上時住所", receipt.storeAddress)
            assertEquals("048-111-2222", receipt.storePhone)
            assertEquals("T1234567890123", receipt.registrationNumber)
            val rendered = ReceiptRenderer.render(receipt, ReceiptPaper.MM80)
            assertTrue(rendered.contains("T1234567890123"))
            assertFalse(rendered.contains("T9999999999999"))
        } finally {
            SaleInvoiceIssuerSnapshotRegistryV136.forget(102L)
            TaxInvoiceSettingsRegistry.replace(previous)
        }
    }
}
