package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V150Syn001002ImmutableOutboxDocumentTest {
    private val appRoot = File(System.getProperty("user.dir")).let { if (File(it, "app").isDirectory) File(it, "app") else it }
    private fun source(name: String) = File(appRoot, "src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test fun formalFieldsAndImmutabilityArePersisted() {
        val text = source("OutboxDocumentV150.kt")
        listOf(
            "document_id", "document_type", "source_business_id", "schema_version",
            "canonical_payload_bytes BLOB", "sha256", "producer_id", "sequence_no",
            "completion_mode", "status", "SYN_OUTBOX_DOCUMENT_IMMUTABLE", "SYN_OUTBOX_DOCUMENT_DELETE_FORBIDDEN",
        ).forEach { assertTrue("missing $it", text.contains(it)) }
        assertTrue(text.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(text.contains("OutboxPayloadAssembler.build(db, record)"))
        assertTrue(text.contains("legacyMaterialized = true"))
    }

    @Test fun workerStagesOnlyStoredVerifiedBytes() {
        val text = source("BusinessSyncFoundation.kt")
        val start = text.indexOf("fun stagePending")
        val end = text.indexOf("fun recoverStaleProcessing", start)
        val stage = text.substring(start, end)
        assertTrue(stage.contains("OutboxDocumentV150.loadVerifiedBytes"))
        assertTrue(!stage.contains("OutboxPayloadAssembler.build"))
        assertTrue(stage.contains("writeBytes(payloadBytes)"))
    }

    @Test fun saleAndSettlementMaterializeInsideFinalizationTransactions() {
        val register = source("RegisterDatabase.kt")
        assertTrue(register.indexOf("SaleTaxSnapshotStoreV136.enrichSaleJournal") < register.indexOf("OutboxDocumentV150.materializeLatest(this, JournalEventType.SALE.name"))
        assertTrue(source("OperationsStore.kt").contains("OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())"))
        assertTrue(source("AdvancedOperationsStore.kt").contains("OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())"))
    }

    @Test fun allTriggerProducedBusinessEventsFreezeBeforeCommit() {
        val operations = source("OperationsStore.kt")
        val advanced = source("AdvancedOperationsStore.kt")
        listOf(
            "JournalEventType.BUSINESS_OPEN.name",
            "JournalEventType.CASH_MOVEMENT.name",
            "JournalEventType.REVERSAL.name",
            "JournalEventType.SETTLEMENT.name",
            "JournalEventType.BUSINESS_STATE.name",
        ).forEach { marker ->
            assertTrue("OperationsStore missing $marker", operations.contains("OutboxDocumentV150.materializeLatest(this, $marker"))
            assertTrue("AdvancedOperationsStore missing $marker", advanced.contains("OutboxDocumentV150.materializeLatest(this, $marker"))
        }
        assertTrue(source("DynamicCatalogRuntime.kt").contains("JournalEventType.MENU_REVISION.name"))
    }

    @Test fun menuApplicationResultIsMaterializedForAllCommittedOutcomes() {
        val catalog = source("DynamicCatalogRuntime.kt")
        val foundation = source("BusinessSyncFoundation.kt")
        assertTrue(foundation.contains("MENU_APPLY_RESULT"))
        assertTrue(catalog.contains("status = \"FAILED_VALIDATION\""))
        assertTrue(catalog.contains("status = \"APPLIED\""))
        assertTrue(catalog.contains("status = \"ROLLED_BACK\""))
        assertTrue(foundation.contains("OutboxDocumentV150.materialize(db, eventId)"))
    }
}
