package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136UnicodeDocumentRendererTest {
    @Test
    fun receiptVoucherUsesSharedUnicodeWidthPolicy() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val renderer = source.substringAfter("internal object ReceiptVoucherRenderer").substringBefore("internal class ReceiptVoucherStore")
        assertTrue(renderer.contains("ReceiptLineWrapV136.wrap(value, width)"))
        assertTrue(renderer.contains("ReceiptLineWrapV136.displayWidth(value)"))
        assertFalse(renderer.contains("value.forEach { char"))
        assertFalse(renderer.contains("value.sumOf { if (it.code <= 0xFF)"))
    }

    @Test
    fun provisionalReceiptUsesSharedUnicodeWidthPolicy() {
        val source = File("src/main/java/jp/co/tenposinfo/register/HeldTicketProvisionalPrintV135.kt").readText()
        val renderer = source.substringAfter("internal object HeldTicketProvisionalReceiptRendererV135").substringBefore("internal class HeldTicketProvisionalPrintServiceV135")
        assertTrue(renderer.contains("ReceiptLineWrapV136.wrap(value, width)"))
        assertTrue(renderer.contains("ReceiptLineWrapV136.displayWidth(value)"))
        assertFalse(renderer.contains("for (char in value)"))
        assertFalse(renderer.contains("value.sumOf { if (it.code <= 0xFF)"))
    }

    @Test
    fun provisionalReceiptWrapsProductNamesAndNotesWithoutDroppingTail() {
        val source = File("src/main/java/jp/co/tenposinfo/register/HeldTicketProvisionalPrintV135.kt").readText()
        val renderer = source.substringAfter("internal object HeldTicketProvisionalReceiptRendererV135").substringBefore("internal class HeldTicketProvisionalPrintServiceV135")
        assertTrue(renderer.contains("lines.addAll(ReceiptLineWrapV136.wrap("))
        assertTrue(renderer.contains("item.product.name"))
        assertTrue(renderer.contains("item.note"))
        assertTrue(renderer.contains("ReceiptLineWrapV136.wrap(label, width).joinToString"))
    }

    @Test
    fun voucherAmountLinePreservesLabelInsteadOfTruncatingIt() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val renderer = source.substringAfter("internal object ReceiptVoucherRenderer").substringBefore("internal class ReceiptVoucherStore")
        assertTrue(renderer.contains("ReceiptLineWrapV136.wrap(label, width).joinToString"))
        assertFalse(renderer.contains("padRight(fit(label, labelWidth), labelWidth)"))
    }


    @Test
    fun reversalAndSettlementUseSharedUnicodeWidthAndNonTruncatingAmounts() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()
        val renderer = source.substringAfter("object OperationDocumentRenderer").substringBefore("object TextEscPosEncoder")
        assertTrue(renderer.contains("ReceiptLineWrapV136.wrap(value, width)"))
        assertTrue(renderer.contains("ReceiptLineWrapV136.displayWidth(value)"))
        assertTrue(renderer.contains("ReceiptLineWrapV136.wrap(label, width).joinToString"))
        assertTrue(renderer.contains("lines.addAll(ReceiptLineWrapV136.wrap("))
        assertFalse(renderer.contains("value.forEach { char"))
        assertFalse(renderer.contains("value.sumOf { if (it.code <= 0xFF)"))
        assertFalse(renderer.contains("padRight(fit(label, labelWidth), labelWidth)"))
    }

}