package jp.co.tenposinfo.register.plus

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class ImportFolderRegistration(
    val treeUri: String,
    val displayName: String,
)

data class ImportFolderScanSummary(
    val scannedAt: Long,
    val discoveredJsonCount: Int,
    val changedJsonCount: Int,
    val unchangedJsonCount: Int,
    val skippedNonJsonCount: Int,
    val readErrorCount: Int,
    val forceRescan: Boolean,
)

data class ImportFolderUiState(
    val registration: ImportFolderRegistration? = null,
    val scanning: Boolean = false,
    val lastSummary: ImportFolderScanSummary? = null,
    val errorMessage: String? = null,
)

data class FolderImportFileMark(
    val sourceUri: String,
    val displayName: String,
    val contentSha256: String,
)

data class ImportFolderScanResult(
    val documents: List<SalesJournalImportDocument>,
    val processedFiles: List<FolderImportFileMark>,
    val summary: ImportFolderScanSummary,
)

object ImportFolderPolicy {
    const val MAX_RECURSION_DEPTH = 12
    const val MAX_JSON_DOCUMENTS = 5_000

    fun isJsonDocument(displayName: String, mimeType: String?): Boolean {
        val lowerName = displayName.lowercase()
        return lowerName.endsWith(".json") ||
            mimeType == "application/json" ||
            mimeType == "text/json"
    }

    fun shouldProcess(
        knownContentSha256: String?,
        currentContentSha256: String,
        forceRescan: Boolean,
    ): Boolean = forceRescan || knownContentSha256 != currentContentSha256

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}

class ImportFolderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun registration(): ImportFolderRegistration? {
        val treeUri = preferences.getString(KEY_TREE_URI, null)?.takeIf(String::isNotBlank)
            ?: return null
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)
            ?.takeIf(String::isNotBlank)
            ?: "登録フォルダ"
        return ImportFolderRegistration(treeUri, displayName)
    }

    fun saveRegistration(registration: ImportFolderRegistration) {
        preferences.edit()
            .putString(KEY_TREE_URI, registration.treeUri)
            .putString(KEY_DISPLAY_NAME, registration.displayName)
            .apply()
    }

    fun clearRegistration() {
        preferences.edit()
            .remove(KEY_TREE_URI)
            .remove(KEY_DISPLAY_NAME)
            .apply()
    }

    fun lastSummary(): ImportFolderScanSummary? {
        val scannedAt = preferences.getLong(KEY_LAST_SCANNED_AT, 0L)
        if (scannedAt <= 0L) return null
        return ImportFolderScanSummary(
            scannedAt = scannedAt,
            discoveredJsonCount = preferences.getInt(KEY_DISCOVERED_COUNT, 0),
            changedJsonCount = preferences.getInt(KEY_CHANGED_COUNT, 0),
            unchangedJsonCount = preferences.getInt(KEY_UNCHANGED_COUNT, 0),
            skippedNonJsonCount = preferences.getInt(KEY_NON_JSON_COUNT, 0),
            readErrorCount = preferences.getInt(KEY_READ_ERROR_COUNT, 0),
            forceRescan = preferences.getBoolean(KEY_FORCE_RESCAN, false),
        )
    }

    fun saveLastSummary(summary: ImportFolderScanSummary) {
        preferences.edit()
            .putLong(KEY_LAST_SCANNED_AT, summary.scannedAt)
            .putInt(KEY_DISCOVERED_COUNT, summary.discoveredJsonCount)
            .putInt(KEY_CHANGED_COUNT, summary.changedJsonCount)
            .putInt(KEY_UNCHANGED_COUNT, summary.unchangedJsonCount)
            .putInt(KEY_NON_JSON_COUNT, summary.skippedNonJsonCount)
            .putInt(KEY_READ_ERROR_COUNT, summary.readErrorCount)
            .putBoolean(KEY_FORCE_RESCAN, summary.forceRescan)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "tsuguregi_plus_import_folder"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_LAST_SCANNED_AT = "last_scanned_at"
        private const val KEY_DISCOVERED_COUNT = "discovered_count"
        private const val KEY_CHANGED_COUNT = "changed_count"
        private const val KEY_UNCHANGED_COUNT = "unchanged_count"
        private const val KEY_NON_JSON_COUNT = "non_json_count"
        private const val KEY_READ_ERROR_COUNT = "read_error_count"
        private const val KEY_FORCE_RESCAN = "force_rescan"
    }
}

