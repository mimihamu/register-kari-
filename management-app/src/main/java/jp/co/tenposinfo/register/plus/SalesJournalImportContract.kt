package jp.co.tenposinfo.register.plus

import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

enum class ImportRejectionCode {
    INVALID_JSON,
    DOCUMENT_TOO_LARGE,
    READ_ERROR,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_VERSION,
    MISSING_FIELD,
    INVALID_FIELD,
    DUPLICATE_KEY_MISMATCH,
    UNSUPPORTED_EVENT_TYPE,
    UNSUPPORTED_PAYLOAD_SCHEMA,
    PAYLOAD_SCHEMA_MISMATCH,
}

data class SalesJournalEnvelope(
    val schemaVersion: Int,
    val minimumReaderVersion: Int,
    val duplicateKeyVersion: Int,
    val eventId: String,
    val duplicateImportKey: String,
    val eventType: String,
    val storeId: String,
    val terminalId: String,
    val businessDate: String,
    val aggregateId: String,
    val occurredAt: Long,
    val payloadSchema: String,
    val payloadJson: String,
    val totalAmount: Long?,
    val rawJson: String,
)

sealed interface JournalParseResult {
    data class Accepted(val envelope: SalesJournalEnvelope) : JournalParseResult

    data class Rejected(
        val code: ImportRejectionCode,
        val message: String,
    ) : JournalParseResult
}

object SalesJournalImportContract {
    const val SCHEMA = "jp.co.tenposinfo.tsuguregi.sales-journal"
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val READER_VERSION = 1
    const val SUPPORTED_DUPLICATE_KEY_VERSION = 1
    const val MAX_DOCUMENT_BYTES = 20L * 1024L * 1024L

    val supportedEventTypes: Set<String> = setOf(
        "SALE",
        "REVERSAL",
        "INSPECTION",
        "Z_SETTLEMENT",
        "CASH_MOVEMENT",
        "BUSINESS_OPEN",
        "BUSINESS_STATE",
        "MENU_REVISION",
    )

    val supportedPayloadSchemas: Set<String> = setOf(
        "register.sale.v2",
        "register.reversal.v2",
        "register.settlement.v1",
        "register.menu-revision.v1",
        "register.generic.v1",
    )

    private val duplicateKeyPattern = Regex("sj1-[0-9a-f]{64}")

    fun parse(rawJson: String): JournalParseResult {
        val root = runCatching { JSONObject(rawJson) }.getOrElse {
            return rejected(ImportRejectionCode.INVALID_JSON, "JSONとして解析できません")
        }

        val schema = root.requiredText("schema")
            ?: return missing("schema")
        if (schema != SCHEMA) {
            return rejected(
                ImportRejectionCode.UNSUPPORTED_SCHEMA,
                "未対応のschemaです: $schema",
            )
        }

        val schemaVersion = root.requiredInt("schemaVersion")
            ?: return missing("schemaVersion")
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return rejected(
                ImportRejectionCode.UNSUPPORTED_VERSION,
                "未対応のschemaVersionです: $schemaVersion",
            )
        }

        val minimumReaderVersion = root.requiredInt("minimumReaderVersion")
            ?: return missing("minimumReaderVersion")
        if (minimumReaderVersion <= 0 || minimumReaderVersion > READER_VERSION) {
            return rejected(
                ImportRejectionCode.UNSUPPORTED_VERSION,
                "必要readerVersion=$minimumReaderVersion、対応=$READER_VERSION",
            )
        }

        val duplicateKeyVersion = root.requiredInt("duplicateKeyVersion")
            ?: return missing("duplicateKeyVersion")
        if (duplicateKeyVersion != SUPPORTED_DUPLICATE_KEY_VERSION) {
            return rejected(
                ImportRejectionCode.UNSUPPORTED_VERSION,
                "未対応のduplicateKeyVersionです: $duplicateKeyVersion",
            )
        }

        val eventId = root.requiredText("eventId") ?: return missing("eventId")
        val duplicateImportKey = root.requiredText("duplicateImportKey")
            ?: return missing("duplicateImportKey")
        val eventType = root.requiredText("eventType") ?: return missing("eventType")
        val storeId = root.requiredText("storeId") ?: return missing("storeId")
        val terminalId = root.requiredText("terminalId") ?: return missing("terminalId")
        val businessDate = root.requiredText("businessDate") ?: return missing("businessDate")
        val aggregateId = root.requiredText("aggregateId") ?: return missing("aggregateId")
        val payloadSchema = root.requiredText("payloadSchema") ?: return missing("payloadSchema")

