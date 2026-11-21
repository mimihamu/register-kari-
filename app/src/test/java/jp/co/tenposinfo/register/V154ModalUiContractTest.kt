package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.5 MODAL-001 source regression, including destructive Z settlement safety. */
class V154ModalUiContractTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun commonModalHasExplicitSafeDismissalAndTouchSize() {
        val modal = source("RegisterModalV154.kt")
        assertTrue(modal.contains("DialogProperties("))
        assertTrue(modal.contains("dismissOnBackPress = !destructive"))
        assertTrue(modal.contains("dismissOnClickOutside = !destructive"))
        assertTrue(modal.contains("RegisterTopBarColorV141"))
        assertTrue(modal.contains("RegisterModalCancelV154("))
        assertTrue(modal.contains("RegisterModalConfirmV154("))
        assertTrue(modal.contains("Modifier.heightIn(min = 48.dp)"))
        assertTrue(modal.contains("if (destructive) Color(0xFFB42318)"))
        assertFalse(modal.contains("FocusRequester"))
        assertFalse(modal.contains(".requestFocus("))
    }

    @Test fun bothSettlementRoutesRequireExplicitDestructiveConfirmation() {
        for (name in listOf("SettlementActivityV030.kt", "OperationsActivity.kt")) {
            val code = source(name)
            assertTrue(name, code.contains("RegisterModalV154("))
            assertTrue(name, code.contains("destructive = true"))
            assertTrue(name, code.contains("RegisterModalConfirmV154("))
            assertTrue(name, code.contains("RegisterModalCancelV154("))
            assertTrue(name, code.contains("Z精算して営業終了"))
            assertTrue(name, code.contains("preflight") || code.contains("zPreflight"))
            assertTrue(name, code.contains("ManagerApprovalV145.canUseCurrentManagerSession"))
        }
    }

    @Test fun inventoryLookupAndPrinterErrorUseOneCommonModalLayout() {
        val sales = source("MainActivity.kt")
        val lookup = source("SalesProductSearchDialogV135.kt")
        val barcode = source("UnregisteredBarcodeDialogV136.kt")
        for (src in listOf(sales, lookup, barcode)) {
            assertTrue(src.contains("RegisterModalV154("))
            assertTrue(src.contains("RegisterModalCancelV154("))
        }
        assertTrue(sales.contains("onPrinterStatus()"))
        assertTrue(lookup.contains("ProductLookupPolicyV135.findExact"))
        assertTrue(lookup.contains("RegisterModalConfirmV154("))
        assertTrue(barcode.contains("TemporaryBarcodeProductPolicyV136.create("))
        assertTrue(barcode.contains("RegisterModalConfirmV154("))
        assertTrue(barcode.contains("onOpenProductSettings()"))
    }
}
