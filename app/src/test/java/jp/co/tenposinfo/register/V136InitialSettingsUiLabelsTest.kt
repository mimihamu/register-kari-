package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136InitialSettingsUiLabelsTest {
    @Test
    fun operatorFacingChoiceButtonsUseJapaneseLabelsInsteadOfEnumNames() {
        val source = File("src/main/java/jp/co/tenposinfo/register/InitialReleaseSettingsV135.kt").readText()
        assertTrue(source.contains("settingsDisplayNameV136"))
        assertTrue(source.contains("QuantityInputModeV135.QUANTITY_THEN_ITEM -> \"数量→商品\""))
        assertTrue(source.contains("SettingPermissionPolicyV135.MANAGER -> \"責任者のみ\""))
        assertTrue(source.contains("RestoreWorkCartPolicyV135.ASK -> \"確認する\""))
        assertTrue(source.contains("BusinessDateModeV135.CONFIRM -> \"確認して決定\""))
        assertTrue(source.contains("ReceiptHeaderModeV135.BOTH -> \"文字＋画像\""))
        assertTrue(source.contains("Text(value.settingsDisplayNameV136())"))
        assertFalse(source.contains("}) { Text(value.name) }"))
    }
}
