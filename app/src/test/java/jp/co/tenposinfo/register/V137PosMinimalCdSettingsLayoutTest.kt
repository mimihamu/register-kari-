package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137PosMinimalCdSettingsLayoutTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplaySettingsActivity.kt").readText()
        .substringAfter("private fun CustomerDisplaySettingsScreen(")

    @Test fun importantOperationsRemainVisibleWithoutMultipleExplanations() {
        for (name in listOf(
            "Text(\"顧客表示\"", "ペアリング受付を開始（2分）",
            "Text(\"表示デザイン\"", "Text(\"保存\")",
        )) assertTrue(name, source.contains(name))
        assertFalse(source.contains("顧客表示は任意機能です。OFFでも販売・会計は継続します。"))
        assertFalse(source.contains("自動探索できないネットワークでは"))
        assertFalse(source.contains("現在の接続状態"))
        assertFalse(source.contains("手動で接続する場合"))
    }

    @Test fun seldomUsedControlsAreUnderDisclosureWithoutRemovingTokenReset() {
        assertTrue(source.contains("if (showDisplayDetails) {"))
        assertTrue(source.contains("if (showConnectionDetails) {"))
        assertTrue(source.contains("Text(\"トークン再発行\")"))
        assertTrue(source.contains("Text(\"URLコピー\")"))
        assertTrue(source.contains("Text(\"税記号\")"))
        assertTrue(source.contains("Text(\"お釣り (秒)\")"))
        assertTrue(source.contains("CustomerDisplayRuntime.applySettings(context, config)"))
    }

    @Test fun statusAndErrorsArePreserved() {
        assertTrue(source.contains("runtimeStatus.connectedClients"))
        assertTrue(source.contains("runtimeStatus.lastError"))
        assertTrue(source.contains("pairingSecondsRemaining"))
        assertTrue(source.contains("buildConfig(enabled)"))
    }
}
