from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing anchor: {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1))


# 1) Document setting model/persistence/UI: formal SCR-720 document-template placement.
doc = "app/src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt"
replace_once(
    doc,
    '''data class DocumentPrintSettingV136(
    val autoPrintEnabled: Boolean = true,
    val copies: Int = 1,
    val header: String = "",
    val footer: String = "",
)''',
    '''data class DocumentPrintSettingV136(
    val autoPrintEnabled: Boolean = true,
    val copies: Int = 1,
    val header: String = "",
    val footer: String = "",
    val stampPlacement: DocumentStampPlacementV136 = DocumentStampPlacementV136.NONE,
)''',
)
replace_once(
    doc,
    '''            footer = if (kind == DocumentPrintKindV136.SALE_RECEIPT) {
                ReceiptFooterMessagePolicyV136.migrateLegacy(storedFooter)
            } else {
                storedFooter
            },
        )''',
    '''            footer = if (kind == DocumentPrintKindV136.SALE_RECEIPT) {
                ReceiptFooterMessagePolicyV136.migrateLegacy(storedFooter)
            } else {
                storedFooter
            },
            stampPlacement = DocumentStampPlacementPolicyV136.normalize(
                kind,
                runCatching {
                    DocumentStampPlacementV136.valueOf(
                        preferences.getString(
                            "${kind.storageKey}.stamp_placement",
                            DocumentStampPlacementPolicyV136.defaultFor(kind).name,
                        ).orEmpty(),
                    )
                }.getOrDefault(DocumentStampPlacementPolicyV136.defaultFor(kind)),
            ),
        )''',
)
replace_once(
    doc,
    '''            .putString("${kind.storageKey}.header", setting.header.trim().take(200))
            .putString("${kind.storageKey}.footer", normalizedFooter)
            .apply()''',
    '''            .putString("${kind.storageKey}.header", setting.header.trim().take(200))
            .putString("${kind.storageKey}.footer", normalizedFooter)
            .putString(
                "${kind.storageKey}.stamp_placement",
                DocumentStampPlacementPolicyV136.normalize(kind, setting.stampPlacement).name,
            )
            .apply()''',
)
replace_once(
    doc,
    '''    var footer by remember(selected, revision) { mutableStateOf(loaded.footer) }
    var previewPaper by remember { mutableStateOf(ReceiptPaper.MM58) }''',
    '''    var footer by remember(selected, revision) { mutableStateOf(loaded.footer) }
    var stampPlacement by remember(selected, revision) { mutableStateOf(loaded.stampPlacement) }
    var previewPaper by remember { mutableStateOf(ReceiptPaper.MM58) }''',
)
replace_once(
    doc,
    '''        header = header,
        footer = footer,
    )''',
    '''        header = header,
        footer = footer,
        stampPlacement = stampPlacement,
    )''',
)
replace_once(
    doc,
    '''        if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
            Text(
                "${ReceiptFooterMessagePolicyV136.lineCount(footer)}/${ReceiptFooterMessagePolicyV136.MAX_LOGICAL_LINES}行・中央寄せ",
                style = MaterialTheme.typography.bodySmall,
            )
            receiptFooterValidation.exceptionOrNull()?.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))''',
    '''        if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
            Text(
                "${ReceiptFooterMessagePolicyV136.lineCount(footer)}/${ReceiptFooterMessagePolicyV136.MAX_LOGICAL_LINES}行・中央寄せ",
                style = MaterialTheme.typography.bodySmall,
            )
            receiptFooterValidation.exceptionOrNull()?.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (DocumentStampPlacementPolicyV136.supports(selected)) {
            Spacer(Modifier.height(6.dp))
            Text("店名スタンプ配置（SCR-720）", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DocumentStampPlacementV136.entries.forEach { placement ->
                    OutlinedButton(
                        onClick = { stampPlacement = placement },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (stampPlacement == placement) "● ${placement.displayName}" else placement.displayName)
                    }
                }
            }
            Text(
                if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
                    "初期値: 上端。売上確定時のスタンプsnapshotへ固定します。"
                } else {
                    "初期値: 下端。領収書ジョブ作成時のスタンプsnapshotへ固定します。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))''',
)
replace_once(
    doc,
    '''                            header = header,
                            footer = footer,
                        ),''',
    '''                            header = header,
                            footer = footer,
                            stampPlacement = stampPlacement,
                        ),''',
)

