package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V121GoogleDriveSyncSingleFlightTest {
    @Test
    fun singleFlightSerializesConcurrentSyncBodies() {
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val first = executor.submit {
            GoogleDriveSyncSingleFlightV121.run {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = executor.submit {
            GoogleDriveSyncSingleFlightV121.run {
                secondEntered.countDown()
            }
        }

        assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()
    }

    @Test
    fun allDriveSyncEntrypointsShareOwnershipAndImmediateWorkChain() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()

        assertTrue(source.contains("GoogleDriveSyncSingleFlightV121.run"))
        assertTrue(source.contains("putString(\"run_token\", runToken)"))
        assertTrue(source.contains("failedForRun"))
        assertTrue(source.contains("preferences.getString(\"run_token\", null) != runToken"))
        assertTrue(source.contains("if (preferences.getBoolean(\"running\", false)) return"))
        assertTrue(source.contains("IMMEDIATE_NAME = \"tsuguregi-plus-drive-api-sync-immediate\""))
        assertTrue(source.contains("enqueueImmediate(context, ExistingWorkPolicy.KEEP)"))
        assertTrue(source.contains("enqueueImmediate(context, ExistingWorkPolicy.APPEND_OR_REPLACE)"))
        assertFalse(source.contains("tsuguregi-plus-drive-api-sync-startup"))
        assertFalse(source.contains("tsuguregi-plus-drive-api-sync-manual"))
    }

    @Test
    fun v120PaginationSafetyRemainsInsideSingleFlightBoundary() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val lockAt = source.indexOf("GoogleDriveSyncSingleFlightV121.run")
        val pageAt = source.indexOf("client.listJournalPage(pageToken)", lockAt)
        val importAt = source.indexOf("SalesJournalImportRepository(database).importDocuments(documents)", pageAt)
        val fingerprintAt = source.indexOf("processed.forEach { recordFingerprint(it.remote, it.sha256) }", importAt)
        val nextPageAt = source.indexOf("pageToken = page.nextPageToken", fingerprintAt)

        assertTrue(lockAt >= 0)
        assertTrue(pageAt > lockAt)
        assertTrue(importAt > pageAt)
        assertTrue(fingerprintAt > importAt)
        assertTrue(nextPageAt > fingerprintAt)
    }
}
