package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.util.Base64

enum class DocumentStampPlacementV136(val displayName: String) {
    NONE("なし"),
    TOP("上端"),
    BOTTOM("下端"),
}

/** Formal v2.5 §8.14 SCR-720 document-template assignment. */
object DocumentStampPlacementPolicyV136 {
    fun defaultFor(kind: DocumentPrintKindV136): DocumentStampPlacementV136 = when (kind) {
        DocumentPrintKindV136.SALE_RECEIPT -> DocumentStampPlacementV136.TOP
        DocumentPrintKindV136.RECEIPT_VOUCHER -> DocumentStampPlacementV136.BOTTOM
        DocumentPrintKindV136.PROVISIONAL_RECEIPT,
        DocumentPrintKindV136.INSPECTION,
        DocumentPrintKindV136.SETTLEMENT,
        -> DocumentStampPlacementV136.NONE
    }

    fun supports(kind: DocumentPrintKindV136): Boolean =
        kind == DocumentPrintKindV136.SALE_RECEIPT || kind == DocumentPrintKindV136.RECEIPT_VOUCHER

    fun normalize(
        kind: DocumentPrintKindV136,
        placement: DocumentStampPlacementV136,
    ): DocumentStampPlacementV136 = if (supports(kind)) placement else DocumentStampPlacementV136.NONE
}

/** Applies one already-rendered stamp byte snapshot to every physical document in a payload. */
object DocumentStampPayloadComposerV136 {
    private val documentMarker = byteArrayOf(0x1B, 0x40, 0x1B, 0x74)
    private val cutCommands = listOf(
        byteArrayOf(0x1D, 0x56, 0x41, 0x00),
        byteArrayOf(0x1D, 0x56, 0x42, 0x00),
        byteArrayOf(0x1D, 0x56, 0x00),
        byteArrayOf(0x1D, 0x56, 0x01),
    )

    fun apply(
        payload: ByteArray,
        stamp: ByteArray,
        placement: DocumentStampPlacementV136,
    ): ByteArray = when (placement) {
        DocumentStampPlacementV136.NONE -> payload.copyOf()
        DocumentStampPlacementV136.TOP -> ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp)
        DocumentStampPlacementV136.BOTTOM -> appendToEachDocument(payload, stamp)
    }

    fun appendToEachDocument(payload: ByteArray, suffix: ByteArray): ByteArray {
        if (suffix.isEmpty() || payload.isEmpty()) return payload.copyOf()
        val starts = documentStarts(payload)
        if (starts.isEmpty()) return appendBeforeCut(payload, suffix)

        val output = ByteArrayOutputStream(payload.size + suffix.size * starts.size)
        if (starts.first() > 0) output.write(payload, 0, starts.first())
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: payload.size
            val document = payload.copyOfRange(start, end)
            val composed = appendBeforeCut(document, suffix)
            output.write(composed)
        }
        return output.toByteArray()
    }

    private fun appendBeforeCut(document: ByteArray, suffix: ByteArray): ByteArray {
        val cutAt = findLastCut(document)
        if (cutAt < 0) return document + suffix
        val output = ByteArrayOutputStream(document.size + suffix.size)
        output.write(document, 0, cutAt)
        output.write(suffix)
        output.write(document, cutAt, document.size - cutAt)
        return output.toByteArray()
    }

    private fun documentStarts(payload: ByteArray): List<Int> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= payload.size - documentMarker.size) {
            if (matches(payload, index, documentMarker)) {
                starts += index
                index += documentMarker.size
            } else {
                index++
            }
        }
        return starts
    }

    private fun findLastCut(payload: ByteArray): Int {
        var found = -1
        cutCommands.forEach { command ->
            var index = 0
            while (index <= payload.size - command.size) {
                if (matches(payload, index, command)) found = maxOf(found, index)
                index++
            }
        }
        return found
    }

    private fun matches(payload: ByteArray, offset: Int, marker: ByteArray): Boolean {
        for (index in marker.indices) if (payload[offset + index] != marker[index]) return false
        return true
    }
}

data class DocumentStampJobSnapshotV136(
    val placement: DocumentStampPlacementV136,
    val stampVersion: Long,
    val sourceImageSha256: String,
    val prefixSha256: String,
    val prefixBytes: ByteArray,
) {
    val hasStamp: Boolean get() = placement != DocumentStampPlacementV136.NONE && prefixBytes.isNotEmpty()

    fun applyToPayload(payload: ByteArray): ByteArray =
        DocumentStampPayloadComposerV136.apply(payload, prefixBytes, if (hasStamp) placement else DocumentStampPlacementV136.NONE)

    companion object {
        fun none(): DocumentStampJobSnapshotV136 = DocumentStampJobSnapshotV136(
            placement = DocumentStampPlacementV136.NONE,
            stampVersion = 0L,
            sourceImageSha256 = "",
            prefixSha256 = "",
            prefixBytes = ByteArray(0),
        )
    }
}

