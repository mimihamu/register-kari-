package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V158RegisterMinusSalesFlowTest {
    private val dir = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(dir, name).readText()

    @Test fun salesScreenShowsRedMinusAndNoWhiteActionBar() {
        val s = source("MainActivity.kt")
        assertTrue(s.contains("onRegisterMinus = if (currentOperator?.allows(RegisterPermission.REVERSAL) == true)"))
        assertTrue(s.contains("onClick = onRegisterMinus"))
        assertTrue(s.contains("minusMode: Boolean = false"))
        assertTrue(s.contains("backgroundColor = if (minusMode) Danger else Navy"))
        assertTrue(s.contains("containerColor = Color.Transparent"))
        assertTrue(s.contains("Text(\"レジマイナス\", maxLines = 1)"))
        assertTrue(s.contains("if (minusMode) \"-\" else \"\""))
        assertTrue(s.contains("会計（レジマイナス）"))
        assertTrue(s.contains("enabled = cart.isNotEmpty() && !minusMode"))
        assertFalse(s.contains(".height(48.dp).background(Color.White)"))
    }

    @Test fun manualMinusIsSeparateFromNormalPosSalesAndRequiresApproval() {
        val s = source("ManualReturnActivityV135.kt")
        val policy = source("ManualReturnV135.kt")
        assertTrue(s.contains("mutableStateListOf<CartItem>()"))
        assertTrue(s.contains("BackHandler(enabled = cart.isNotEmpty()"))
        assertTrue(s.contains("ManagerApprovalV145.canUseCurrentManagerSession"))
        assertTrue(s.contains("ManualReturnPolicyV135.toPositiveCartItems(request, reasonRequired = false)"))
        assertTrue(s.contains("coordinator.create(request, managerPin, checkoutRequestId)"))
        assertTrue(s.contains("submissionGate.tryStart()"))
        assertTrue(s.contains("refundMethod != ManualRefundMethodV135.CASH"))
        assertTrue(policy.contains("ManualReturnPolicyV135.signedQuantityHundredths"))
        assertTrue(policy.contains("signedGross"))
        assertFalse(s.contains("database.saveSale("))
    }

    @Test fun minusCountIsIndependentOfSaleCountAndReceiptUsesMinusName() {
        val hub = source("OperationsHubActivityV030.kt")
        val renderer = source("ManualReturnV135.kt")
        assertTrue(hub.contains("ManualReturnStoreV135(context.applicationContext)"))
        assertTrue(hub.contains("it.accountingContribution(session.id).returnCount"))
        assertTrue(hub.contains("HubAmountV030(\"レジマイナス\", \"-\${minusCount}件\")"))
        assertTrue(renderer.contains("appendLine(\"      レジマイナス\")"))
        assertTrue(renderer.contains("appendLine(\"レジマイナスNo. MR-\$manualReturnId\")"))
    }

    @Test fun commonTopBarSupportsRedModeWithoutChangingDefault() {
        val s = source("RegisterTopBarV141.kt")
        assertTrue(s.contains("backgroundColor: Color = RegisterTopBarColorV141"))
        assertTrue(s.contains(".background(backgroundColor)"))
        assertTrue(s.contains("topBarColor: Color = RegisterTopBarColorV141"))
    }
}
