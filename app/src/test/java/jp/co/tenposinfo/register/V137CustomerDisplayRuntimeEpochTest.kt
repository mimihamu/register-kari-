package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayRuntimeEpochTest {
    @Test
    fun stoppedPollerCannotPublishThroughCurrentRuntime() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayRuntime.kt").readText()
        val stop = source.substringAfter("private fun stopLocked()")
        assertTrue(stop.contains("runtimeGeneration++"))
        val configure = source.substringAfter("fun applySettings(").substringBefore("fun publish(")
        assertTrue(configure.contains("val generation = runtimeGeneration"))
        assertTrue(configure.contains("if (runtimeGeneration == generation) publish(snapshot)"))
    }

    @Test
    fun stoppedServerCannotOverwriteCurrentClientCountOrRuntimeError() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayRuntime.kt").readText()
        val configure = source.substringAfter("fun applySettings(").substringBefore("fun publish(")
        assertTrue(configure.contains("onClientCountChanged = { count ->"))
        assertTrue(configure.contains("if (runtimeGeneration == generation) {"))
        assertTrue(configure.contains("if (runtimeGeneration == generation) lastError = message"))
    }
}