        if (!duplicateKeyPattern.matches(duplicateImportKey)) {
            return rejected(
                ImportRejectionCode.INVALID_FIELD,
                "duplicateImportKeyの形式が不正です",
            )
        }
        if (eventType !in supportedEventTypes) {
            return rejected(
                ImportRejectionCode.UNSUPPORTED_EVENT_TYPE,
                "未対応のeventTypeです: $eventType",
            )
        }
        if (payloadSchema !in supportedPayloadSchemas) {
            return rejected(
                ImportRejectionCode.UNSUPPORTED_PAYLOAD_SCHEMA,
                "未対応のpayloadSchemaです: $payloadSchema",
            )
        }
        if (!validIdentifier(storeId) || !validIdentifier(terminalId)) {
            return rejected(
                ImportRejectionCode.INVALID_FIELD,
                "storeIdまたはterminalIdの形式が不正です",
            )
        }
        if (eventId.length > 200 || aggregateId.length > 200) {
            return rejected(
                ImportRejectionCode.INVALID_FIELD,
                "eventIdまたはaggregateIdが長すぎます",
            )
        }
        if (runCatching { LocalDate.parse(businessDate) }.isFailure) {
            return rejected(
                ImportRejectionCode.INVALID_FIELD,
                "businessDateはYYYY-MM-DD形式で指定してください",
            )
        }

        val expectedDuplicateKey = expectedDuplicateImportKey(
            duplicateKeyVersion = duplicateKeyVersion,
            storeId = storeId,
            terminalId = terminalId,
            businessDate = businessDate,
            eventType = eventType,
            eventId = eventId,
        )
        if (duplicateImportKey != expectedDuplicateKey) {
            return rejected(
                ImportRejectionCode.DUPLICATE_KEY_MISMATCH,
                "duplicateImportKeyが識別項目から再計算した値と一致しません",
            )
        }

        if (!root.has("occurredAt") || root.isNull("occurredAt")) {
            return missing("occurredAt")
        }
        val occurredAt = root.optLong("occurredAt", -1L)
        if (occurredAt <= 0L) {
            return rejected(
                ImportRejectionCode.INVALID_FIELD,
                "occurredAtは正のUNIXミリ秒で指定してください",
            )
        }

        val payload = root.optJSONObject("payload")
            ?: return rejected(
                ImportRejectionCode.MISSING_FIELD,
                "payloadはJSONオブジェクトである必要があります",
            )
        val embeddedPayloadSchema = payload.optString("schema", "").trim()
        if (embeddedPayloadSchema.isNotEmpty() && embeddedPayloadSchema != payloadSchema) {
            return rejected(
                ImportRejectionCode.PAYLOAD_SCHEMA_MISMATCH,
                "payloadSchemaとpayload.schemaが一致しません",
            )
        }

        return JournalParseResult.Accepted(
            SalesJournalEnvelope(
                schemaVersion = schemaVersion,
                minimumReaderVersion = minimumReaderVersion,
                duplicateKeyVersion = duplicateKeyVersion,
                eventId = eventId,
                duplicateImportKey = duplicateImportKey,
                eventType = eventType,
                storeId = storeId,
                terminalId = terminalId,
                businessDate = businessDate,
                aggregateId = aggregateId,
                occurredAt = occurredAt,
                payloadSchema = payloadSchema,
                payloadJson = payload.toString(),
                totalAmount = extractTotalAmount(payload),
                rawJson = rawJson,
            ),
        )
    }

    fun expectedDuplicateImportKey(
        duplicateKeyVersion: Int = SUPPORTED_DUPLICATE_KEY_VERSION,
        storeId: String,
        terminalId: String,
        businessDate: String,
        eventType: String,
        eventId: String,
    ): String {
        require(duplicateKeyVersion == SUPPORTED_DUPLICATE_KEY_VERSION)
        val source = listOf(
            duplicateKeyVersion.toString(),
            storeId,
            terminalId,
            businessDate,
            eventType,
            eventId,
        ).joinToString("|")
        return "sj$duplicateKeyVersion-${sha256(source)}"
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun validIdentifier(value: String): Boolean =
        value.length in 1..160 && value.none(Char::isISOControl)

    private fun extractTotalAmount(payload: JSONObject): Long? {
        for (field in listOf("totalAmount", "grossAmount", "amount")) {
            if (payload.has(field) && !payload.isNull(field)) {
                return payload.optLong(field)
            }
        }
        return null
    }

    private fun missing(field: String): JournalParseResult.Rejected = rejected(
        ImportRejectionCode.MISSING_FIELD,
        "必須項目がありません: $field",
    )

    private fun rejected(
        code: ImportRejectionCode,
        message: String,
    ): JournalParseResult.Rejected = JournalParseResult.Rejected(code, message)

    private fun JSONObject.requiredText(name: String): String? =
        optString(name, "").trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.requiredInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrNull()
    }
}
