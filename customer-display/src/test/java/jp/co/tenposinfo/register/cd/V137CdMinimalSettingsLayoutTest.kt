package jp.co.tenposinfo.register.cd

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CdMinimalSettingsLayoutTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt").readText()
        .substringAfter("private fun CustomerDisplayConnectionSettingsScreen(")
        .substringBefore("private fun LogoBadge(")

    @Test fun discoveryIsPrimaryAndManualConnectionIsProgressiveDisclosure() {
        assertTrue(source.contains("Text(if (manualExpanded)"))
        assertTrue(source.contains("if (manualExpanded) {"))
        assertTrue(source.contains("else \"レジを探す\""))
        assertTrue(source.contains("onSave(candidate.toConnectionSettings())"))
        assertTrue(source.contains("Text(\"保存して接続\")"))
    }

    @Test fun essentialInputsAndErrorFeedbackRemainWithoutRedundantExplanations() {
        for (label in listOf("レジIP", "ポート", "トークン", "自動接続")) {
            assertTrue(source.contains("\"$label\""))
        }
        assertTrue(source.contains("CustomerDisplayPrivateLanHostPolicy.isPermitted(host)"))
        assertFalse(source.contains("通信パス："))
        assertFalse(source.contains("同じWi-Fiのつぐレジを探すか、接続情報を手入力します"))
    }

    @Test fun bothCompactAndWideLayoutsRetainConnectionFlow() {
        assertTrue(source.contains("if (compact) {"))
        assertTrue(source.contains("Modifier.weight(2f)"))
        assertTrue(source.contains("Modifier.fillMaxWidth()"))
    }
}
