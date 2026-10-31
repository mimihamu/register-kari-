package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CdConcurrentSocketCollectionTest {
    @Test
    fun broadcastAndStopIterateWeaklyConsistentConcurrentCollection() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayWebSocketServer.kt")
            .readText()
        assertTrue(source.contains("private val clients = ConcurrentHashMap.newKeySet<ClientConnection>()"))
        assertTrue(source.contains("clients.forEach { it.close() }"))
        assertTrue(source.contains("clients.forEach { connection ->"))
        // Kotlin Collection.toList() can assume a stable size while the
        // ConcurrentHashMap key set shrinks, throwing NoSuchElementException.
        assertFalse(source.contains("clients.toList()"))
    }
}
