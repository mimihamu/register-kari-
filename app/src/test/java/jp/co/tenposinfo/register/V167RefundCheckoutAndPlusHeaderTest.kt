package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V167RefundCheckoutAndPlusHeaderTest {
    @Test fun fixedRefundSummaryAndAdaptiveScrollableOptions() {
        val src = File("src/main/java/jp/co/tenposinfo/register/ManualReturnActivityV135.kt").readText()
        val modal = src.substring(src.indexOf("    if (showCheckout) {"))
        val amount = modal.indexOf("Text(\"返金額  -\"")
        val scrolling = modal.indexOf(".verticalScroll(rememberScrollState())")
        val methods = modal.indexOf("Text(\"返金方法\"")
        assertTrue(amount >= 0 && scrolling > amount && methods > scrolling)
        assertTrue(modal.contains(".coerceIn(100, 260)"))
        assertTrue(modal.contains("ManualRefundMethodV135.entries.chunked(2)"))
        assertTrue(modal.contains("containerColor = if (refundMethod == method) Color(0xFFFFEBEE) else Color.White"))
        assertTrue(modal.contains("coordinator.create(request, managerPin, checkoutRequestId)"))
        assertTrue(modal.contains("submissionGate.tryStart()"))
    }

    @Test fun plusBackControlDoesNotCoverTheNavyHeaderWithWhiteBackground() {
        val src = File("../management-app/src/main/java/jp/co/tenposinfo/register/plus/PlusTopBarV155.kt").readText()
        assertTrue(src.contains(".background(PlusNavyV148)"))
        assertTrue(src.contains("border = BorderStroke(1.dp, Color.White)"))
        assertTrue(src.contains("containerColor = Color.Transparent"))
        assertTrue(src.contains("contentColor = Color.White"))
    }
}
