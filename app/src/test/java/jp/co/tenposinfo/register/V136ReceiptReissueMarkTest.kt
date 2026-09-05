package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptReissueMarkTest {
    private fun source(name: String) = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun reissueMarkIsAbsoluteFirstAndLastLineOnBothPaperWidths() {
        val item = CartItem(
            product = Product(
                id = "RCP004",
                name = "再発行確認商品",
                unitPrice = 1_100L,
                taxCategory = TaxCategory.INCLUDED_10,
                displayOrder = 1,
            ),
            quantity = 1,
        )
        val base = ReceiptData(
            storeName = "つぐレジ店",
            registrationNumber = "T1234567890123",
            saleId = 123L,
            createdAt = 0L,
            operatorName = "担当者",
            items = listOf(item),
            taxSummary = TaxEngine.calculate(listOf(item)),
            payments = emptyList(),
            changeAmount = 0L,
            reprint = true,
            documentHeader = "任意ヘッダ",
            documentFooter = "任意フッタ",
        )

        listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
            val lines = ReceiptRenderer.render(base, paper).lines()
            assertTrue(lines.first().contains("【再発行】"))
            assertTrue(lines.last().contains("【再発行】"))
            assertEquals(2, lines.count { it.contains("【再発行】") })
            assertTrue(lines[1].contains("任意ヘッダ"))
        }
    }

    @Test
    fun ordinaryReceiptHasNoReissueMark() {
        val data = ReceiptData(
            storeName = "つぐレジ店",
            registrationNumber = "",
            saleId = 1L,
            createdAt = 0L,
            operatorName = "担当者",
            items = emptyList(),
            taxSummary = TaxEngine.calculate(emptyList()),
            payments = emptyList(),
            changeAmount = 0L,
            reprint = false,
        )
        assertFalse(ReceiptRenderer.render(data, ReceiptPaper.MM58).contains("【再発行】"))
    }

    @Test
    fun reissueQueueRegistrationRecordsCountActorAndTimestampAudit() {
        val database = source("RegisterDatabase.kt")
        val main = source("MainActivity.kt")
        val schema = source("OperationAuditSchemaV136.kt")

        assertTrue(database.contains("fun enqueueReprint(saleId: Long, actor: String = \"SYSTEM\")"))
        assertTrue(database.contains("OperationAuditSchemaV136.ensure(this)"))
        assertTrue(database.contains("SALE_RECEIPT_REPRINT_ENQUEUED"))
        assertTrue(database.contains("再発行回数=${'$'}{previousReprintCount + 1}"))
        assertTrue(database.contains("put(\"operator_name\", normalizedActor)"))
        assertTrue(database.contains("put(\"created_at\", now)"))
        assertTrue(main.contains("database.enqueueReprint(detail.summary.id, operatorName)"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS operation_audit"))

        val retryBlock = database.substringAfter("fun retryPrintJob").substringBefore("fun discardPrintJob")
        assertFalse(retryBlock.contains("SALE_RECEIPT_REPRINT_ENQUEUED"))
    }
}
