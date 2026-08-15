package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class V129DriveVerificationHistoryMutationGateTest {
    @Test
    fun concurrentHistoryMutationsAreSerializedAcrossCallers() {
        val workers = 12
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workers)

        repeat(workers) {
            executor.execute {
                try {
                    start.await()
                    GoogleDriveVerificationHistoryMutationGateV129.exclusive {
                        val nowInside = inside.incrementAndGet()
                        maxInside.accumulateAndGet(nowInside, ::maxOf)
                        Thread.sleep(5)
                        inside.decrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        try {
            org.junit.Assert.assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, maxInside.get())
        assertEquals(0, inside.get())
    }

    @Test
    fun exclusiveReturnsBlockResult() {
        assertEquals(
            "history-published",
            GoogleDriveVerificationHistoryMutationGateV129.exclusive { "history-published" },
        )
    }
}
