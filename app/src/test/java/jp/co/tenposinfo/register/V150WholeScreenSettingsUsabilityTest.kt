package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for dark sales toolbar and predictable, progressively disclosed settings. */
class V150WholeScreenSettingsUsabilityTest {
    private val dir = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(dir, name).readText()

    @Test fun salesHasOneNavyToolbarAndNoWhiteSecondActionBar() {
        val sales = src("MainActivity.kt")
        val start = sales.indexOf("RegisterTopBarV141(title = \"販売\"")
        assertTrue(start > 0)
        val toolbar = sales.substring(start).take(2_700)
        assertTrue(toolbar.contains("onClick = onOpenManagement"))
        assertTrue(toolbar.contains("onClick = onLogout"))
        assertTrue(toolbar.contains("onClick = onOpenSettings"))
        assertTrue(toolbar.contains("containerColor = Color.White"))
        assertFalse(toolbar.contains(".height(48.dp).background(Color.White)"))
        assertFalse(toolbar.contains("サンプル居酒屋"))
        val chrome = src("RegisterTopBarV141.kt")
        assertTrue(chrome.contains(".background(RegisterTopBarColorV141)"))
    }

    @Test fun receiptShowsBasicOrAdvancedWithoutLosingEditorsOrSave() {
        val receipt = src("ReceiptSettingsActivity.kt")
        assertTrue(receipt.contains("var showDetails by remember"))
        assertTrue(receipt.contains("if (!showDetails) Card("))
        assertTrue(receipt.contains("if (showDetails) Card("))
        assertTrue(receipt.contains("DocumentPrintSettingsPanelV136"))
        assertTrue(receipt.contains("ReceiptTextStampSettingsPanelV136"))
        assertTrue(receipt.contains("ReceiptStampSettingsPanelV136"))
        assertTrue(receipt.contains("store.savePrinterConfiguration("))
        assertTrue(receipt.contains("layoutStore.save("))
    }

    @Test fun storeSalesBusinessDeviceSettingsKeepAdvancedAndSave() {
        val initial = src("InitialReleaseSettingsV135.kt")
        assertTrue(initial.contains("private fun SettingDetailsToggleV150("))
        assertTrue(initial.contains("var showAdvanced by remember"))
        assertTrue(initial.contains("Text(item.label)"))
        for (token in listOf(
            "store.saveStore(value, actor)",
            "store.saveSales(value, actor)",
            "store.saveBusiness(value, actor)",
            "store.saveDevice(value, actor)",
            "EnumCycleRowV135(\"単価上書き\"",
            "NumericSettingV135(\"空き容量警告しきい値",
            "BoolRowV135(\"キオスク運用を要求\"",
        )) assertTrue(token, initial.contains(token))
    }

    @Test fun paymentAndStaffSettingsKeepAdvancedRightsAndPersistence() {
        val payment = src("PaymentSettingsActivity.kt")
        assertTrue(payment.contains("var expandedMethod by remember"))
        assertTrue(payment.contains("if (expandedMethod == row.method) {"))
        assertTrue(payment.contains("store.save("))
        assertTrue(payment.contains("PaymentTerminalAdapterV136.NONE"))
        val admin = src("AdminSettingsActivity.kt")
        assertTrue(admin.contains("var showPermissionsV150 by remember"))
        assertTrue(admin.contains("if (!showPermissionsV150) {"))
        assertTrue(admin.contains("if (showPermissionsV150) AsPanel("))
        assertTrue(admin.contains("store.saveOperator("))
        assertTrue(admin.contains("permission.displayName"))
        assertTrue(admin.contains("var showRoutesV150 by remember"))
        assertTrue(admin.contains("if (showRoutesV150) {"))
        assertTrue(admin.contains("profileStore.setDefault("))
        assertFalse(admin.contains("Text(\"適用内容\", fontSize = 24.sp"))
    }

    @Test fun printerDiagnosticScreensUseSharedUpperLeftBackAndRespectBusy() {
        val printerFiles = listOf(
            "PrinterStatusActivity.kt",
            "PrinterStatusProbeActivity.kt",
            "PrinterSoakTestActivity.kt",
            "PrinterNotificationSettingsActivity.kt",
        )
        for (name in printerFiles) {
            assertTrue(name, src(name).contains("RegisterTopBarV141(title = "))
            assertTrue(name, src(name).contains("onBack = "))
        }
        assertTrue(src("PrinterSoakTestActivity.kt").contains("if (!running) onClose()"))
        assertTrue(src("PrinterStatusProbeActivity.kt").contains("if (!running) onClose()"))
    }
}
