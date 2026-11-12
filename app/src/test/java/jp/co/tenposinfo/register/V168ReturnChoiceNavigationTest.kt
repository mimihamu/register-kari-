package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reversal choice is clear and continues to preserve distinct accounting. */
class V168ReturnChoiceNavigationTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(root, name).readText()

    @Test fun managementPairsReversalAndMinusWithoutDuplicate() {
        val screen = src("OperationsHubActivityV030.kt")
        val receipt = screen.indexOf("\"取引指定で返品・取消\"")
        val minus = screen.indexOf("\"レジマイナス\", Color(0xFFFFE9E9)")
        val settlement = screen.indexOf("\"Z精算\", Color(0xFFFFE8E8)")
        assertTrue(receipt >= 0 && minus > receipt && settlement > minus)
        assertEquals(1, screen.split("\"レジマイナス\", Color(0xFFFFE9E9)").size - 1)
        assertTrue(screen.contains("RegisterPermission.REVERSAL in permissions, openManualReturn"))
        assertTrue(screen.contains("openLegacyManagement(OperationsLegacyRouteV140.REVERSAL)"))
        assertTrue(screen.contains("maxLines = 2"))
        assertTrue(screen.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test fun reversalScreenProvidesNoOriginalTransactionRoute() {
        val screen = src("OperationsActivity.kt")
        assertTrue(screen.contains("OpHeader(\"SCR-400/410\", \"元売上から返品・取消\")"))
        assertTrue(screen.contains("Text(\"元取引なし → レジマイナス\""))
        assertTrue(screen.contains("onClick = onManualReturn"))
        assertTrue(screen.contains("context.startActivity(Intent(context, ManualReturnActivityV135::class.java))"))
        assertTrue(screen.contains("onManualReturn: () -> Unit"))
        assertTrue(screen.contains("if (selected == null)"))
    }

    @Test fun distinctLedgerApprovalAndNegativeCountRemain() {
        val minus = src("ManualReturnActivityV135.kt")
        assertTrue(minus.contains("coordinator.create(request, managerPin, checkoutRequestId)"))
        assertTrue(minus.contains("submissionGate.tryStart()"))
        assertTrue(minus.contains("ManualReturnPolicyV135.toPositiveCartItems(request, reasonRequired = false)"))
        val hub = src("OperationsHubActivityV030.kt")
        assertTrue(hub.contains("HubAmountV030(\"レジマイナス\", \"-"))
        assertTrue(hub.contains("minusCount"))
    }
}
