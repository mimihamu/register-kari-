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
        assertEquals(0L, secondEntered.count)
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
        // v1.36 sale receipts wrap the raw TCP gateway with ReceiptStampGatewayV136,
        // but the actual TCP transport remains inside the existing endpoint permit.
        assertTrue(autoSource.contains("val rawGateway = TcpEscPosPrinterGateway("))
        assertTrue(queueSource.contains("val rawGateway = TcpEscPosPrinterGateway("))
        assertTrue(autoSource.contains("delegate = rawGateway"))
        assertTrue(queueSource.contains("delegate = rawGateway"))
        assertTrue(receiptSource.contains("future = executor.submit<Unit>"))
    }

    @Test
    fun automaticAndManualPathsHoldEndpointGateAcrossJobClaimAndSend() {
        assertTrue(autoSource.contains("PrinterEndpointSendGate.withPermit("))
        assertTrue(autoSource.contains("val candidate = AutomaticPrintQueuePolicy.oldestCandidate("))
        assertTrue(autoSource.contains("return@withPermit null"))
        assertTrue(autoSource.indexOf("PrinterEndpointSendGate.withPermit(") < autoSource.indexOf("val candidate = AutomaticPrintQueuePolicy.oldestCandidate("))
        assertTrue(queueSource.contains("PrinterEndpointSendGate.withPermit("))
        assertTrue(queueSource.contains("requireCurrentStatus(job.status, current.status)"))
        assertTrue(queueSource.contains("requireCurrentStatus(unifiedJob.status, sourceJob.status)"))
        assertTrue(queueSource.contains("一覧を再読込してから操作してください"))
    }

    @Test
    fun v099ReleaseAndCurrentCiKeepDuplicatePrevention() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val notes = File(root, "docs/V0.99_RELEASE_NOTES.md").readText()
        val requirements = File(root, "docs/V0.99_PRINTER_ENDPOINT_SERIALIZATION.md").readText()

        val currentVersionCode = Regex("versionCode\\s*=\\s*(\\d+)")
            .find(gradle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(currentVersionCode >= 129)
        assertTrue(notes.contains("versionCode 129") || notes.contains("versionCode `129`"))
        assertTrue(notes.contains("0.99.0-dev.1"))
        assertTrue(workflow.contains("V099PrinterEndpointSerializationTest.kt"))
        assertTrue(workflow.contains("PRINTER_ENDPOINT_SERIALIZATION=true"))
        assertTrue(workflow.contains("PRINTER_JOB_STALE_STATE_REVALIDATION=true"))
        assertTrue(workflow.contains("PRINTER_AUTO_MANUAL_DUPLICATE_PREVENTION=true"))
        assertTrue(notes.contains("二重印刷"))
        assertTrue(requirements.contains("最新ステータス"))
        assertTrue(notes.contains("最終総合実機試験へ繰越"))
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
