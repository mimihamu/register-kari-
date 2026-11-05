package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136AdminSettingsUiLabelsTest {
    @Test
    fun permissionListShowsJapaneseDisplayNamesWithoutInternalEnumCodes() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt").readText()
        assertTrue(source.contains("Text(permission.displayName, fontWeight = FontWeight.SemiBold)"))
        assertFalse(source.contains("Text(permission.name"))
    }
}
