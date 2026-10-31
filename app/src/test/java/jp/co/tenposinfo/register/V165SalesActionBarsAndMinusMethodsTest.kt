package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header and footer backgrounds must remain visible in normal / minus sales modes. */
class V165SalesActionBarsAndMinusMethodsTest {
    private fun src(path: String) = File("src/main/java/jp/co/tenposinfo/register", path).readText()

    @Test fun allSalesAndCheckoutActionRowsHaveBackgrounds() {
        val src = src("MainActivity.kt")
        assertTrue(src.contains("backgroundColor = if (minusMode) Danger else Navy"))
        assertTrue(src.contains("Modifier.fillMaxWidth().height(responsive.bottomBarHeightDp.dp)\n                    .background(Color(0xFFFFCDD2))"))
        assertTrue(src.contains("Modifier.fillMaxWidth().height(122.dp)\n                    .background(RegisterSecondaryActionBarColorV159)"))
        assertTrue(src.contains("Modifier.fillMaxWidth().height(responsive.bottomBarHeightDp.dp)\n                    .background(RegisterSecondaryActionBarColorV159)"))
        assertTrue(src.contains(".height(responsive.bottomBarHeightDp.dp)\n            .background(RegisterSecondaryActionBarColorV159)"))
    }

    @Test fun minusRefundMethodsFitSmallLandscapeAndPreserveLedger() {
        val minus = src("ManualReturnActivityV135.kt")
        assertTrue(minus.contains("ManualRefundMethodV135.entries.chunked(2)"))
        assertTrue(minus.contains("modifier = Modifier.weight(1f).heightIn(min = 48.dp)"))
        assertTrue(minus.contains("coordinator.create(request, managerPin, checkoutRequestId)"))
        assertTrue(minus.contains("RegisterSafeAreaV141(topBarColor = Color(0xFFB42318))"))
        assertTrue(minus.contains("window.statusBarColor = android.graphics.Color.rgb(180, 35, 24)"))
        assertTrue(src("OperationsHubActivityV030.kt").contains("HubAmountV030(\"レジマイナス\""))
    }
}
