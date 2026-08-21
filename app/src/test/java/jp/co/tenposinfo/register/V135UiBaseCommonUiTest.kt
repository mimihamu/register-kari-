package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135UiBaseCommonUiTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private val mainSource: String by lazy {
        File(root, "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
    }

    private val responsiveSource: String by lazy {
        File(root, "app/src/main/java/jp/co/tenposinfo/register/ResponsiveLayoutV029.kt").readText()
    }

    private fun functionSource(name: String, nextName: String): String {
        val start = mainSource.indexOf("private fun $name(")
        val end = mainSource.indexOf("private fun $nextName(", start + 1)
        assertTrue("$name source not found", start >= 0)
        assertTrue("$nextName source not found", end > start)
        return mainSource.substring(start, end)
    }

    @Test
    fun uiAt01KeepsSalesPaymentAndNextTransactionFlowFreeOfHorizontalScroll() {
        val sales = functionSource("SalesScreen", "LineEditScreen")
        val payment = functionSource("PaymentScreen", "CompleteScreen")
        val complete = functionSource("CompleteScreen", "SalesHistoryScreen")

        assertFalse(sales.contains(".horizontalScroll("))
        assertFalse(payment.contains(".horizontalScroll("))
        assertFalse(complete.contains(".horizontalScroll("))

        assertTrue(sales.contains("Modifier.weight(responsive.salesListWeight).fillMaxHeight()"))
        assertTrue(sales.contains("Modifier.weight(responsive.salesKeypadWeight).fillMaxHeight()"))
        assertTrue(sales.contains("Modifier.weight(responsive.salesProductsWeight).fillMaxHeight()"))
        assertTrue(payment.contains("Modifier.weight(responsive.paymentDetailWeight).fillMaxHeight()"))
        assertTrue(payment.contains("Modifier.weight(responsive.paymentKeypadWeight).fillMaxHeight()"))
        assertTrue(complete.contains("if (responsive.isCompact) Modifier.verticalScroll(compactScroll)"))
        assertTrue(complete.contains("\"次の取引\""))
    }

    @Test
    fun uiAt06FontScale130For1280x800UsesCompactLayout() {
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(
                widthDp = 1280,
                heightDp = 800,
                fontScale = 1.30f,
            ),
        )

        assertTrue(responsiveSource.contains("configuration.fontScale,"))
        assertTrue(responsiveSource.contains("fontScale = configuration.fontScale"))
    }

    @Test
    fun uiAt06SelectedSaleLineIsNotCommunicatedByColorAlone() {
        val sales = functionSource("SalesScreen", "LineEditScreen")
        val selectionStart = sales.indexOf("val selected = selectedIndex == index")
        val selectionEnd = sales.indexOf("if (corrections.isNotEmpty())", selectionStart)
        assertTrue("selected sale line source not found", selectionStart >= 0)
        assertTrue("selected sale line end not found", selectionEnd > selectionStart)
        val selectedLineSource = sales.substring(selectionStart, selectionEnd)

        assertTrue(selectedLineSource.contains(".background(if (selected) PaleBlue else Color.Transparent"))
        assertTrue(selectedLineSource.contains("if (selected) {"))
        assertTrue(selectedLineSource.contains("\"選択中\""))
    }

    @Test
    fun uiAt06PrinterStateHasTextLabelsInAdditionToColor() {
        val banner = functionSource("PrinterHealthBanner", "statusColor")

        assertTrue(banner.contains("PrinterHealthLevel.CHECKING -> \"確認中\""))
        assertTrue(banner.contains("PrinterHealthLevel.READY -> \"正常\""))
        assertTrue(banner.contains("PrinterHealthLevel.WARNING -> \"注意\""))
        assertTrue(banner.contains("PrinterHealthLevel.ERROR -> \"異常\""))
        assertTrue(banner.contains("PrinterHealthLevel.DISABLED -> \"未使用\""))
        assertTrue(banner.contains("PrinterHealthLevel.UNCONFIGURED -> \"未設定\""))
        assertTrue(banner.contains("Text(\"プリンター \$prefix\""))
    }
}
