package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V083RestoreBootstrapOrderTest {
    private data class ProviderOrder(val name: String, val initOrder: Int)

    private fun providerOrders(manifest: String): List<ProviderOrder> {
        val providerRegex = Regex("""<provider\s+([\s\S]*?)/>""")
        val nameRegex = Regex("""android:name="([^"]+)"""")
        val orderRegex = Regex("""android:initOrder="(\d+)"""")
        return providerRegex.findAll(manifest).mapNotNull { match ->
            val block = match.groupValues[1]
            val name = nameRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val order = orderRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            ProviderOrder(name, order)
        }.toList()
    }

    @Test
    fun restoreProviderRunsBeforeEveryOtherAppBootstrapProvider() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val providers = providerOrders(manifest)
        val restore = providers.firstOrNull { it.name == ".DataRestoreBootstrapProvider" }
        assertNotNull(restore)
        restore!!

        val others = providers.filter { it.name != restore.name }
        assertTrue(others.isNotEmpty())
        assertEquals(1000, restore.initOrder)
        assertTrue(
            "DataRestoreBootstrapProvider must have the highest initOrder because Android initializes higher initOrder first",
            others.all { restore.initOrder > it.initOrder },
        )
    }

    @Test
    fun restoreBootstrapStillAppliesPendingRestoreBeforeNormalDatabaseUse() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DataProtection.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(source.contains("class DataRestoreBootstrapProvider : ContentProvider()"))
        assertTrue(source.contains("PendingRestoreApplier::applyIfPresent"))
        assertTrue(source.contains("DataProtectionManager.atomicReplace(pending, database)"))
        assertTrue(source.contains("verifyRestoredDatabase(database)"))
        assertTrue(source.contains("if (hadCurrent && rollback.isFile) rollback.copyTo(database, overwrite = true)"))
        assertTrue(manifest.contains("android:name=\".DataRestoreBootstrapProvider\""))
        assertTrue(manifest.contains("android:initOrder=\"1000\""))
    }

    @Test
    fun restoreOrderingChangeDoesNotAlterBusinessDataPolicies() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DataProtection.kt").readText()
        val root = File("..")
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(source.contains("DATA_RESTORE_STAGED"))
        assertTrue(source.contains("DATA_RESTORE_APPLIED"))
        assertTrue(source.contains("DATA_RESTORE_CANCELLED"))
        assertFalse(source.contains("DELETE FROM sales"))
        assertFalse(source.contains("DELETE FROM sales_journal"))
        assertFalse(source.contains("DELETE FROM sync_outbox"))
        assertTrue(workflow.contains("V083RestoreBootstrapOrderTest.kt"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
    }
}