# 2) Sale receipt frozen bytes honor the selected document placement.
syn = "app/src/main/java/jp/co/tenposinfo/register/Syn003FrozenPrintPayloadV136.kt"
replace_once(
    syn,
    '''        val normalBytes = stampSnapshot.applyToPayload(EscPosEncoder.encode(receipt(false), configuration))
        val reprintBytes = stampSnapshot.applyToPayload(EscPosEncoder.encode(receipt(true), configuration))''',
    '''        val stampPlacement = DocumentStampPlacementPolicyV136.normalize(
            DocumentPrintKindV136.SALE_RECEIPT,
            documentPrintSetting.stampPlacement,
        )
        val normalBytes = stampSnapshot.applyToPayload(
            EscPosEncoder.encode(receipt(false), configuration),
            stampPlacement,
        )
        val reprintBytes = stampSnapshot.applyToPayload(
            EscPosEncoder.encode(receipt(true), configuration),
            stampPlacement,
        )''',
)
replace_once(
    syn,
    '''            append("\\\"documentPrintSettingSnapshot\\\":{")
            append("\\\"copies\\\":").append(DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)).append(',')''',
    '''            append("\\\"documentPrintSettingSnapshot\\\":{")
            append("\\\"copies\\\":").append(DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)).append(',')
            append("\\\"stampPlacement\\\":\\\"").append(stampPlacement.name).append("\\\",")''',
)

# 3) Snapshot application delegates TOP/BOTTOM/NONE to one placement contract.
snapshot = "app/src/main/java/jp/co/tenposinfo/register/ReceiptStampSnapshotV136.kt"
replace_once(
    snapshot,
    '''    fun applyToPayload(payload: ByteArray): ByteArray =
        ReceiptStampPayloadComposerV136.prependToEachDocument(payload, prefixBytes)''',
    '''    fun applyToPayload(
        payload: ByteArray,
        placement: DocumentStampPlacementV136 = DocumentStampPlacementV136.TOP,
    ): ByteArray = DocumentStampPayloadComposerV136.apply(payload, prefixBytes, placement)''',
)

# 4) Receipt voucher: suppress duplicate issuer text only when a real stamp snapshot exists,
# and freeze stamp placement/bytes into every print job at creation.
voucher = "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt"
replace_once(
    voucher,
    '''    val reprintedAt: Long? = null,
    val reprintedBy: String? = null,
)''',
    '''    val reprintedAt: Long? = null,
    val reprintedBy: String? = null,
    val suppressIssuerHeader: Boolean = false,
)''',
)
replace_once(
    voucher,
    '''        val lines = mutableListOf<String>()
        lines += center(data.issuer.storeName, width)
        if (data.issuer.address.isNotBlank()) lines += center(data.issuer.address, width)
        if (data.issuer.phone.isNotBlank()) lines += center(data.issuer.phone, width)
        lines += center("【領収書】", width)''',
    '''        val lines = mutableListOf<String>()
        if (!data.suppressIssuerHeader) {
            lines += center(data.issuer.storeName, width)
            if (data.issuer.address.isNotBlank()) lines += center(data.issuer.address, width)
            if (data.issuer.phone.isNotBlank()) lines += center(data.issuer.phone, width)
        }
        lines += center("【領収書】", width)''',
)
replace_once(
    voucher,
    '''        if (!data.supplementary && data.issuer.registrationNumber.isNotBlank()) {
            lines += fit("登録番号 ${data.issuer.registrationNumber}", width)
        }''',
    '''        if (!data.supplementary && !data.suppressIssuerHeader && data.issuer.registrationNumber.isNotBlank()) {
            lines += fit("登録番号 ${data.issuer.registrationNumber}", width)
        }''',
)
replace_once(
    voucher,
    '''        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
        )
        var result: ReceiptVoucherIssueResult? = null''',
    '''        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
        )
        val documentStampSnapshot = if (documentPrintSetting.autoPrintEnabled) {
            DocumentStampJobSchemaV136.capture(
                context = appContext,
                paperWidthMm = paperWidthMm,
                setting = documentPrintSetting,
            )
        } else {
            DocumentStampJobSnapshotV136.none()
        }
        var result: ReceiptVoucherIssueResult? = null''',
)
replace_once(
    voucher,
    '''                            supplementary = plan.copies > 1,
                        ),''',
    '''                            supplementary = plan.copies > 1,
                            suppressIssuerHeader = documentStampSnapshot.hasStamp,
                        ),''',
)
replace_once(
    voucher,
    '''                                decoratedPayload,
                                now + offsetBase + copyIndex,
                            )''',
    '''                                decoratedPayload,
                                now + offsetBase + copyIndex,
                                documentStampSnapshot,
                            )''',
)
replace_once(
    voucher,
    '''        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
        )
        val payload = ReceiptVoucherRenderer.render(''',
    '''        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(
            DocumentPrintKindV136.RECEIPT_VOUCHER,
        )
        val documentStampSnapshot = DocumentStampJobSchemaV136.capture(
            context = appContext,
            paperWidthMm = paperWidthMm,
            setting = documentPrintSetting,
        )
        val payload = ReceiptVoucherRenderer.render(''',
)
replace_once(
    voucher,
    '''                reprintedAt = now,
                reprintedBy = actor,
            ),''',
    '''                reprintedAt = now,
                reprintedBy = actor,
                suppressIssuerHeader = documentStampSnapshot.hasStamp,
            ),''',
)
replace_once(
    voucher,
    '''                    add(insertDocumentPrintJob(record.id, paperWidthMm, decoratedPayload, now + copyIndex))''',
    '''                    add(
                        insertDocumentPrintJob(
                            record.id,
                            paperWidthMm,
                            decoratedPayload,
                            now + copyIndex,
                            documentStampSnapshot,
                        ),
                    )''',
)
replace_once(
    voucher,
    '''    private fun insertDocumentPrintJob(
        issuanceId: Long,
        paperWidthMm: Int,
        payload: String,
        now: Long,
    ): Long = db.insertOrThrow(''',
    '''    private fun insertDocumentPrintJob(
        issuanceId: Long,
        paperWidthMm: Int,
        payload: String,
        now: Long,
        stampSnapshot: DocumentStampJobSnapshotV136,
    ): Long = db.insertOrThrow(''',
)
replace_once(
    voucher,
    '''            put("payload_text", payload)
            put("created_at", now)''',
    '''            put("payload_text", payload)
            DocumentStampJobSchemaV136.putInto(this, stampSnapshot)
            put("created_at", now)''',
)
replace_once(
    voucher,
    '''        ensureBatchLifecycleSchema()
        OperationAuditSchemaV136.ensure(db)''',
    '''        ensureBatchLifecycleSchema()
        DocumentStampJobSchemaV136.ensure(db)
        OperationAuditSchemaV136.ensure(db)''',
)

