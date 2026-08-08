package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V057HeldTicketOperationsUiTest {
    private fun item(id: String, quantity: Int): CartItem = CartItem(
        product = Product(
            id = id,
            name = "商品$id",
            unitPrice = 1_000L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        ),
        quantity = quantity,
    )

    @Test
    fun splitValidationRejectsEmptyOverQuantityAndFullMove() {
        val items = listOf(item("A", 2), item("B", 1))

        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("0", "0"), "分割先").canConfirm)
        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("3", "0"), "分割先").canConfirm)
        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("2", "1"), "分割先").canConfirm)
        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("1", "0"), "   ").canConfirm)
    }

    @Test
    fun splitValidationProducesIndexedMoveMapAndRemainingCount() {
        val validation = HeldTicketOperationsUiPolicy.validateSplit(
            items = listOf(item("A", 3), item("B", 2)),
            rawQuantities = listOf("1", "2"),
            rawName = "分割先",
        )

        assertTrue(validation.canConfirm)
        assertEquals(mapOf(0 to 1, 1 to 2), validation.movedQuantities)
        assertEquals(3, validation.movedCount)
        assertEquals(2, validation.remainingCount)
    }

    @Test
    fun ticketListAndSplitScreenWireAtomicV056Engine() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val uiPolicy = File("src/main/java/jp/co/tenposinfo/register/HeldTicketOperationsUi.kt").readText()
        val engine = File("src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.57_HELD_TICKET_OPERATIONS_UI.md").readText()

        assertTrue(main.contains("TICKET_SPLIT"))
        assertTrue(main.contains("onMerge: (HeldTicket, HeldTicket) -> Unit"))
        assertTrue(main.contains("onSplit: (HeldTicket) -> Unit"))
        assertTrue(main.contains("結合元として選択中"))
        assertTrue(main.contains("結合確定"))
        assertTrue(main.contains("この伝票へ結合"))
        assertTrue(main.contains("Header(\"SCR-201\", \"伝票分割\")"))
        assertTrue(main.contains("移動数量"))
        assertTrue(main.contains("分割実行"))
        assertTrue(main.contains("heldTicketCoordinator.merge(source, target)"))
        assertTrue(main.contains("heldTicketCoordinator.split("))
        assertTrue(uiPolicy.contains("元伝票を空にはできません"))
        assertTrue(engine.contains("db.beginTransaction()"))
        assertTrue(build.contains("versionCode = 90"))
        assertTrue(build.contains("versionName = \"0.60.0-dev.1\""))
        assertTrue(workflow.contains("V057HeldTicketOperationsUiTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.60.0_dev1_receipt_voucher_operations_ledger_debug.apk"))
        assertTrue(docs.contains("結合元 → 結合先 → 結合確定"))
    }
}
