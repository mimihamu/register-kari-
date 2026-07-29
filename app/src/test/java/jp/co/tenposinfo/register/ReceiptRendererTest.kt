package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptRendererTest {
    private val item = CartItem(
        product = Product("P1", "弁当", 1000, TaxCategory.EXCLUDED_8, 1),
        quantity = 2,
        discountAmount = 100,
    )

    private val data = ReceiptData(
        storeName = "テスト店舗",
        registrationNumber = "T1234567890123",
        saleId = 15,
        createdAt = 0,
        operatorName = "山田",
        items = listOf(item),
        taxSummary = TaxEngine.calculate(listOf(item)),
        payments = listOf(PaymentAllocation(PaymentMethod.CASH, 2052, 3000)),
        changeAmount = 948,
    )

    @Test
    fun rendererIncludesTaxSymbolAndInvoiceRegistrationNumber() {
        val text = ReceiptRenderer.render(data, ReceiptPaper.MM58)

        assertTrue(text.contains("外※"))
        assertTrue(text.contains("T1234567890123"))
        assertTrue(text.contains("お釣り"))
        assertTrue(text.contains("軽減税率"))
    }

    @Test
    fun widthsProduceDifferentLayoutsFromSameStructuredData() {
        val narrow = ReceiptRenderer.render(data, ReceiptPaper.MM58)
        val wide = ReceiptRenderer.render(data, ReceiptPaper.MM80)

        assertFalse(narrow == wide)
        assertTrue(wide.lines().first().length >= narrow.lines().first().length)
    }

    @Test
    fun escPosPayloadStartsWithInitializeAndEndsWithCut() {
        val payload = EscPosEncoder.encode(data, ReceiptPaper.MM80)

        assertArrayEquals(byteArrayOf(0x1B, 0x40), payload.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x1D, 0x56, 0x42, 0x00), payload.copyOfRange(payload.size - 4, payload.size))
    }

    @Test
    fun memoryPrinterKeepsExactPayload() {
        val gateway = MemoryPrinterGateway()
        val payload = EscPosEncoder.encode(data, ReceiptPaper.MM58)

        gateway.send(payload).getOrThrow()

        assertArrayEquals(payload, gateway.sentPayloads.single())
    }
}