/**
 * New operation-document jobs freeze SCR-720 bytes at enqueue time.
 * Legacy rows migrate to NONE/empty so retries remain byte-compatible with their original payload.
 */
object DocumentStampJobSchemaV136 {
    private const val COL_PLACEMENT = "stamp_placement"
    private const val COL_VERSION = "stamp_version"
    private const val COL_SOURCE_SHA = "stamp_source_sha256"
    private const val COL_PREFIX_SHA = "stamp_prefix_sha256"
    private const val COL_PREFIX_BASE64 = "stamp_prefix_base64"

    fun ensure(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(document_print_jobs)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        if (COL_PLACEMENT !in columns) {
            db.execSQL("ALTER TABLE document_print_jobs ADD COLUMN $COL_PLACEMENT TEXT NOT NULL DEFAULT 'NONE'")
        }
        if (COL_VERSION !in columns) {
            db.execSQL("ALTER TABLE document_print_jobs ADD COLUMN $COL_VERSION INTEGER NOT NULL DEFAULT 0")
        }
        if (COL_SOURCE_SHA !in columns) {
            db.execSQL("ALTER TABLE document_print_jobs ADD COLUMN $COL_SOURCE_SHA TEXT NOT NULL DEFAULT ''")
        }
        if (COL_PREFIX_SHA !in columns) {
            db.execSQL("ALTER TABLE document_print_jobs ADD COLUMN $COL_PREFIX_SHA TEXT NOT NULL DEFAULT ''")
        }
        if (COL_PREFIX_BASE64 !in columns) {
            db.execSQL("ALTER TABLE document_print_jobs ADD COLUMN $COL_PREFIX_BASE64 TEXT NOT NULL DEFAULT ''")
        }
    }

    fun capture(
        context: Context,
        paperWidthMm: Int,
        setting: DocumentPrintSettingV136,
    ): DocumentStampJobSnapshotV136 {
        val placement = DocumentStampPlacementPolicyV136.normalize(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
            setting.stampPlacement,
        )
        if (placement == DocumentStampPlacementV136.NONE) return DocumentStampJobSnapshotV136.none()

        val appContext = context.applicationContext
        val settingsStore = AdminSettingsStore(appContext)
        val configuration = try {
            settingsStore.loadPrinterConfiguration().copy(
                paperWidthMm = PrinterPaperSettingPolicy.normalizeWidthMm(paperWidthMm),
            )
        } finally {
            settingsStore.close()
        }
        val stamp = ReceiptStampSnapshotV136.capture(appContext, configuration)
        if (stamp.prefixBytes.isEmpty()) return DocumentStampJobSnapshotV136.none()
        return DocumentStampJobSnapshotV136(
            placement = placement,
            stampVersion = stamp.stampVersion,
            sourceImageSha256 = stamp.sourceImageSha256,
            prefixSha256 = stamp.prefixSha256,
            prefixBytes = stamp.prefixBytes.copyOf(),
        )
    }

    fun putInto(values: ContentValues, snapshot: DocumentStampJobSnapshotV136) {
        values.put(COL_PLACEMENT, snapshot.placement.name)
        values.put(COL_VERSION, snapshot.stampVersion)
        values.put(COL_SOURCE_SHA, snapshot.sourceImageSha256)
        values.put(COL_PREFIX_SHA, snapshot.prefixSha256)
        values.put(COL_PREFIX_BASE64, Base64.getEncoder().encodeToString(snapshot.prefixBytes))
    }

    fun load(db: SQLiteDatabase, jobId: Long): DocumentStampJobSnapshotV136 {
        ensure(db)
        return db.query(
            "document_print_jobs",
            arrayOf(COL_PLACEMENT, COL_VERSION, COL_SOURCE_SHA, COL_PREFIX_SHA, COL_PREFIX_BASE64),
            "id = ?",
            arrayOf(jobId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use DocumentStampJobSnapshotV136.none()
            val placement = runCatching { DocumentStampPlacementV136.valueOf(cursor.getString(0)) }
                .getOrDefault(DocumentStampPlacementV136.NONE)
            val prefix = runCatching { Base64.getDecoder().decode(cursor.getString(4).orEmpty()) }
                .getOrDefault(ByteArray(0))
            val storedSha = cursor.getString(3).orEmpty().lowercase()
            if (prefix.isNotEmpty() && storedSha.isNotBlank()) {
                require(PrintDocumentSnapshotV136.sha256Hex(prefix) == storedSha) {
                    "文書スタンプsnapshotのハッシュが一致しません"
                }
            }
            DocumentStampJobSnapshotV136(
                placement = if (prefix.isEmpty()) DocumentStampPlacementV136.NONE else placement,
                stampVersion = cursor.getLong(1).coerceAtLeast(0L),
                sourceImageSha256 = cursor.getString(2).orEmpty(),
                prefixSha256 = storedSha,
                prefixBytes = prefix,
            )
        }
    }
}
