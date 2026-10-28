package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V157CompactSystemSettingsTest {
    private fun source(file: String) =
        File("src/main/java/jp/co/tenposinfo/register", file).readText()

    @Test fun notificationSettingsAreScrollableAndPreserveSafetyControls() {
        val s = source("PrinterNotificationSettingsActivity.kt")
        assertTrue(s.contains("RegisterTopBarV141(title = \"プリンター通知・試験\", onBack = onClose)"))
        assertTrue(s.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(s.contains("Modifier.widthIn(max = 760.dp).fillMaxWidth()"))
        assertTrue(s.contains("Modifier.weight(1f).heightIn(min = 52.dp)"))
        assertFalse(s.contains("Modifier.width(760.dp)"))
        assertFalse(s.contains("Modifier.width(360.dp)"))
        assertTrue(s.contains("permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)"))
        assertTrue(s.contains("売上保存、印刷キュー、FAILEDの再送制御には影響しません"))
        assertTrue(s.contains("開始前にロール紙と排紙口を確認してください"))
    }

    @Test fun taxPaymentHubUsesOperationalNavigationWithoutUnusedIds() {
        val s = source("TaxPaymentSettingsHubActivity.kt")
        assertTrue(s.contains("RegisterTopBarV141(title = \"税・支払設定\", onBack = onClose)"))
        assertTrue(s.contains("onClick = onTax"))
        assertTrue(s.contains("onClick = onPayment"))
        assertFalse(s.contains("SCR-630A"))
        assertFalse(s.contains("SCR-630B"))
        assertFalse(s.contains("screenId: String"))
    }
}
