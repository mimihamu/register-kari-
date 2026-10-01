package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptPrintEmphasisTest {
    @Test
    fun formalReceiptLinesAreEmphasizedButArbitraryProductTextIsNotPolicyInput() {
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("領収書／レシート"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("合計       ￥1,100"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("お釣り       ￥100"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("【再発行】"))
        assertFalse(ReceiptPrintEmphasisV136.shouldEmphasize("商品 合計セット"))
    }

    @Test
    fun encodedReceiptUsesBoldCommandsWithoutTrustingEmbeddedTextCommands() {
        val item = CartItem(Product("P1", "商品", 1_100L, TaxCategory.INCLUDED_10, 1), 1)
        val data = ReceiptData(
            storeName = "店",
            registrationNumber = "",
            saleId = 1L,
            createdAt = 0L,
            operatorName = "担当",
            items = listOf(item),
            taxSummary = TaxEngine.calculate(listOf(item)),
            payments = emptyList(),
            changeAmount = 0L,
        )
        val payload = EscPosEncoder.encode(data, PrinterConfiguration(paperWidthMm = 58))
        val boldOn = byteArrayOf(0x1B, 0x45, 0x01)
        val boldOff = byteArrayOf(0x1B, 0x45, 0x00)
        assertTrue(payload.containsSequence(boldOn))
        assertTrue(payload.containsSequence(boldOff))
    }

    private fun ByteArray.containsSequence(target: ByteArray): Boolean =
        indices.any { start ->
            start + target.size <= size &&
                target.indices.all { offset -> this[start + offset] == target[offset] }
        }
}
