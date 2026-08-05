package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V035OutboxExternalDeliveryTest {
    @Test
    fun enablingRequiresDocumentTreeDestination() {
        val failure = runCatching {
            OutboxDeliverySettingsPolicy.validated(
                OutboxDeliverySettings(enabled = true),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)

        val valid = OutboxDeliverySettingsPolicy.validated(
            OutboxDeliverySettings(
                enabled = true,
                treeUri = "content://com.example.documents/tree/sales",
                destinationLabel = "売上同期",
            ),
        )
        assertTrue(valid.enabled)
        assertEquals("売上同期", valid.destinationLabel)
    }

    @Test
    fun objectKeyPathIsStrictlyValidated() {
        assertEquals(
            listOf("つぐレジ", "2026-08-05", "sale-123.json"),
            OutboxDeliveryPathPolicy.segments("つぐレジ/2026-08-05/sale-123.json"),
        )
        assertEquals(
            "sale-123.json.partial",
            OutboxDeliveryPathPolicy.partialName("sale-123.json"),
        )
        assertTrue(runCatching { OutboxDeliveryPathPolicy.segments("../register.db") }.isFailure)
        assertTrue(runCatching { OutboxDeliveryPathPolicy.segments("root/../sale-1.json") }.isFailure)
        assertTrue(runCatching { OutboxDeliveryPathPolicy.segments("root\\sale-1.json") }.isFailure)
        assertTrue(runCatching { OutboxDeliveryPathPolicy.segments("root/2026-08-05/sale-1.txt") }.isFailure)
    }

    @Test
    fun deliveryRetryHasFiniteManualRecoveryBoundary() {
        assertEquals(60_000L, OutboxDeliveryRetryPolicy.delayMillis(1))
        assertEquals(5 * 60_000L, OutboxDeliveryRetryPolicy.delayMillis(2))
        assertEquals(30 * 60_000L, OutboxDeliveryRetryPolicy.delayMillis(3))
        assertEquals(2 * 60 * 60_000L, OutboxDeliveryRetryPolicy.delayMillis(6))
        assertEquals(6 * 60 * 60_000L, OutboxDeliveryRetryPolicy.delayMillis(9))
        assertFalse(OutboxDeliveryRetryPolicy.permanent(9))
        assertTrue(OutboxDeliveryRetryPolicy.permanent(10))
    }

    @Test
    fun sourceHooksAndManifestStayConnected() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val delivery = File(root, "OutboxExternalDelivery.kt").readText()
        val deliveryUi = File(root, "OutboxDeliverySettingsActivity.kt").readText()
        val foundation = File(root, "BusinessSyncFoundation.kt").readText()
        val syncUi = File(root, "SyncSettingsActivity.kt").readText()
        val application = File(root, "RegisterApplication.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()

        assertTrue(delivery.contains("status='STAGED'"))
        assertTrue(delivery.contains("SyncOutboxStatus.SENT.name"))
        assertTrue(delivery.contains(".partial"))
        assertTrue(delivery.contains("SHA-256"))
        assertTrue(delivery.contains("SYNC_OUTBOX_EXTERNAL_SENT"))
        assertTrue(delivery.contains("SYNC_OUTBOX_EXTERNAL_FAILED"))
        assertTrue(delivery.contains("MAX_ATTEMPTS = 10"))
        assertTrue(deliveryUi.contains("ActivityResultContracts.OpenDocumentTree"))
        assertTrue(deliveryUi.contains("takePersistableUriPermission"))
        assertTrue(deliveryUi.contains("失敗を再試行"))
        assertTrue(foundation.contains("OutboxExternalDeliveryCoordinator"))
        assertTrue(foundation.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue(syncUi.contains("OutboxDeliverySettingsActivity::class.java"))
        assertTrue(application.contains("is OutboxDeliverySettingsActivity"))
        assertTrue(manifest.contains("android:name=\".OutboxDeliverySettingsActivity\""))
        assertTrue(build.contains("versionCode = 68"))
        assertTrue(build.contains("versionName = \"0.38.0-dev.1\""))
        assertFalse(manifest.contains("<activity-alias"))
    }
}
