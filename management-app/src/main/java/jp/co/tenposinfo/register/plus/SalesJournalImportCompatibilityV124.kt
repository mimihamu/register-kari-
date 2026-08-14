package jp.co.tenposinfo.register.plus

import android.content.Context

enum class SalesJournalImportChannelV124(val preferenceKey: String) {
    DRIVE_API("drive_api_processor_signature"),
    COMPATIBILITY_FOLDER("compatibility_folder_processor_signature"),
}

object SalesJournalProcessorSignatureV124 {
    /**
     * Bump this when parser acceptance/validation semantics change without changing
     * the declared schema/version/event/payload sets below.
     */
    const val RULE_VERSION = 1

    fun current(): String {
        val material = buildList {
            add("rule=$RULE_VERSION")
            add("schema=${SalesJournalImportContract.SCHEMA}")
            add("schemaVersion=${SalesJournalImportContract.SUPPORTED_SCHEMA_VERSION}")
            add("readerVersion=${SalesJournalImportContract.READER_VERSION}")
            add("duplicateKeyVersion=${SalesJournalImportContract.SUPPORTED_DUPLICATE_KEY_VERSION}")
            add("events=${SalesJournalImportContract.supportedEventTypes.sorted().joinToString(",")}")
            add("payloads=${SalesJournalImportContract.supportedPayloadSchemas.sorted().joinToString(",")}")
        }.joinToString("|")
        return SalesJournalImportContract.sha256(material)
    }

    fun requiresReplay(storedSignature: String?, currentSignature: String = current()): Boolean =
        storedSignature.isNullOrBlank() || storedSignature != currentSignature
}

class SalesJournalImportCompatibilityStoreV124(
    context: Context,
    private val channel: SalesJournalImportChannelV124,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_sales_journal_processor_v124",
        Context.MODE_PRIVATE,
    )

    fun requiresFullReplay(): Boolean = SalesJournalProcessorSignatureV124.requiresReplay(
        preferences.getString(channel.preferenceKey, null),
    )

    /**
     * Called only after the complete source scan/sync finishes. Synchronous commit is
     * intentional: if this write cannot be persisted, the next run safely replays again.
     */
    fun markSuccessful(): Boolean = preferences.edit()
        .putString(channel.preferenceKey, SalesJournalProcessorSignatureV124.current())
        .commit()
}
