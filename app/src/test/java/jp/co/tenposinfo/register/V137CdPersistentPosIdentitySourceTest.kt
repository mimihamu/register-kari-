package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CdPersistentPosIdentitySourceTest {
    @Test
    fun settingsScreenPreservesStoredIdWhileUpdatingConfig() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplaySettingsActivity.kt").readText()
        assertTrue(source.contains("storeId = initial.storeId"))
        assertTrue(source.contains("registerTerminalId = initial.registerTerminalId"))
    }

    @Test
    fun runtimeAlwaysAddsIdForInitialAndChangedSnapshots() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayRuntime.kt").readText()
        assertTrue(source.contains("storeId = config.storeId"))
        assertTrue(source.contains("registerTerminalId = config.registerTerminalId"))
        assertTrue(source.contains("storeId = currentConfig?.storeId"))
        assertTrue(source.contains("registerTerminalId = currentConfig?.registerTerminalId"))
    }
}
