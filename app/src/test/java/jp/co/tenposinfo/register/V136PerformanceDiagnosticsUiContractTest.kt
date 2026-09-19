package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PerformanceDiagnosticsUiContractTest {
    @Test
    fun diagnosticsAreReachableFromAuthenticatedPrinterTools() {
        val hub = File("src/main/java/jp/co/tenposinfo/register/PrinterToolsHubActivity.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/PerformanceDiagnosticsActivityV136.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(hub.contains("PerformanceDiagnosticsActivityV136::class.java"))
        assertTrue(hub.contains("\"性能診断\""))
        assertTrue(activity.contains("PerformanceDiagnosticsV136.capture(context)"))
        assertTrue(activity.contains("\"再計測\""))
        assertTrue(activity.contains("processPssBytes"))
        assertTrue(activity.contains("storageAvailableBytes"))
        assertTrue(manifest.contains("android:name=\".PerformanceDiagnosticsActivityV136\""))
        assertTrue(manifest.contains("android:screenOrientation=\"landscape\""))
    }
}
