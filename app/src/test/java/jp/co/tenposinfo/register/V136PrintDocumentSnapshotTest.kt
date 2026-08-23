package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V136PrintDocumentSnapshotTest {
    @Test
    fun saleElectronicJournalKeepsLegacyTaxFieldsAndStructuredPrintableSnapshot() {
        val product = Product(
            id = "P-\"1",
            name = "弁当\n特上",
            unitPrice = 1_100L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        )
        val items = listOf(CartItem(product = product, quantity = 2, discountAmount = 100L, note = "持帰り"))
        val taxSummary = TaxEngine.calculate(items)
        val payments = listOf(PaymentAllocation(PaymentMethod.CASH, taxSummary.grossAmount, 3_000L))
        val payload = PrintDocumentSnapshotV136.enrichSalePayload(
            basePayloadJson = "{\"saleId\":123,\"sameRateMixedModePolicy\":\"BLOCK\",\"taxRoundUnit\":\"1YEN\"}",
            saleId = 123L,
            businessDate = "2026-08-23",
            issuedAt = 123456789L,
            operatorName = "担当A",
            items = items,
            taxSummary = taxSummary,
            payments = payments,
            changeAmount = 3_000L - taxSummary.grossAmount,
            settings = TaxInvoiceSettings(
                mixedTaxPolicy = MixedTaxPolicy.BLOCK,
                issuer = InvoiceIssuerProfile(
                    storeName = "店舗A",
                    address = "東京都",
                    phone = "03-0000-0000",
                    registrationNumber = "T1234567890123",
                ),
            ),
        )

        assertTrue(payload.startsWith("{"))
        assertTrue(payload.endsWith("}"))
        assertTrue(payload.contains("\"saleId\":123"))
        assertTrue(payload.contains("\"sameRateMixedModePolicy\":\"BLOCK\""))
        assertTrue(payload.contains("\"printDocument\":"))
        assertTrue(payload.contains("\"documentType\":\"SALES_RECEIPT\""))
        assertTrue(payload.contains("\"businessDate\":\"2026-08-23\""))
        assertTrue(payload.contains("\"lines\":["))
        assertTrue(payload.contains("\"productCode\":\"P-\\\"1\""))
        assertTrue(payload.contains("\"productName\":\"弁当\\n特上\""))
        assertTrue(payload.contains("\"receiptTaxSymbolSnapshot\":\"内\""))
        assertTrue(payload.contains("\"invoiceTaxes\":["))
        assertTrue(payload.contains("\"tenders\":["))
        assertTrue(payload.contains("\"registrationNo\":\"T1234567890123\""))
    }

    @Test
    fun renderedHashUsesSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PrintDocumentSnapshotV136.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun retentionContractIsAtLeastTwoYearsForJournalAndOneYearForPrintErrors() {
        assertTrue(PrintJournalRetentionPolicyV136.ELECTRONIC_JOURNAL_MIN_DAYS >= 730)
        assertTrue(PrintJournalRetentionPolicyV136.PRINT_ERROR_DETAIL_MIN_DAYS >= 365)
        assertFalse(PrintJournalRetentionPolicyV136.ORDINARY_CLEANUP_ALLOWED)
    }

    @Test
    fun saleCommitPersistsStructuredSnapshotInsideSaleTransaction() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val enrich = source.indexOf("SaleTaxSnapshotStoreV136.enrichSaleJournal(this, saleId)")
        val snapshot = source.indexOf("PrintDocumentSnapshotV136.persistSaleSnapshot(")
        val cartDelete = source.indexOf("// 売上確定と作業中カート消去を同一トランザクションに含める。")

        assertTrue(enrich >= 0)
        assertTrue(snapshot > enrich)
        assertTrue(cartDelete > snapshot)
        assertTrue(source.contains("PrintDocumentSnapshotSchemaV136.ensureSale(db)"))
    }

    @Test
    fun allDocumentPrintInsertsAreCapturedByStructuredJournalTriggerAndRenderedHashIsRecordedBeforeSend() {
        val schema = File("src/main/java/jp/co/tenposinfo/register/PrintDocumentSnapshotV136.kt").readText()
        val advanced = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        val receipt = File("src/main/java/jp/co/tenposinfo/register/Receipt.kt").readText()

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS print_document_journal_v136"))
        assertTrue(schema.contains("AFTER INSERT ON document_print_jobs"))
        assertTrue(schema.contains("NEW.payload_text"))
        assertTrue(schema.contains("payload_json"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS print_error_journal_v136"))
        assertTrue(schema.contains("AFTER UPDATE OF last_error ON"))
        assertTrue(advanced.contains("PrintDocumentSnapshotSchemaV136.ensureDocument(db)"))
        assertTrue(advanced.contains("table = \"document_print_jobs\""))
        assertTrue(advanced.indexOf("recordRenderedHash(") < advanced.indexOf("gateway.send(renderedPayload)"))
        assertTrue(receipt.contains("table = \"print_jobs\""))
        assertTrue(receipt.indexOf("recordRenderedHash(") < receipt.indexOf("gateway.send(renderedPayload)"))
    }

    @Test
    fun authoritativeJournalAndPrintErrorTablesHaveNoOrdinaryDeletePath() {
        val production = File("src/main/java/jp/co/tenposinfo/register")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(Regex("(?i)DELETE\\s+FROM\\s+sales_journal").containsMatchIn(production))
        assertFalse(Regex("(?i)DELETE\\s+FROM\\s+print_document_journal_v136").containsMatchIn(production))
        assertFalse(Regex("(?i)DELETE\\s+FROM\\s+print_error_journal_v136").containsMatchIn(production))
        assertFalse(production.contains("delete(\"sales_journal\""))
        assertFalse(production.contains("delete(\"print_document_journal_v136\""))
        assertFalse(production.contains("delete(\"print_error_journal_v136\""))
    }
}
