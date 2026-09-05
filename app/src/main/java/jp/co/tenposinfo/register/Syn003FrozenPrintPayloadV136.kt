package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase
import java.util.Base64

/**
 * Formal v2.5 SYN-003.
 *
 * The structured print document remains the authority. Rendering-affecting printer/profile
 * state and deterministic rendered derivatives are frozen at sale finalization so that
 * queued printing and reprints never have to consult mutable masters/settings again.
 */
object Syn003FrozenPrintPayloadV136 {
    const val LAYOUT_VERSION = "receipt-v1.36"
    private const val NORMAL_KEY = "renderedPayloadBase64"
    private const val REPRINT_KEY = "reprintPayloadBase64"

    fun freezeSalePayload(
        payloadJson: String,
        saleId: Long,
        issuedAt: Long,
        operatorName: String,
        items: List<CartItem>,
        taxSummary: TaxSummary,
        payments: List<PaymentAllocation>,
        changeAmount: Long,
        settings: TaxInvoiceSettings,
        printerConfiguration: PrinterConfiguration,
        documentPrintSetting: DocumentPrintSettingV136,
    ): String {
        if (payloadJson.contains("\"syn003FrozenPrint\"")) return payloadJson
        val configuration = printerConfiguration.copy(
            paperWidthMm = PrinterPaperSettingPolicy.normalizeWidthMm(printerConfiguration.paperWidthMm),
        )
        val issuer = settings.issuer
        fun receipt(reprint: Boolean): ReceiptData = DocumentPrintSettingsPolicyV136.applyToReceipt(
            ReceiptData(
                storeName = issuer.storeName,
                storeAddress = issuer.address,
                storePhone = issuer.phone,
                registrationNumber = issuer.registrationNumber,
                saleId = saleId,
                createdAt = issuedAt,
                operatorName = operatorName,
                items = items,
                taxSummary = taxSummary,
                payments = payments,
                changeAmount = changeAmount,
                reprint = reprint,
                invoiceAggregationBasis = settings.invoiceAggregationBasis,
            ),
            documentPrintSetting,
        )
        val normalBytes = EscPosEncoder.encode(receipt(false), configuration)
        val reprintBytes = EscPosEncoder.encode(receipt(true), configuration)
        val documentId = "SALE_RECEIPT:$saleId:$issuedAt"
        val frozen = buildString {
            append("\"syn003FrozenPrint\":{")
            append("\"documentId\":\"").append(escape(documentId)).append("\",")
            append("\"layoutVersion\":\"").append(LAYOUT_VERSION).append("\",")
            append("\"printerProfileSnapshot\":{")
            append("\"profile\":\"").append(configuration.profile.name).append("\",")
            append("\"charsetName\":\"").append(escape(configuration.profile.charsetName)).append("\",")
            append("\"codeTable\":").append(configuration.profile.codeTable).append(',')
            append("\"kanjiCodeSystem\":")
                .append(configuration.profile.kanjiCodeSystem?.toString() ?: "null").append(',')
            append("\"paperWidthMm\":").append(configuration.paperWidthMm).append(',')
            append("\"printableDotWidth\":").append(configuration.printableDotWidth).append(',')
            append("\"cutMode\":\"").append(configuration.cutMode.name).append("\",")
            append("\"feedLines\":").append(configuration.feedLines)
            append("},")
            append("\"documentPrintSettingSnapshot\":{")
            append("\"copies\":").append(DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)).append(',')
            append("\"header\":\"").append(escape(documentPrintSetting.header.trim())).append("\",")
            append("\"footer\":\"")
                .append(escape(ReceiptFooterMessagePolicyV136.migrateLegacy(documentPrintSetting.footer))).append("\"},")
            append("\"normalSha256\":\"").append(PrintDocumentSnapshotV136.sha256Hex(normalBytes)).append("\",")
            append("\"reprintSha256\":\"").append(PrintDocumentSnapshotV136.sha256Hex(reprintBytes)).append("\",")
            append("\"").append(NORMAL_KEY).append("\":\"")
                .append(Base64.getEncoder().encodeToString(normalBytes)).append("\",")
            append("\"").append(REPRINT_KEY).append("\":\"")
                .append(Base64.getEncoder().encodeToString(reprintBytes)).append("\"")
            append('}')
        }
        val trimmed = payloadJson.trim()
        return if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            buildString {
                append('{')
                if (body.isNotBlank()) append(body).append(',')
                append(frozen)
                append('}')
            }
        } else {
            "{$frozen}"
        }
    }

    /** Returns the exact finalization-time bytes for SYN-003 jobs; null means legacy fallback. */
    fun loadJobPayload(
        db: SQLiteDatabase,
        jobId: Long,
        saleId: Long,
        reprint: Boolean,
    ): ByteArray? {
        val jobPayload = db.rawQuery(
            "SELECT payload_json FROM print_jobs WHERE id = ? LIMIT 1",
            arrayOf(jobId.toString()),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
        val authoritative = if (jobPayload?.contains("\"syn003FrozenPrint\"") == true) {
            jobPayload
        } else {
            db.rawQuery(
                "SELECT payload_json FROM sales_journal WHERE event_type = ? AND aggregate_id = ? ORDER BY created_at DESC LIMIT 1",
                arrayOf(JournalEventType.SALE.name, saleId.toString()),
            ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
        } ?: return null
        val encoded = extractString(authoritative, if (reprint) REPRINT_KEY else NORMAL_KEY) ?: return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    private fun extractString(json: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = json.indexOf('"', valueStart)
        if (end < valueStart) return null
        return json.substring(valueStart, end)
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
