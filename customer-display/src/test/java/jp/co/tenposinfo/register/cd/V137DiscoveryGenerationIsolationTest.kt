package jp.co.tenposinfo.register.cd

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V137DiscoveryGenerationIsolationTest {
    @Test
    fun callbacksMustCheckCurrentScanBeforeUpdatingFoundItemsOrState() {
        val source = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayNsdDiscovery.kt").readText()
        assertTrue(source.contains("private var discoveryGeneration = 0L"))
        assertTrue(source.contains("discoveryGeneration++"))
        assertTrue(source.contains("discoveryListener !== this"))
        val resolved = source.substringAfter("override fun onServiceResolved(")
            .substringBefore("private fun emit(state: CustomerDisplayDiscoveryState)")
        assertTrue(resolved.contains("if (!running || discoveryGeneration != generation) return@post"))
        assertTrue(source.contains("discoveryGeneration == generation && discoveryListener === this"))
    }
}