class SalesJournalDocumentSource(
    private val contentResolver: ContentResolver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun persistFolderPermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    fun releaseFolderPermission(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun folderDisplayName(treeUri: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(String::isNotBlank)
            ?: treeUri.lastPathSegment
            ?: "登録フォルダ"
    }

    fun readSingle(uri: Uri, fallbackName: String): SalesJournalImportDocument =
        readDocument(uri, fallbackName).document

    fun scanFolder(
        treeUri: Uri,
        knownFingerprints: Map<String, String>,
        forceRescan: Boolean,
    ): ImportFolderScanResult {
        val candidates = mutableListOf<FolderDocumentCandidate>()
        var skippedNonJsonCount = 0
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrElse { error ->
            throw IllegalStateException("登録フォルダを開けません: ${error.message}", error)
        }

        fun collect(parentDocumentId: String, depth: Int) {
            require(depth <= ImportFolderPolicy.MAX_RECURSION_DEPTH) {
                "フォルダ階層が深すぎます（上限${ImportFolderPolicy.MAX_RECURSION_DEPTH}階層）"
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId,
            )
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val displayName = cursor.getString(1) ?: "名称不明"
                    val mimeType = cursor.getString(2)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        collect(documentId, depth + 1)
                    } else if (ImportFolderPolicy.isJsonDocument(displayName, mimeType)) {
                        require(candidates.size < ImportFolderPolicy.MAX_JSON_DOCUMENTS) {
                            "JSONファイルが多すぎます（上限${ImportFolderPolicy.MAX_JSON_DOCUMENTS}件）"
                        }
                        candidates += FolderDocumentCandidate(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            displayName = displayName,
                        )
                    } else {
                        skippedNonJsonCount += 1
                    }
                }
            } ?: throw IllegalStateException("フォルダ内容を取得できません")
        }

        collect(rootDocumentId, 0)

        val documents = mutableListOf<SalesJournalImportDocument>()
        val processedFiles = mutableListOf<FolderImportFileMark>()
        var changedCount = 0
        var unchangedCount = 0
        var readErrorCount = 0

        candidates.forEach { candidate ->
            val read = readDocument(candidate.uri, candidate.displayName)
            val fingerprint = read.contentSha256
            if (fingerprint == null) {
                documents += read.document
                readErrorCount += 1
                return@forEach
            }

            processedFiles += FolderImportFileMark(
                sourceUri = candidate.uri.toString(),
                displayName = candidate.displayName,
                contentSha256 = fingerprint,
            )
            if (
                ImportFolderPolicy.shouldProcess(
                    knownContentSha256 = knownFingerprints[candidate.uri.toString()],
                    currentContentSha256 = fingerprint,
                    forceRescan = forceRescan,
                )
            ) {
                documents += read.document
                changedCount += 1
            } else {
                unchangedCount += 1
            }
        }

        return ImportFolderScanResult(
            documents = documents,
            processedFiles = processedFiles,
            summary = ImportFolderScanSummary(
                scannedAt = nowMillis(),
                discoveredJsonCount = candidates.size,
                changedJsonCount = changedCount,
                unchangedJsonCount = unchangedCount,
                skippedNonJsonCount = skippedNonJsonCount,
                readErrorCount = readErrorCount,
                forceRescan = forceRescan,
            ),
        )
    }

    private fun readDocument(uri: Uri, fallbackName: String): ReadDocumentResult {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
                        throw DocumentTooLargeException()
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("入力ストリームを開けません")
            ReadDocumentResult(
                document = SalesJournalImportDocument(
                    sourceName = fallbackName,
                    sourceUri = uri.toString(),
                    rawJson = bytes.toString(Charsets.UTF_8),
                ),
                contentSha256 = ImportFolderPolicy.sha256(bytes),
            )
        } catch (_: DocumentTooLargeException) {
            ReadDocumentResult(
                document = SalesJournalImportDocument(
                    sourceName = fallbackName,
                    sourceUri = uri.toString(),
                    rawJson = null,
                    loadErrorCode = ImportRejectionCode.DOCUMENT_TOO_LARGE,
                    loadErrorMessage = "JSONファイルが20MiBを超えています",
                ),
                contentSha256 = null,
            )
        } catch (error: Exception) {
            ReadDocumentResult(
                document = SalesJournalImportDocument(
                    sourceName = fallbackName,
                    sourceUri = uri.toString(),
                    rawJson = null,
                    loadErrorCode = ImportRejectionCode.READ_ERROR,
                    loadErrorMessage = error.message ?: "ファイルを読み込めませんでした",
                ),
                contentSha256 = null,
            )
        }
    }

    private data class FolderDocumentCandidate(
        val uri: Uri,
        val displayName: String,
    )

    private data class ReadDocumentResult(
        val document: SalesJournalImportDocument,
        val contentSha256: String?,
    )

    private class DocumentTooLargeException : IllegalArgumentException()
}
