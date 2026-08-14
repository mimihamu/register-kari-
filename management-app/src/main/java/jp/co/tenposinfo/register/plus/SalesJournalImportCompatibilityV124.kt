package jp.co.tenposinfo.register.plus

import android.content.Context
import android.database.sqlite.SQLiteDatabase

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

object SalesJournalImportCompatibilityResetV124 {
    private const val PREFERENCES_NAME = "tsuguregi_plus_sales_journal_processor_v124"
    private const val KEY_PROCESSOR_SIGNATURE = "processor_signature"

    /**
     * Importer compatibility is shared by Drive API and compatibility-folder imports.
     * When the accepted contract changes, only transport fingerprints are invalidated.
     * Imported sales, import runs and rejection audit rows are never deleted here.
     *
     * The DB transaction is committed before the preference signature. If the process
     * dies in between, the next DB open repeats the harmless fingerprint reset.
     * Synchronization prevents concurrent ManagementDatabase opens in this process from
     * performing overlapping first-open resets.
     */
    @Synchronized
    fun ensureCurrent(context: Context, db: SQLiteDatabase): Boolean {
        if (db.isReadOnly) return false
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val currentSignature = SalesJournalProcessorSignatureV124.current()
        val storedSignature = preferences.getString(KEY_PROCESSOR_SIGNATURE, null)
        if (!SalesJournalProcessorSignatureV124.requiresReplay(storedSignature, currentSignature)) {
            return false
        }

        db.beginTransaction()
        try {
            db.delete("drive_sync_files", null, null)
            db.delete("folder_import_files", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // commit(), not apply(): if persistence fails we intentionally retry the reset.
        preferences.edit().putString(KEY_PROCESSOR_SIGNATURE, currentSignature).commit()
        return true
    }
}
