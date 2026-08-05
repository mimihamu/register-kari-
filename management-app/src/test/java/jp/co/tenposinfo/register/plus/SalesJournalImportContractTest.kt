package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesJournalImportContractTest {
    @Test
    fun validV1EnvelopeIsAccepted() {
        val result = SalesJournalImportContract.parse(validEnvelope())

        assertTrue(result is JournalParseResult.Accepted)
        val envelope = (result as JournalParseResult.Accepted).envelope
        assertEquals("SALE", envelope.eventType)
        assertEquals("STORE-001", envelope.storeId)
        assertEquals("TERMINAL-001", envelope.terminalId)
        assertEquals("register.sale.v2", envelope.payloadSchema)
        assertEquals(1_080L, envelope.totalAmount)
        assertEquals(expectedDuplicateKey(), envelope.duplicateImportKey)
    }

    @Test
    fun unsupportedSchemaAndFutureReaderAreRejected() {
        val wrongSchema = SalesJournalImportContract.parse(
            validEnvelope().replace(
                SalesJournalImportContract.SCHEMA,
                "example.unsupported",
            ),
        )
        assertRejected(wrongSchema, ImportRejectionCode.UNSUPPORTED_SCHEMA)

        val futureReader = SalesJournalImportContract.parse(
            validEnvelope().replace(
                "\"minimumReaderVersion\":1",
                "\"minimumReaderVersion\":2",
            ),
        )
        assertRejected(futureReader, ImportRejectionCode.UNSUPPORTED_VERSION)
    }

    @Test
    fun duplicateKeyFormatFormulaAndEventTypeAreStrictlyValidated() {
        val badFormat = SalesJournalImportContract.parse(
            validEnvelope().replace(expectedDuplicateKey(), "sj1-short"),
        )
        assertRejected(badFormat, ImportRejectionCode.INVALID_FIELD)

        val wrongFormula = SalesJournalImportContract.parse(
            validEnvelope().replace(expectedDuplicateKey(), "sj1-${"b".repeat(64)}"),
        )
        assertRejected(wrongFormula, ImportRejectionCode.DUPLICATE_KEY_MISMATCH)

        val badEvent = SalesJournalImportContract.parse(
            validEnvelope().replace("\"eventType\":\"SALE\"", "\"eventType\":\"UNKNOWN\""),
        )
        assertRejected(badEvent, ImportRejectionCode.UNSUPPORTED_EVENT_TYPE)
    }

    @Test
    fun payloadSchemaMismatchIsQuarantined() {
        val result = SalesJournalImportContract.parse(
            validEnvelope().replace(
                "\"schema\":\"register.sale.v2\"",
                "\"schema\":\"register.reversal.v2\"",
            ),
        )
        assertRejected(result, ImportRejectionCode.PAYLOAD_SCHEMA_MISMATCH)
    }

    @Test
    fun invalidJsonAndMissingTotalRemainSafe() {
        assertRejected(
            SalesJournalImportContract.parse("{"),
            ImportRejectionCode.INVALID_JSON,
        )

        val withoutTotal = SalesJournalImportContract.parse(
            validEnvelope().replace(
                "\"totalAmount\":1080",
                "\"unknownAmount\":1080",
            ),
        )
        assertTrue(withoutTotal is JournalParseResult.Accepted)
        assertNull((withoutTotal as JournalParseResult.Accepted).envelope.totalAmount)
    }

    @Test
    fun duplicateInsertPolicyMatchesSqliteConflictIgnore() {
        assertTrue(SalesJournalImportPolicy.isDuplicateInsertResult(-1L))
        assertTrue(!SalesJournalImportPolicy.isDuplicateInsertResult(42L))
    }

    private fun assertRejected(
        result: JournalParseResult,
        code: ImportRejectionCode,
    ) {
        assertTrue(result is JournalParseResult.Rejected)
        assertEquals(code, (result as JournalParseResult.Rejected).code)
    }

    private fun expectedDuplicateKey(): String =
        SalesJournalImportContract.expectedDuplicateImportKey(
            storeId = "STORE-001",
            terminalId = "TERMINAL-001",
            businessDate = "2026-08-05",
            eventType = "SALE",
            eventId = "sale-10-1000",
        )

    private fun validEnvelope(): String = """
        {
          "schema":"${SalesJournalImportContract.SCHEMA}",
          "schemaVersion":1,
          "minimumReaderVersion":1,
          "duplicateKeyVersion":1,
          "eventId":"sale-10-1000",
          "duplicateImportKey":"${expectedDuplicateKey()}",
          "eventType":"SALE",
          "storeId":"STORE-001",
          "terminalId":"TERMINAL-001",
          "businessDate":"2026-08-05",
          "aggregateId":"10",
          "occurredAt":1785916800000,
          "payloadSchema":"register.sale.v2",
          "payload":{
            "schema":"register.sale.v2",
            "totalAmount":1080
          }
        }
    """.trimIndent()
}
