package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V138CrashReportContractTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")

    @Test
    fun uncaughtCrashIsPersistedWithoutPersonalPayloadAndCanBeSharedAfterRestart() {
        val source = File(root, "CrashReportV138.kt").readText()
        assertTrue(source.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(source.contains("SCREEN_RESUMED:"))
        assertTrue(source.contains("appVersionName"))
        assertTrue(source.contains("manufacturer"))
        assertTrue(source.contains("causeChain"))
        assertTrue(source.contains("FileOutputStream(temporary)"))
        assertTrue(source.contains("stream.fd.sync()"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(source.contains("Intent.ACTION_SEND"))
        assertTrue(source.contains("Intent.EXTRA_TEXT"))
        assertTrue(source.contains("NO_EXCEPTION_MESSAGE_NO_USER_INPUT_NO_ACCOUNT_OR_TRANSACTION_DATA"))
        assertFalse(source.contains("error.message"))
        assertFalse(source.contains("current.message"))
        assertFalse(source.contains("Settings.Secure.ANDROID_ID"))
        assertFalse(source.contains("Build.SERIAL"))
    }

    @Test
    fun applicationInstallsCrashHandlerBeforeNormalSchedulers() {
        val app = File(root, "RegisterApplication.kt").readText()
        val crashAt = app.indexOf("CrashReportRuntimeV138.install(this)")
        val printerAt = app.indexOf("PrinterConfigurationRegistry.reload(this)")
        assertTrue(crashAt >= 0)
        assertTrue(printerAt > crashAt)
    }
}
