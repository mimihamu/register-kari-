package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class V058ReceiptVoucherFoundationTest {
    @Test
    fun banquetBatchUsesUnitAmountTimesCopiesWithoutExceedingSale() {
        val request = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 10,
            unitAmount = 4_000,
            copies = 30,
            addressee = "株式会社テスト",
            purpose = "ご飲食代",
            operatorName = "担当A",
        )
        val plan = ReceiptVoucherPolicy.plan(
            request,
            ReceiptVoucherAvailability(saleTotal = 120_000, allocatedAmount = 0),
        )
        assertEquals(120_000L, plan.totalAmount)
        assertEquals(30, plan.copies)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialReceiptsCannotExceedRemainingSaleAmount() {
        ReceiptVoucherPolicy.plan(
            ReceiptVoucherBatchRequest(
                requestId = UUID.randomUUID().toString(),
                saleId = 1,
                unitAmount = 4_000,
                copies = 2,
                addressee = "テスト",
                purpose = "飲食代",
                operatorName = "担当",
            ),
            ReceiptVoucherAvailability(saleTotal = 10_000, allocatedAmount = 3_000),
        )
    }

    @Test
    fun rendererReferencesOriginalReceiptInsteadOfInventingPartialTaxAllocation() {
        val text = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = 12,
                saleId = 99,
                sequenceNo = 1,
                sequenceCount = 30,
                amount = 4_000,
                addressee = "株式会社テスト",
                purpose = "ご飲食代",
                operatorName = "担当A",
                issuedAt = 1_700_000_000_000,
                issuer = InvoiceIssuerProfile(
                    storeName = "つぐ食堂",
                    registrationNumber = "T1234567890123",
                ),
            ),
            ReceiptPaper.MM80,
        )
        assertTrue(text.contains("【領収書】"))
        assertTrue(text.contains("元売上No.99"))
        assertTrue(text.contains("一括発行 1/30"))
        assertTrue(text.contains("税率別の取引内容・消費税額等は元売上レシート"))
        assertTrue(text.contains("登録番号 T1234567890123"))
        assertFalse(text.contains("【再発行】"))
    }

    @Test
    fun reprintIsVisiblyMarked() {
        val text = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = 1,
                saleId = 2,
                sequenceNo = 1,
                sequenceCount = 1,
                amount = 5_000,
                addressee = "テスト株式会社 御中",
                purpose = "飲食代",
                operatorName = "担当A",
                issuedAt = 1_700_000_000_000,
                issuer = InvoiceIssuerProfile(storeName = "つぐ食堂"),
                reprintedAt = 1_700_000_100_000,
                reprintedBy = "責任者B",
            ),
            ReceiptPaper.MM58,
        )
        assertTrue(text.contains("【再発行】"))
        assertTrue(text.contains("再発行担当 責任者B"))
    }

    @Test
    fun sourceUsesImmutableHistoryAndExistingUnifiedDocumentQueue() {
        val root = File("..")
        val receipt = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val advanced = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        val unified = File("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.58_RECEIPT_VOUCHER_FOUNDATION.md").readText()

        assertTrue(receipt.contains("CREATE TABLE IF NOT EXISTS receipt_voucher_batches"))
        assertTrue(receipt.contains("request_id TEXT NOT NULL UNIQUE"))
        assertTrue(receipt.contains("CREATE TABLE IF NOT EXISTS receipt_voucher_issuances"))
        assertTrue(receipt.contains("CREATE TABLE IF NOT EXISTS receipt_voucher_reprints"))
        assertTrue(receipt.contains("OperationDocumentType.RECEIPT_VOUCHER.name"))
        assertTrue(receipt.contains("db.beginTransaction()"))
        assertTrue(receipt.contains("existingBatchResult"))
        assertTrue(advanced.contains("RECEIPT_VOUCHER(\"領収書\")"))
        assertTrue(unified.contains("RECEIPT_VOUCHER(\"領収書\")"))
        assertTrue(unified.contains("UnifiedPrintTypeFilter.RECEIPT"))
        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains("V058ReceiptVoucherFoundationTest.kt"))
        assertTrue(workflow.contains(":app:assembleDebug"))
        assertTrue(docs.contains("4,000円 × 30枚"))
        assertTrue(docs.contains("元売上レシート"))
    }
}
