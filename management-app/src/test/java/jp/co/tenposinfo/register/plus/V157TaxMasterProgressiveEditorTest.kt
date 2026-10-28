package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V157TaxMasterProgressiveEditorTest {
    @Test fun advancedInputsRemainPersisted() {
        val s = File("src/main/java/jp/co/tenposinfo/register/plus/TaxCategoryMasterActivityV135.kt").readText()
        assertTrue(s.contains("var showAdvanced by remember(initial)"))
        assertTrue(s.contains("if (showAdvanced) {"))
        assertTrue(s.contains("value = symbol"))
        assertTrue(s.contains("value = effectiveTo"))
        assertTrue(s.contains("onValueChange = { enabled = it }"))
        assertTrue(s.contains("value = effectiveFrom"))
        assertTrue(s.contains("onSave("))
        assertTrue(s.contains("effectiveFromBusinessDate = effectiveFrom"))
        assertTrue(s.contains("effectiveToBusinessDate = effectiveTo"))
        assertTrue(s.contains("symbol = symbol"))
        assertTrue(s.contains("enabled = enabled"))
        assertTrue(s.contains("dismissOnBackPress = false"))
    }
}
