package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.56: toolbar placement and compact-landscape settings regressions. */
class V156SettingsAndNativeReturnLayoutTest {
    private fun source(name: String) =
        File("src/main/java/jp/co/tenposinfo/register", name).readText()

    @Test fun minusScreenReusesSalesLayoutAndIsolatesReturnCart() {
        val screen = source("ManualReturnActivityV135.kt")
        val main = source("MainActivity.kt")
        assertTrue(screen.contains("RegisterSafeAreaV141(topBarColor = Color(0xFFB42318))"))
        assertTrue(screen.contains("SalesScreen("))
        assertTrue(screen.contains("minusMode = true"))
        assertTrue(screen.contains("val cart = remember { mutableStateListOf<CartItem>() }"))
        assertFalse(main.contains("onRegisterMinus = if"))
        assertFalse(main.contains("onClick = onRegisterMinus"))
        assertTrue(source("OperationsHubActivityV030.kt").contains("RegisterPermission.REVERSAL in permissions, openManualReturn"))
        assertTrue(main.contains("if (minusMode) Danger else Navy"))
        assertTrue(screen.contains("ManualReturnRequestV135(lines, reason, refundMethod)"))
        assertFalse(screen.contains("database.saveSale("))
    }

    @Test fun paymentSettingsUseAvailableWidthAndKeepFixedSaveAction() {
        val payment = source("PaymentSettingsActivity.kt")
        assertTrue(payment.contains("RegisterTopBarV141(title = \"支払設定\""))
        assertFalse(payment.contains("Modifier.width(256.dp).fillMaxHeight()"))
        assertTrue(payment.contains("Modifier.weight(1f).fillMaxWidth()"))
        assertTrue(payment.contains("Modifier.width(128.dp).height(54.dp)"))
        assertTrue(payment.contains("if (expandedMethod == row.method)"))
        assertTrue(payment.contains("store.save("))
        assertTrue(payment.contains("PaymentTerminalAdapterV136.NONE"))
        assertTrue(payment.contains("外部端末の決済結果を確認してください"))
    }
}
