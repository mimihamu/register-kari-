package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135HeldTicketProvisionalPrintTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun provisionalRendererPrintsGuestCountAndNeverClaimsFinalization() {
        val product = Product("P1", "テスト商品", 1_100L, TaxCategory.INCLUDED_10, 1)
        val ticket = HeldTicket(
            id = 10L,
            name = "宴会A",
            operatorName = "担当A",
            createdAt = 1_700_000_000_000L,
            itemCount = 1,
            totalAmount = 1_100L,
            guestCount = 6,
        )
        val rendered = HeldTicketProvisionalReceiptRendererV135.render(
            ticket,
            listOf(CartItem(product = product, quantity = 1)),
            ReceiptPaper.MM80,
        )
        assertTrue(rendered.contains("【仮締め票】"))
        assertTrue(rendered.contains("客数 6名"))
        assertTrue(rendered.contains("合計"))
        assertTrue(rendered.contains("売上確定ではありません"))
        assertFalse(rendered.contains("領収書／レシート"))
    }

    @Test
    fun provisionalSlipUsesDedicatedDocumentTypeAndUnifiedSafeQueue() {
        val service = source("HeldTicketProvisionalPrintV135.kt")
        val queue = source("UnifiedPrintQueue.kt")
        val worker = source("AutomaticPrintWorker.kt")
        assertTrue(service.contains("OperationDocumentType.HELD_TICKET_PROVISIONAL"))
        assertTrue(service.contains("document_print_jobs"))
        assertTrue(service.contains("HELD_TICKET_PROVISIONAL_PRINT_QUEUED"))
        assertTrue(queue.contains("UnifiedPrintJobType.HELD_TICKET_PROVISIONAL"))
        assertTrue(worker.contains("operations.processDocumentPrint"))
        assertFalse(service.contains("saveSale("))
    }

    @Test
    fun ticketListOffersProvisionalPrintAction() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("onPrint: (HeldTicket) -> Unit"))
        assertTrue(main.contains("HeldTicketProvisionalPrintServiceV135"))
        assertTrue(main.contains("Text(\"仮締め\""))
    }
}
