package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V138CrashReportContractTest {
    @Test
    fun crashReportIsInstalledAndPrivacySafe() {
        val source = File("src/main/java/jp/co/tenposinfo/register/plus/CrashReportV138.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".PlusApplicationV138\""))
        assertTrue(source.contains("CrashReportRuntimeV138.install(this)"))
        assertTrue(source.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(source.contains("SCREEN_RESUMED:"))
        assertTrue(source.contains("appVersionName"))
        assertTrue(source.contains("manufacturer"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(source.contains("Intent.ACTION_SEND"))
        assertTrue(source.contains("Intent.EXTRA_TEXT"))
        assertTrue(source.contains("NO_EXCEPTION_MESSAGE_NO_USER_INPUT_NO_ACCOUNT_OR_TRANSACTION_DATA"))
        assertFalse(source.contains("error.message"))
        assertFalse(source.contains("current.message"))
        assertFalse(source.contains("Settings.Secure.ANDROID_ID"))
        assertFalse(source.contains("Build.SERIAL"))
    }
}
