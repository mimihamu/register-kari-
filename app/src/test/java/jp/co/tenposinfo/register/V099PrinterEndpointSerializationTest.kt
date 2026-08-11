package jp.co.tenposinfo.register

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V099PrinterEndpointSerializationTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }
    private val receiptSource = File(root, "app/src/main/java/jp/co/tenposinfo/register/Receipt.kt").readText()
    private val gateSource = File(root, "app/src/main/java/jp/co/tenposinfo/register/PrinterEndpointSendGate.kt").readText()
    private val autoSource = File(root, "app/src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt").readText()
    private val queueSource = File(root, "app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt").readText()

    @Test
    fun endpointKeyNormalizesHostAndKeepsPort() {
        assertEquals("printer.local:9100", PrinterEndpointSendGate.endpointKey(" Printer.Local ", 9100))
        assertEquals("printer.local:9101", PrinterEndpointSendGate.endpointKey("printer.local", 9101))
    }

    @Test
    fun sameEndpointNeverEntersCriticalSectionConcurrently() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val first = thread(start = true) {
            PrinterEndpointSendGate.withPermit("10.0.0.10", 9100, 2_000) {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, now) }
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                active.decrementAndGet()
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val second = thread(start = true) {
            PrinterEndpointSendGate.withPermit("10.0.0.10", 9100, 2_000) {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, now) }
                secondEntered.countDown()
                active.decrementAndGet()
            }
        }

        assertFalse(secondEntered.await(120, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(secondEntered.count == 0L)
        assertEquals(1, maxActive.get())
    }

    @Test
    fun differentEndpointsDoNotBlockEachOther() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val first = thread(start = true) {
            PrinterEndpointSendGate.withPermit("10.0.0.20", 9100, 2_000) {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val second = thread(start = true) {
            PrinterEndpointSendGate.withPermit("10.0.0.21", 9100, 500) {
                secondEntered.countDown()
            }
        }

        assertTrue(secondEntered.await(300, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }

    @Test
    fun waitTimeoutFailsBeforeTransportAndIsSafeToRetry() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondBlockExecuted = AtomicBoolean(false)

        val first = thread(start = true) {
            PrinterEndpointSendGate.withPermit("10.0.0.30", 9100, 2_000) {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val error = runCatching {
            PrinterEndpointSendGate.withPermit("10.0.0.30", 9100, 30) {
                secondBlockExecuted.set(true)
            }
        }.exceptionOrNull()

        releaseFirst.countDown()
        first.join(2_000)

        assertTrue(error is PrinterEndpointBusyException)
        assertFalse(secondBlockExecuted.get())
        assertEquals(PrinterFailureDisposition.SAFE_TO_RETRY, PrinterRetrySafety.classify(error!!))
        assertTrue(error.message.orEmpty().contains("送信待ちタイムアウト"))
    }

    @Test
    fun permitIsReleasedWhenProtectedBlockThrows() {
        val expected = IllegalStateException("boom")
        val first = runCatching {
            PrinterEndpointSendGate.withPermit("10.0.0.40", 9100, 100) {
                throw expected
            }
        }.exceptionOrNull()
        assertTrue(first === expected)

        var entered = false
        PrinterEndpointSendGate.withPermit("10.0.0.40", 9100, 100) {
            entered = true
        }
        assertTrue(entered)
    }

    @Test
    fun allRealTcpCallersConvergeOnProtectedGateway() {
        assertTrue(receiptSource.contains("PrinterEndpointSendGate.withPermit("))
        assertTrue(receiptSource.indexOf("PrinterEndpointSendGate.withPermit(") < receiptSource.indexOf("Socket().use { socket ->"))
        assertTrue(receiptSource.contains("private fun sendExclusive(payload: ByteArray)"))
        assertTrue(receiptSource.contains("TcpEscPosPrinterGateway("))
        assertTrue(autoSource.contains("val gateway = TcpEscPosPrinterGateway("))
        assertTrue(queueSource.contains("val gateway = TcpEscPosPrinterGateway("))
        assertTrue(receiptSource.contains("future = executor.submit<Unit>"))
    }

    @Test
    fun gateDoesNotIntroduceSalesDataDestruction() {
        listOf(gateSource, receiptSource).forEach { source ->
            assertFalse(source.contains("DELETE FROM sales", ignoreCase = true))
            assertFalse(source.contains("UPDATE sales", ignoreCase = true))
            assertFalse(source.contains("DROP TABLE", ignoreCase = true))
        }
    }
}