# 5) Operation document sender applies the frozen snapshot before hashing and sending.
advanced = "app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt"
replace_once(
    advanced,
    '''        val renderedPayload = TextEscPosEncoder.encode(job.payloadText)
        PrintDocumentSnapshotSchemaV136.recordRenderedHash(
            db = db,
            table = "document_print_jobs",
            jobId = jobId,
            payload = renderedPayload,
        )
        val result = gateway.send(renderedPayload)''',
    '''        val renderedPayload = TextEscPosEncoder.encode(job.payloadText)
        val stampSnapshot = DocumentStampJobSchemaV136.load(db, jobId)
        val finalPayload = stampSnapshot.applyToPayload(renderedPayload)
        PrintDocumentSnapshotSchemaV136.recordRenderedHash(
            db = db,
            table = "document_print_jobs",
            jobId = jobId,
            payload = finalPayload,
        )
        val result = gateway.send(finalPayload)''',
)
replace_once(
    advanced,
    '''        db.execSQL("CREATE INDEX IF NOT EXISTS idx_business_sessions_status ON business_sessions(status, opened_at)")''',
    '''        DocumentStampJobSchemaV136.ensure(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_business_sessions_status ON business_sessions(status, opened_at)")''',
)

# 6) New shared placement/snapshot contract.
placement_source = r'''package jp.co.tenposinfo.register

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
'''
Path("app/src/main/java/jp/co/tenposinfo/register/DocumentStampPlacementV136.kt").write_text(placement_source)

