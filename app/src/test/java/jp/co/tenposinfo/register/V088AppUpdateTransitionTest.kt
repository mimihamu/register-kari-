package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V088AppUpdateTransitionTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun sameSuccessfulVersionDoesNotCreateAnotherTransition() {
        val current = AppReleaseIdentityV088("0.88.0-dev.1", 118)
        val decision = AppUpdateTransitionPolicyV088.begin(
            lastSuccessful = current,
            existingPending = null,
            current = current,
            now = 10L,
        )

        assertFalse(decision.trackingRequired)
        assertNull(decision.pending)
        assertNull(decision.displacedIncomplete)
    }

    @Test
    fun firstV088StartupCreatesBaselineWithoutInventingPreviousVersion() {
        val current = AppReleaseIdentityV088("0.88.0-dev.1", 118)
        val decision = AppUpdateTransitionPolicyV088.begin(
            lastSuccessful = null,
            existingPending = null,
            current = current,
            now = 20L,
        )

        assertTrue(decision.trackingRequired)
        val pending = requireNotNull(decision.pending)
        assertNull(pending.source)
        assertEquals(current, pending.target)
        assertEquals(20L, pending.startedAt)
        assertEquals(1, pending.attemptCount)
    }

    @Test
    fun updateCarriesLastSuccessfulVersionIntoPendingTransition() {
        val old = AppReleaseIdentityV088("0.87.0-dev.1", 117)
        val current = AppReleaseIdentityV088("0.88.0-dev.1", 118)
        val decision = AppUpdateTransitionPolicyV088.begin(old, null, current, 30L)

        val pending = requireNotNull(decision.pending)
        assertEquals(old, pending.source)
        assertEquals(current, pending.target)
        assertEquals(1, pending.attemptCount)
        assertNull(decision.displacedIncomplete)
    }

    @Test
    fun retryOfIncompleteTargetKeepsFirstStartTimeAndIncrementsAttempt() {
        val old = AppReleaseIdentityV088("0.87.0-dev.1", 117)
        val current = AppReleaseIdentityV088("0.88.0-dev.1", 118)
        val existing = PendingAppStartupV088(old, current, startedAt = 40L, attemptCount = 1)
        val decision = AppUpdateTransitionPolicyV088.begin(old, existing, current, now = 99L)

        val pending = requireNotNull(decision.pending)
        assertEquals(40L, pending.startedAt)
        assertEquals(2, pending.attemptCount)
        assertNull(decision.displacedIncomplete)
    }

    @Test
    fun installingAnotherVersionBeforeSuccessPreservesIncompleteEvidence() {
        val lastSuccessful = AppReleaseIdentityV088("0.87.0-dev.1", 117)
        val failedTarget = AppReleaseIdentityV088("0.88.0-dev.1", 118)
        val newTarget = AppReleaseIdentityV088("0.89.0-dev.1", 119)
        val existing = PendingAppStartupV088(lastSuccessful, failedTarget, 50L, 3)
        val decision = AppUpdateTransitionPolicyV088.begin(lastSuccessful, existing, newTarget, 60L)

        assertEquals(existing, decision.displacedIncomplete)
        val pending = requireNotNull(decision.pending)
        assertEquals(lastSuccessful, pending.source)
        assertEquals(newTarget, pending.target)
        assertEquals(1, pending.attemptCount)
    }

    @Test
    fun startupTrackingIsAfterRestoreBeforeNormalProvidersAndReadOnlyForBusinessData() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/AppUpdateTransitionV088.kt",
        ).readText()

        assertTrue(manifest.contains("android:name=\".DataRestoreBootstrapProviderV086\""))
        assertTrue(manifest.contains("android:name=\".AppUpdateTransitionBootstrapProviderV088\""))
        assertTrue(manifest.contains("android:initOrder=\"1000\""))
        assertTrue(manifest.contains("android:initOrder=\"900\""))
        assertTrue(source.contains("beginAfterRestore"))
        assertTrue(source.contains("activity !is MainActivity"))
        assertTrue(source.contains("APP_UPDATE_STARTUP_SUCCEEDED"))
        assertTrue(source.contains("APP_UPDATE_STARTUP_RECOVERED"))
        assertTrue(source.contains("APP_UPDATE_PREVIOUS_STARTUP_INCOMPLETE"))
        assertTrue(source.contains("editor.commit()"))
        assertTrue(source.contains("PRAGMA user_version"))
        assertTrue(source.contains("tsuguregi-update-v088"))

        assertFalse(source.contains("DELETE FROM sales", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
        assertFalse(source.contains("delete(\"sales\""))
        assertFalse(source.contains("delete(\"sale_items\""))
        assertFalse(source.contains("delete(\"sale_payments\""))
    }

    @Test
    fun v088DocumentationExistsAndCumulativeTestsRemainEnabled() {
        assertTrue(File(root, "docs/V0.88_APP_UPDATE_TRANSITION_LEDGER.md").isFile)
        assertTrue(File(root, "docs/V0.88_RELEASE_NOTES.md").isFile)
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
    }
}
