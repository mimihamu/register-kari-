package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Receipt58mmLayoutTest {
    @Test
    fun longProductNameWrapsWithoutLossAt32Columns() {
        val name = "特選海鮮盛り合わせ本まぐろ中とろ北海道産うに入り大サイズ"
        val product = Product(
            id = "LONG-001",
            name = name,
            unitPrice = 12_345_678L,
            taxCategory = TaxCategory.INCLUDED_8,
            displayOrder = 1,
        )
        val item = CartItem(product = product, quantity = 2)
        val data = receiptData(item)

        val rendered = ReceiptRenderer.render(data, ReceiptPaper.MM58)
        val compact = rendered.replace("\n", "")
        val amountLine = rendered.lineSequence().first { it.contains("24,691,356") }

        assertTrue(compact.contains("$name [内※]"))
        assertTrue(amountLine.trimEnd().endsWith("24,691,356"))
        assertTrue(ReceiptLineWrapV136.displayWidth(amountLine) <= 32)
        rendered.lineSequence().forEach { line ->
            assertTrue("58mm line exceeds 32 columns: $line", ReceiptLineWrapV136.displayWidth(line) <= 32)
        }
    }

    @Test
    fun wrappingPreservesEveryCharacterAndTaxSuffix() {
        val source = "あいうえおかきくけこさしすせそたちつてと [外※]"
        val lines = ReceiptLineWrapV136.wrap(source, 32)

        assertEquals(source, lines.joinToString(""))
        assertTrue(lines.size >= 2)
        lines.forEach { assertTrue(ReceiptLineWrapV136.displayWidth(it) <= 32) }
    }

    @Test
    fun quantityUnitPriceAndAmountRemainSeparateFromProductNameOn58mm() {
        val product = Product(
            id = "ITEM-001",
            name = "生ビール",
            unitPrice = 550L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        )
        val item = CartItem(product = product, quantity = 2)
        val rendered = ReceiptRenderer.render(receiptData(item), ReceiptPaper.MM58).lines()
        val nameIndex = rendered.indexOfFirst { it.contains("生ビール [内]") }
        val amountIndex = rendered.indexOfFirst { it.contains("2 ×") && it.contains("1,100") }

        assertTrue(nameIndex >= 0)
        assertEquals(nameIndex + 1, amountIndex)
        assertTrue(ReceiptLineWrapV136.displayWidth(rendered[amountIndex]) <= 32)
    }

    private fun receiptData(item: CartItem): ReceiptData = ReceiptData(
        storeName = "つぐレジ店",
        registrationNumber = "",
        saleId = 1L,
        createdAt = 0L,
        operatorName = "担当",
        items = listOf(item),
        taxSummary = TaxEngine.calculate(listOf(item)),
        payments = emptyList(),
        changeAmount = 0L,
    )
}
