package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Receipt80mmLayoutTest {
    @Test
    fun eightyMillimeterUses48LogicalColumnsAndPreservesLongName() {
        val name = "特選海鮮盛り合わせ本まぐろ中とろ北海道産うにいくら入り大サイズ限定商品"
        val item = CartItem(
            product = Product(
                id = "LONG-080",
                name = name,
                unitPrice = 9_876_543L,
                taxCategory = TaxCategory.EXCLUDED_8,
                displayOrder = 1,
            ),
            quantity = 3,
        )

        val rendered = ReceiptRenderer.render(receiptData(listOf(item)), ReceiptPaper.MM80)
        val compact = rendered.replace("\n", "")
        val amountLine = rendered.lineSequence().first { it.contains("29,629,629") }

        assertEquals(48, ReceiptPaper.MM80.charsPerLine)
        assertTrue(compact.contains("$name [外※]"))
        assertTrue(amountLine.trimEnd().endsWith("29,629,629"))
        rendered.lineSequence().forEach { line ->
            assertTrue("80mm line exceeds 48 columns: $line", ReceiptLineWrapV136.displayWidth(line) <= 48)
        }
    }

    @Test
    fun sameStructuredReceiptKeepsBusinessContentAcross58And80Millimeter() {
        val items = listOf(
            CartItem(
                product = Product(
                    id = "A",
                    name = "長い商品名でも紙幅だけで内容を変えない確認商品A",
                    unitPrice = 1_100L,
                    taxCategory = TaxCategory.INCLUDED_10,
                    displayOrder = 1,
                ),
                quantity = 2,
                discountAmount = 100L,
            ),
            CartItem(
                product = Product(
                    id = "B",
                    name = "軽減税率確認商品B",
                    unitPrice = 1_000L,
                    taxCategory = TaxCategory.EXCLUDED_8,
                    displayOrder = 2,
                ),
                quantity = 1,
            ),
        )
        val data = receiptData(items).copy(
            payments = listOf(
                PaymentAllocation(PaymentMethod.CASH, appliedAmount = 2_000L, receivedAmount = 3_000L),
                PaymentAllocation(PaymentMethod.CARD, appliedAmount = 1_180L, receivedAmount = 1_180L),
            ),
            changeAmount = 1_000L,
        )

        val text58 = ReceiptRenderer.render(data, ReceiptPaper.MM58)
        val text80 = ReceiptRenderer.render(data, ReceiptPaper.MM80)
        val compact58 = text58.replace("\n", "")
        val compact80 = text80.replace("\n", "")

        listOf(
            "長い商品名でも紙幅だけで内容を変えない確認商品A [内]",
            "軽減税率確認商品B [外※]",
            "2,100",
            "1,000",
            "3,180",
            "現金",
            "カード",
            "お釣り",
        ).forEach { token ->
            assertTrue("58mm missing $token", compact58.contains(token))
            assertTrue("80mm missing $token", compact80.contains(token))
        }

        text58.lineSequence().forEach { assertTrue(ReceiptLineWrapV136.displayWidth(it) <= 32) }
        text80.lineSequence().forEach { assertTrue(ReceiptLineWrapV136.displayWidth(it) <= 48) }
    }

    @Test
    fun eightyMillimeterWrapPreservesAllCharactersAt48Columns() {
        val source = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789あいうえおかきくけこ [内※]"
        val lines = ReceiptLineWrapV136.wrap(source, ReceiptPaper.MM80.charsPerLine)

        assertEquals(source, lines.joinToString(""))
        lines.forEach { assertTrue(ReceiptLineWrapV136.displayWidth(it) <= 48) }
    }

    private fun receiptData(items: List<CartItem>): ReceiptData = ReceiptData(
        storeName = "つぐレジ店",
        registrationNumber = "T1234567890123",
        saleId = 80L,
        createdAt = 0L,
        operatorName = "担当",
        items = items,
        taxSummary = TaxEngine.calculate(items),
        payments = emptyList(),
        changeAmount = 0L,
    )
}
