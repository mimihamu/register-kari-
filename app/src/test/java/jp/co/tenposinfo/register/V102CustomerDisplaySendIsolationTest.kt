package jp.co.tenposinfo.register

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V102CustomerDisplaySendIsolationTest {
    @Test
    fun stalledWriterDoesNotBlockProducerAndOnlyLatestPendingSnapshotIsSent() {
        val executor = Executors.newSingleThreadExecutor()
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val latestWritten = CountDownLatch(1)
        val producerReturned = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<String>()
        val dispatcher = CustomerDisplayLatestSendDispatcher(
            executor = executor,
            send = { payload ->
                sent += payload
                if (payload == "first") {
                    firstWriteStarted.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                }
                if (payload == "third") latestWritten.countDown()
            },
        )

        try {
            assertTrue(dispatcher.offer("first"))
            assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS))

            Thread {
                assertTrue(dispatcher.offer("second"))
                assertTrue(dispatcher.offer("third"))
                producerReturned.countDown()
            }.start()

            assertTrue("POS poller must not wait for a stalled socket writer", producerReturned.await(500, TimeUnit.MILLISECONDS))
            releaseFirstWrite.countDown()
            assertTrue(latestWritten.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("first", "third"), sent.toList())
        } finally {
            dispatcher.close()
            releaseFirstWrite.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun stalledDisplayDoesNotPreventAnotherDisplayFromSending() {
        val executor = Executors.newCachedThreadPool()
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val fastWritten = CountDownLatch(1)
        val slow = CustomerDisplayLatestSendDispatcher(
            executor = executor,
            send = {
                slowStarted.countDown()
                assertTrue(releaseSlow.await(2, TimeUnit.SECONDS))
            },
        )
        val fast = CustomerDisplayLatestSendDispatcher(
            executor = executor,
            send = { fastWritten.countDown() },
        )

        try {
            assertTrue(slow.offer("slow"))
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS))
            assertTrue(fast.offer("fast"))
            assertTrue("another healthy CD must remain independent", fastWritten.await(500, TimeUnit.MILLISECONDS))
        } finally {
            slow.close()
            fast.close()
            releaseSlow.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun writerFailureClosesDispatcherAndRejectsFutureSnapshots() {
        val executor = Executors.newSingleThreadExecutor()
        val failed = CountDownLatch(1)
        val dispatcher = CustomerDisplayLatestSendDispatcher(
            executor = executor,
            send = { throw IllegalStateException("socket write failed") },
            onFailure = { failed.countDown() },
        )

        try {
            assertTrue(dispatcher.offer("first"))
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertFalse(dispatcher.offer("second"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun explicitCloseRejectsFutureSnapshots() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = CustomerDisplayLatestSendDispatcher(
            executor = executor,
            send = {},
        )
        dispatcher.close()
        try {
            assertFalse(dispatcher.offer("after-close"))
        } finally {
            executor.shutdownNow()
        }
    }
}