# 7) Focused regression tests.
test_source = r'''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Scr720DocumentStampPlacementTest {
    private val start = byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x00)
    private val fullCut = byteArrayOf(0x1D, 0x56, 0x41, 0x00)
    private val partialCut = byteArrayOf(0x1D, 0x56, 0x42, 0x00)
    private val stamp = byteArrayOf(0x55, 0x66)

    @Test
    fun formalDefaultsAreReceiptTopAndVoucherBottom() {
        assertEquals(DocumentStampPlacementV136.TOP, DocumentStampPlacementPolicyV136.defaultFor(DocumentPrintKindV136.SALE_RECEIPT))
        assertEquals(DocumentStampPlacementV136.BOTTOM, DocumentStampPlacementPolicyV136.defaultFor(DocumentPrintKindV136.RECEIPT_VOUCHER))
        assertEquals(DocumentStampPlacementV136.NONE, DocumentStampPlacementPolicyV136.defaultFor(DocumentPrintKindV136.PROVISIONAL_RECEIPT))
        assertFalse(DocumentStampPlacementPolicyV136.supports(DocumentPrintKindV136.SETTLEMENT))
    }

    @Test
    fun topPlacementPreservesExistingPerDocumentPrefixContract() {
        val payload = start + byteArrayOf(0x11) + fullCut
        assertArrayEquals(
            stamp + payload,
            DocumentStampPayloadComposerV136.apply(payload, stamp, DocumentStampPlacementV136.TOP),
        )
    }

    @Test
    fun bottomPlacementIsInsertedBeforeFullAndPartialCut() {
        val full = start + byteArrayOf(0x11, 0x0A) + fullCut
        val partial = start + byteArrayOf(0x22, 0x0A) + partialCut
        assertArrayEquals(
            start + byteArrayOf(0x11, 0x0A) + stamp + fullCut,
            DocumentStampPayloadComposerV136.apply(full, stamp, DocumentStampPlacementV136.BOTTOM),
        )
        assertArrayEquals(
            start + byteArrayOf(0x22, 0x0A) + stamp + partialCut,
            DocumentStampPayloadComposerV136.apply(partial, stamp, DocumentStampPlacementV136.BOTTOM),
        )
    }

    @Test
    fun bottomPlacementAppliesToEveryPhysicalDocumentAndNoCutFallsBackToEnd() {
        val first = start + byteArrayOf(0x01) + fullCut
        val second = start + byteArrayOf(0x02) + partialCut
        assertArrayEquals(
            start + byteArrayOf(0x01) + stamp + fullCut + start + byteArrayOf(0x02) + stamp + partialCut,
            DocumentStampPayloadComposerV136.apply(first + second, stamp, DocumentStampPlacementV136.BOTTOM),
        )
        val noCut = start + byteArrayOf(0x33)
        assertArrayEquals(
            noCut + stamp,
            DocumentStampPayloadComposerV136.apply(noCut, stamp, DocumentStampPlacementV136.BOTTOM),
        )
    }

    @Test
    fun noneOrBlankStampIsByteIdentical() {
        val payload = start + byteArrayOf(0x44) + fullCut
        assertArrayEquals(payload, DocumentStampPayloadComposerV136.apply(payload, stamp, DocumentStampPlacementV136.NONE))
        assertArrayEquals(payload, DocumentStampPayloadComposerV136.apply(payload, ByteArray(0), DocumentStampPlacementV136.BOTTOM))
    }

    @Test
    fun voucherJobFreezesSnapshotAndSenderHashesFinalStampedBytes() {
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val advanced = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        val frozen = File("src/main/java/jp/co/tenposinfo/register/Syn003FrozenPrintPayloadV136.kt").readText()
        val settings = File("src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt").readText()

        assertTrue(voucher.contains("DocumentStampJobSchemaV136.capture"))
        assertTrue(voucher.contains("DocumentStampJobSchemaV136.putInto(this, stampSnapshot)"))
        assertTrue(voucher.contains("suppressIssuerHeader = documentStampSnapshot.hasStamp"))
        assertTrue(advanced.contains("val stampSnapshot = DocumentStampJobSchemaV136.load(db, jobId)"))
        assertTrue(advanced.contains("payload = finalPayload"))
        assertTrue(advanced.contains("gateway.send(finalPayload)"))
        assertTrue(frozen.contains("documentPrintSetting.stampPlacement"))
        assertTrue(frozen.contains("\\\"stampPlacement\\\""))
        assertTrue(settings.contains(".stamp_placement"))
    }

    @Test
    fun issuerHeaderSuppressionOnlyAppliesWhenStampExists() {
        val plain = ReceiptVoucherDocumentData(
            issuanceId = 1L,
            saleId = 2L,
            sequenceNo = 1,
            sequenceCount = 1,
            amount = 1_000L,
            addressee = "",
            purpose = "飲食代",
            operatorName = "担当",
            issuedAt = 0L,
            issuer = InvoiceIssuerProfile(
                storeName = "つぐレジ店",
                address = "住所",
                phone = "03-0000-0000",
                registrationNumber = "T1234567890123",
            ),
        )
        val stamped = plain.copy(suppressIssuerHeader = true)
        val plainText = ReceiptVoucherRenderer.render(plain, ReceiptPaper.MM58)
        val stampedText = ReceiptVoucherRenderer.render(stamped, ReceiptPaper.MM58)
        assertTrue(plainText.contains("つぐレジ店"))
        assertTrue(plainText.contains("登録番号"))
        assertFalse(stampedText.contains("つぐレジ店"))
        assertFalse(stampedText.contains("登録番号 T1234567890123"))
        assertTrue(stampedText.contains("【領収書】"))
    }
}
'''
Path("app/src/test/java/jp/co/tenposinfo/register/V136Scr720DocumentStampPlacementTest.kt").write_text(test_source)

# Remove one-shot helpers in the implementation commit.
Path(".github/v136-scr720-placement-apply.py").unlink(missing_ok=True)
Path(".github/workflows/v136-scr720-placement-apply.yml").unlink(missing_ok=True)
print("SCR-720 document stamp placement patch applied")
