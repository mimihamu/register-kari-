package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptNumberDisplayTest {
    @Test
    fun receiptNumberUsesSixDigitMinimumWithoutTruncation() {
        assertEquals("000001", ReceiptNumberV136.format(1L))
        assertEquals("000123", ReceiptNumberV136.format(123L))
        assertEquals("123456", ReceiptNumberV136.format(123_456L))
        assertEquals("1234567", ReceiptNumberV136.format(1_234_567L))
    }

    @Test
    fun normalAndReprintKeepSamePersistedSaleNumberOnBothWidths() {
        val original = receiptData(saleId = 123L, reprint = false)
        val reprint = original.copy(reprint = true)

        listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
            val normalText = ReceiptRenderer.render(original, paper)
            val reprintText = ReceiptRenderer.render(reprint, paper)

            assertTrue(normalText.contains("No.000123"))
            assertTrue(reprintText.contains("No.000123"))
            assertFalse(normalText.contains("【再発行】"))
            assertTrue(reprintText.contains("【再発行】"))
        }
    }

    @Test
    fun receiptFactoryAndRendererUseSaleIdAsSingleNumberSource() {
        val source = File("src/main/java/jp/co/tenposinfo/register/Receipt.kt").readText()
        assertTrue(source.contains("saleId = detail.summary.id"))
        assertTrue(source.contains("ReceiptNumberV136.format(data.saleId)"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeReceiptNumberIsRejected() {
        ReceiptNumberV136.format(-1L)
    }

    private fun receiptData(saleId: Long, reprint: Boolean): ReceiptData = ReceiptData(
        storeName = "つぐレジ店",
        registrationNumber = "T1234567890123",
        saleId = saleId,
        createdAt = 0L,
        operatorName = "担当",
        items = emptyList(),
        taxSummary = TaxSummary(emptyList()),
        payments = emptyList(),
        changeAmount = 0L,
        reprint = reprint,
    )
}
