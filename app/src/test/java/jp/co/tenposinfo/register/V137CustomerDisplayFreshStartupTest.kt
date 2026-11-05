package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayFreshStartupTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayRuntime.kt").readText()

    @Test
    fun newlyReconfiguredServerNeverOffersPreviousPaymentTotal() {
        assertTrue(source.contains("latestSnapshot = CustomerDisplaySnapshotFactory.standby("))
        assertTrue(source.contains("sequence = sequence.incrementAndGet()"))
        assertFalse(source.contains("latestSnapshot = latestSnapshot.copy("))
    }

    @Test
    fun concurrentPollerAndPaymentUiPublishesCannotReverseSequenceOrder() {
        val function = source.substringAfter("fun publish(snapshot: CustomerDisplaySnapshot)")
            .substringBefore("fun stop()")
        assertTrue(function.contains("synchronized(lock) {"))
        val sequence = function.indexOf("sequence = sequence.incrementAndGet()")
        val store = function.indexOf("latestSnapshot = normalized")
        val delivery = function.indexOf("server?.broadcast(normalized.toJson())")
        assertTrue(sequence >= 0 && store > sequence && delivery > store)
    }
}
