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
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("【返品レシート】"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("【取消レシート】"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("【Z精算票】"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("【X点検票】"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("【再印字】"))
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("返金合計    -￥1,100"))
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

    @Test
    fun operationDocumentEncoderUsesSameSafeEmphasisCommands() {
        val configuration = PrinterConfiguration(paperWidthMm = 58)
        val payload = TextEscPosEncoder.encode("【返品レシート】\n返金合計 -￥1,100", configuration)
        assertTrue(payload.containsSequence(byteArrayOf(0x1B, 0x45, 0x01)))
        assertTrue(payload.containsSequence(byteArrayOf(0x1B, 0x45, 0x00)))
    }


    @Test
    fun emphasisFallsBackWhenLineExceedsPaperWidth() {
        assertTrue(ReceiptPrintEmphasisV136.shouldEmphasize("合計 ￥1,100", ReceiptPaper.MM58))
        assertFalse(
            ReceiptPrintEmphasisV136.shouldEmphasize(
                "合計 " + "X".repeat(40),
                ReceiptPaper.MM58,
            ),
        )
    }

}