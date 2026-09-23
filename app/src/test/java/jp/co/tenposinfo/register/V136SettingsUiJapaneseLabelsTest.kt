package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136SettingsUiJapaneseLabelsTest {
    @Test
    fun backupFileStatesExposeJapaneseDisplayNames() {
        assertEquals("検証中", AutoBackupFileState.VERIFYING.displayName)
        assertEquals("利用可能", AutoBackupFileState.READY.displayName)
        assertEquals("破損", AutoBackupFileState.CORRUPT.displayName)
    }

    @Test
    fun operatorFacingSettingsCopyDoesNotExposeInternalStateNames() {
        val dataProtection = File("src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt").readText()
        val initialSettings = File("src/main/java/jp/co/tenposinfo/register/InitialReleaseSettingsV135.kt").readText()

        assertTrue(dataProtection.contains("metadata.state.displayName"))
        assertFalse(dataProtection.contains("状態: ${metadata.state.name}"))

        assertTrue(initialSettings.contains("未会計伝票: 禁止"))
        assertFalse(initialSettings.contains("未会計伝票: BLOCK"))
        assertTrue(initialSettings.contains("精算の安全条件は、この設定画面から弱めることはできません。"))
        assertFalse(initialSettings.contains("REP-003で確定済みの安全条件"))
        assertTrue(initialSettings.contains("端末管理者（Device Owner）"))
    }
}
