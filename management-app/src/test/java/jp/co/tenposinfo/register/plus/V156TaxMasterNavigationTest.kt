package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.56: the tax master shares fixed navigation and explicit editor cancellation. */
class V156TaxMasterNavigationTest {
    @Test fun taxMasterUsesSharedBarAndPinnedList() {
        val s = File("src/main/java/jp/co/tenposinfo/register/plus/TaxCategoryMasterActivityV135.kt").readText()
        assertTrue(s.contains("PlusTopBarV155(title = \"税区分マスター\", onBack = onBack)"))
        assertTrue(s.contains("Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)"))
        assertFalse(s.contains("TextButton(onClick = onBack) { Text(\"戻る\") }"))
        assertTrue(s.contains("onSave = { candidate ->"))
        assertTrue(s.contains("store.appendRevision(candidate)"))
        assertTrue(s.contains("dismissOnBackPress = false"))
        assertTrue(s.contains("dismissOnClickOutside = false"))
        assertTrue(s.contains("TextButton(onClick = onDismiss) { Text(\"キャンセル\") }"))
    }
}
