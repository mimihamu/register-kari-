package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buttons remain usable with 200% font and compact landscape dimensions. */
class V174SalesFooterResponsiveTest {
    @Test fun textScaleSelectsTwoRowsBeforeButtonsBecomeUnreadable() {
        assertTrue(SalesActionBarLayoutPolicyV174.useTwoRows(800))
        assertTrue(SalesActionBarLayoutPolicyV174.useTwoRows(1059))
        assertFalse(SalesActionBarLayoutPolicyV174.useTwoRows(1060))
        assertTrue(SalesActionBarLayoutPolicyV174.useTwoRows(1100, 1.2f))
        assertFalse(SalesActionBarLayoutPolicyV174.useTwoRows(1200, 1.2f))
        assertTrue(SalesActionBarLayoutPolicyV174.useTwoRows(1399, 1.4f))
        assertFalse(SalesActionBarLayoutPolicyV174.useTwoRows(1400, 1.4f))
        assertTrue(SalesActionBarLayoutPolicyV174.twoRowButtonHeightDp() >=
            SalesActionBarLayoutPolicyV174.MIN_TAP_DP)
    }

    @Test fun footerRetainsDrawerButtonAndLargeCheckoutWithoutDisplacement() {
        val s = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        assertTrue(s.contains("SalesActionBarLayoutPolicyV174.useTwoRows("))
        assertTrue(s.contains("actionConfig.fontScale"))
        assertTrue(s.contains("Modifier.fillMaxWidth().height(122.dp)"))
        assertTrue(s.contains("Modifier.weight(2f).fillMaxHeight()"))
        assertTrue(s.contains("Modifier.weight(2.8f).fillMaxHeight()"))
        assertTrue(s.contains("Text(\"両替\", maxLines = 1)"))
        assertTrue(s.contains("enabled = !exchangeDrawerInProgress"))
    }
}
