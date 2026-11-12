package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PaymentCompletionUiTest {
    @Test
    fun paymentAndCompletionExposeCriticalStateInText() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        assertTrue(source.contains("\"残額（支払完了）\""))
        assertTrue(source.contains("\"金額指定なし：支払方法を押すと残額全額を充当\""))
        assertTrue(source.contains("\"お釣り  \${yen(detail?.summary?.changeAmount ?: 0)}\""))
        assertTrue(source.contains("fontSize = if (responsive.isCompact) 28.sp else 36.sp"))
        assertTrue(source.contains("\"次の取引\""))
    }
}
