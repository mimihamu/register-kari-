package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V174DrawerFeedbackModalTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(root, name).readText()

    @Test fun successShowsBriefFeedbackWithoutTakingOverTheTopBar() {
        assertNull(SalesDrawerFeedbackPolicyV174.fromResult(Result.success(true)))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("SalesDrawerFeedbackPolicyV174.fromResult(result)"))
        assertTrue(main.contains("android.widget.Toast.makeText("))
        assertFalse(main.contains("accessMessage = result.fold("))
        assertTrue(main.contains("onDismissDrawerFeedback = { drawerFeedback = null }"))
    }

    @Test fun preflightRejectionIsNotMisreportedAsUncertainTransport() {
        val notDispatched = SalesDrawerFeedbackPolicyV174.fromResult(Result.success(false))
        assertTrue(notDispatched != null && !notDispatched.uncertain)
        val rejected = SalesDrawerFeedbackPolicyV174.fromResult(
            Result.failure(SalesExchangePreflightExceptionV174("ドロア設定なし")),
        )
        assertTrue(rejected != null && !rejected.uncertain)
        assertTrue(rejected!!.detail.contains("ドロア設定なし"))
    }

    @Test fun uncertainSendWarnsAboutPotentiallyOpenedDrawer() {
        val report = SalesDrawerFeedbackPolicyV174.fromResult(
            Result.failure(java.io.IOException("送信後に切断")),
        )
        assertTrue(report != null && report.uncertain)
        assertTrue(report!!.detail.contains("既にドロアが開いている可能性"))
        assertTrue(report.detail.contains("必要な場合のみ再操作"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("RegisterModalV154("))
        assertTrue(main.contains("drawerFeedback.title"))
        assertTrue(main.contains("drawerFeedback.detail"))
        assertTrue(main.contains("RegisterModalConfirmV154(label = \"プリンター状態\""))
        assertTrue(main.contains("if (drawerFeedback == null &&"))
        assertTrue(src("SalesExchangeDrawerV172.kt").contains("CashDrawerRuntimeV136.dispatch("))
        assertFalse(src("SalesExchangeDrawerV172.kt").contains("dispatchAsync("))
    }
}
