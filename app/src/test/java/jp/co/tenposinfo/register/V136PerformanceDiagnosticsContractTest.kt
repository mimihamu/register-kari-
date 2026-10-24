package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PerformanceDiagnosticsContractTest {
    @Test
    fun diagnosticsUsesAndroid12AwareMemoryEstimateWithFallback() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PerformanceDiagnosticsV136.kt").readText()
        assertTrue(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(source.contains("Debug.getPss()"))
        assertTrue(source.contains("ActivityManager.MemoryInfo()"))
        assertTrue(source.contains("Runtime.getRuntime()"))
        assertTrue(source.contains("StatFs"))
        assertTrue(source.contains("取得不可"))
    }

    @Test
    fun snapshotSeparatesProcessDeviceAndStorageValues() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PerformanceDiagnosticsV136.kt").readText()
        assertTrue(source.contains("processPssBytes"))
        assertTrue(source.contains("deviceAvailableBytes"))
        assertTrue(source.contains("deviceTotalBytes"))
        assertTrue(source.contains("storageAvailableBytes"))
        assertTrue(source.contains("storageTotalBytes"))
        assertTrue(source.contains("memorySource"))
    }
}
