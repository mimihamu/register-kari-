package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V036SalesJournalJsonContractTest {
    private val identity = SalesJournalIdentity(
        storeId = "STORE-001",
        terminalId = "TERMINAL-001",
    )

    @Test
    fun duplicateImportKeyIsStableAndTerminalScoped() {
        val first = SalesJournalJsonContract.duplicateImportKey(
            identity = identity,
            eventId = "sale-10-1000",
            businessDate = "2026-08-05",
            eventType = "SALE",
        )
        val second = SalesJournalJsonContract.duplicateImportKey(
            identity = identity,
            eventId = "sale-10-1000",
            businessDate = "2026-08-05",
            eventType = "SALE",
        )
        val otherTerminal = SalesJournalJsonContract.duplicateImportKey(
            identity = identity.copy(terminalId = "TERMINAL-002"),
            eventId = "sale-10-1000",
            businessDate = "2026-08-05",
            eventType = "SALE",
        )

        assertEquals(first, second)
        assertTrue(first.matches(Regex("sj1-[0-9a-f]{64}")))
        assertNotEquals(first, otherTerminal)
    }

    @Test
    fun envelopeContainsFixedVersionIdentityAndLegacyPayload() {
        val record = JournalOutboxRecord(
            id = 1,
            eventId = "sale-10-1000",
            businessDate = "2026-08-05",
            eventType = JournalEventType.SALE.name,
            aggregateId = "10",
            objectKey = "つぐレジ/2026-08-05/sale-10.json",
            status = SyncOutboxStatus.STAGED,
            attemptCount = 1,
            lastError = null,
            createdAt = 1000,
            updatedAt = 1000,
        )
        val legacy = """{"schema":"register.sale.v2","eventId":"sale-10-1000","taxTotals":[{"ratePercent":10,"taxAmount":100}]}"""
        val json = SalesJournalJsonContract.wrap(record, legacy, identity)

        assertTrue(json.contains("\"schema\":\"jp.co.tenposinfo.tsuguregi.sales-journal\""))
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"minimumReaderVersion\":1"))
        assertTrue(json.contains("\"eventType\":\"SALE\""))
        assertTrue(json.contains("\"storeId\":\"STORE-001\""))
        assertTrue(json.contains("\"terminalId\":\"TERMINAL-001\""))
        assertTrue(json.contains("\"businessDate\":\"2026-08-05\""))
        assertTrue(json.contains("\"payloadSchema\":\"register.sale.v2\""))
        assertTrue(json.contains("\"payload\":$legacy"))
        assertTrue(SalesJournalJsonContract.supports(json))
    }

    @Test
    fun settlementIsSeparatedIntoInspectionAndZSettlement() {
        assertEquals(
            "INSPECTION",
            SalesJournalJsonContract.canonicalEventType(
                JournalEventType.SETTLEMENT.name,
                """{"schema":"register.settlement.v1","type":"INSPECTION"}""",
            ),
        )
        assertEquals(
            "Z_SETTLEMENT",
            SalesJournalJsonContract.canonicalEventType(
                JournalEventType.SETTLEMENT.name,
                """{"schema":"register.settlement.v1","type":"Z_SETTLEMENT"}""",
            ),
        )
    }

    @Test
    fun currentLegacySchemasRemainReadable() {
        assertTrue(SalesJournalJsonContract.supports("""{"schema":"register.sale.v2"}"""))
        assertTrue(SalesJournalJsonContract.supports("""{"schema":"register.reversal.v2"}"""))
        assertTrue(SalesJournalJsonContract.supports("""{"schema":"register.settlement.v1"}"""))
        assertFalse(SalesJournalJsonContract.supports("""{"schema":"register.sale.v99"}"""))
        assertFalse(SalesJournalJsonContract.supports("""{"schema":"jp.co.tenposinfo.tsuguregi.sales-journal","schemaVersion":2}"""))
    }

    @Test
    fun productionAssemblerAndPublishedSchemaStayConnected() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val foundation = File(root, "BusinessSyncFoundation.kt").readText()
        val contract = File(root, "SalesJournalJsonContract.kt").readText()
        val schema = File("../docs/schemas/tsuguregi-sales-journal-v1.schema.json").readText()
        val build = File("build.gradle.kts").readText()

        assertTrue(foundation.contains("SalesJournalJsonContract.wrap"))
        assertTrue(foundation.contains("SalesJournalIdentityStore.resolve(db)"))
        assertTrue(contract.contains("DUPLICATE_KEY_VERSION = 1"))
        assertTrue(schema.contains("\"schemaVersion\""))
        assertTrue(schema.contains("\"duplicateImportKey\""))
        assertTrue(schema.contains("\"taxTotals\""))
        assertTrue(build.contains("versionCode = 97"))
        assertTrue(build.contains("versionName = \"0.67.0-dev.1\""))
    }
}