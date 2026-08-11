package jp.co.tenposinfo.register

import java.io.File
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V101GoogleDriveReauthorizationSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun accountAndEnvironmentBlockersDoNotPermanentlyFailSaleJson() {
        listOf(
            GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED,
            GoogleDriveApiFailureCategory.API_DISABLED,
            GoogleDriveApiFailureCategory.STORAGE_FULL,
            GoogleDriveApiFailureCategory.PERMISSION_DENIED,
        ).forEach { category ->
            assertEquals(
                GoogleDriveCandidateFailureDisposition.BLOCK_QUEUE,
                GoogleDriveCandidateFailurePolicy.disposition(category),
            )
        }
    }

    @Test
    fun transientErrorsRetryBatchAndInvalidJsonRemainsItemFailure() {
        listOf(
            GoogleDriveApiFailureCategory.RATE_LIMITED,
            GoogleDriveApiFailureCategory.NETWORK,
            GoogleDriveApiFailureCategory.SERVER,
            GoogleDriveApiFailureCategory.UNKNOWN,
        ).forEach { category ->
            assertEquals(
                GoogleDriveCandidateFailureDisposition.RETRY_BATCH,
                GoogleDriveCandidateFailurePolicy.disposition(category),
            )
        }
        assertEquals(
            GoogleDriveCandidateFailureDisposition.PERMANENT_ITEM,
            GoogleDriveCandidateFailurePolicy.disposition(GoogleDriveApiFailureCategory.INVALID_DATA),
        )
        assertEquals(
            GoogleDriveApiFailureCategory.INVALID_DATA,
            GoogleDriveApiErrorPolicy.classify(IllegalArgumentException("bad json")),
        )
        assertEquals(
            GoogleDriveApiFailureCategory.INVALID_DATA,
            GoogleDriveApiErrorPolicy.classify(JSONException("bad json")),
        )
    }

    @Test
    fun easyConnectShowsReauthorizationInsteadOfConnected() {
        val state = GoogleDriveEasyConnectUiState(
            account = GoogleDriveAccountState(
                status = GoogleDriveAccountStatus.CONNECTED,
                email = "operator@example.com",
            ),
            upload = GoogleDriveDirectUploadStatus(
                blockedCategory = GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED,
            ),
            connectionTest = GoogleDriveConnectionTestState(
                status = GoogleDriveConnectionTestStatus.SUCCEEDED,
            ),
        )
        assertFalse(GoogleDriveEasyConnectPolicy.isReady(state))
        assertEquals("再接続が必要", GoogleDriveEasyConnectPolicy.statusLabel(state))
    }

    @Test
    fun coordinatorPreservesBlockedCandidateAndStopsBatch() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt").readText()
        val blockStart = source.indexOf("GoogleDriveCandidateFailureDisposition.BLOCK_QUEUE -> {")
        val retryStart = source.indexOf("GoogleDriveCandidateFailureDisposition.RETRY_BATCH -> {", blockStart)
        assertTrue(blockStart >= 0)
        assertTrue(retryStart > blockStart)
        val blockBody = source.substring(blockStart, retryStart)
        assertTrue(blockBody.contains("blockedCategory = category"))
        assertTrue(blockBody.contains("売上JSONは永久失敗にせず"))
        assertTrue(blockBody.contains("break"))
        assertFalse(blockBody.contains("markFailure(candidate"))
    }

    @Test
    fun bothSuccessfulEasyConnectPathsClearBlockerBeforeRequestingUpload() {
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveEasyConnectActivity.kt").readText()
        val marker = "GoogleDriveDirectUploadStatusStore(applicationContext).clearBlocker()"
        assertEquals(2, Regex(Regex.escape(marker)).findAll(source).count())
        val setup = source.substring(source.indexOf("private fun finishEasySetup"), source.indexOf("private fun synchronizeNow"))
        val manual = source.substring(source.indexOf("private fun synchronizeNow"), source.indexOf("private fun disconnect"))
        assertTrue(setup.indexOf(marker) < setup.indexOf("GoogleDriveDirectUploadScheduler.enqueueNow"))
        assertTrue(manual.indexOf(marker) < manual.indexOf("GoogleDriveDirectUploadScheduler.enqueueNow"))
    }

    @Test
    fun releaseVersionsDocsAndDataSafetyAreHistoricalAndCurrentCodeRemainsSafe() {
        val registerGradle = File(root, "app/build.gradle.kts").readText()
        val plusGradle = File(root, "management-app/build.gradle.kts").readText()
        val requirements = File(root, "docs/V1.01_GOOGLE_DRIVE_REAUTHORIZATION_SAFETY.md")
        val notes = File(root, "docs/V1.01_RELEASE_NOTES.md")
        val source = File(root, "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt").readText()
        val registerCode = Regex("versionCode = (\\d+)").find(registerGradle)?.groupValues?.get(1)?.toInt()
            ?: error("register versionCode not found")
        val registerName = Regex("versionName = \"([^\"]+)\"").find(registerGradle)?.groupValues?.get(1)
            ?: error("register versionName not found")
        val plusCode = Regex("versionCode = (\\d+)").find(plusGradle)?.groupValues?.get(1)?.toInt()
            ?: error("plus versionCode not found")
        val plusName = Regex("versionName = \"([^\"]+)\"").find(plusGradle)?.groupValues?.get(1)
            ?: error("plus versionName not found")

        assertTrue(registerCode >= 131)
        assertTrue(registerName.matches(Regex("\\d+\\.\\d+\\.\\d+-dev\\.\\d+")))
        assertTrue(plusCode >= 15)
        assertTrue(plusName.matches(Regex("\\d+\\.\\d+\\.\\d+-dev\\.\\d+")))
        assertTrue(requirements.isFile)
        assertTrue(notes.isFile)
        val historicalNotes = notes.readText()
        assertTrue(historicalNotes.contains("versionCode `131`"))
        assertTrue(historicalNotes.contains("versionName `1.01.0-dev.1`"))
        assertTrue(historicalNotes.contains("versionCode `15`"))
        assertTrue(historicalNotes.contains("versionName `0.15.0-dev.1`"))
        assertFalse(source.contains("DELETE FROM sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE sales", ignoreCase = true))
    }
}
