package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CdImmediateTokenRevocationTest {
    @Test
    fun reissueReconfiguresRunningServerInsteadOfLeavingOldSessionsActive() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplaySettingsActivity.kt").readText()
        val block = source.substringAfter("val newToken = store.regenerateToken()")
            .substringBefore("}) { Text(\"トークン再発行\") }")
        assertTrue(block.contains("store.load().copy(token = newToken)"))
        assertTrue(block.contains("CustomerDisplayRuntime.applySettings(context, runningConfig)"))
        assertTrue(block.contains("runtimeStatus = CustomerDisplayRuntime.status()"))
    }

    @Test
    fun socketRestartClosesEveryPriorClient() {
        val runtime = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayRuntime.kt").readText()
        assertTrue(runtime.contains("if (existing == config"))
        assertTrue(runtime.contains("stopLocked()"))
        assertTrue(runtime.contains("newServer.start()"))
        val server = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayWebSocketServer.kt").readText()
        assertTrue(server.contains("clients.forEach { it.close() }"))
    }
}
