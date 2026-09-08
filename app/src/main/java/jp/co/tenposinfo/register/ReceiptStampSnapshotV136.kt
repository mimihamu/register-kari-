package jp.co.tenposinfo.register

import android.content.Context
import java.util.Base64

/**
 * Formal v2.5 §8.14 SCR-720 stamp snapshot.
 *
 * The exact ESC/POS prefix is frozen at sale finalization. New print jobs therefore never
 * consult mutable TEXT/IMAGE/BOTH, text-line, or image settings when a historical receipt
 * is printed or reprinted.
 */
data class ReceiptStampSnapshotV136(
    val stampVersion: Long,
    val headerMode: ReceiptHeaderModeV135,
    val imageStampVersion: Long,
    val textStampVersion: Long,
    val sourceImageSha256: String,
    val prefixBytes: ByteArray,
) {
    val prefixSha256: String
        get() = if (prefixBytes.isEmpty()) "" else PrintDocumentSnapshotV136.sha256Hex(prefixBytes)

    fun applyToPayload(payload: ByteArray): ByteArray =
        ReceiptStampPayloadComposerV136.prependToEachDocument(payload, prefixBytes)

    fun prefixBase64(): String = Base64.getEncoder().encodeToString(prefixBytes)

    companion object {
        fun none(): ReceiptStampSnapshotV136 = ReceiptStampSnapshotV136(
            stampVersion = 0L,
            headerMode = ReceiptHeaderModeV135.TEXT,
            imageStampVersion = 0L,
            textStampVersion = 0L,
            sourceImageSha256 = "",
            prefixBytes = ByteArray(0),
        )

        fun compose(
            mode: ReceiptHeaderModeV135,
            imagePrefix: ByteArray,
            textPrefix: ByteArray,
            imageStampVersion: Long,
            textStampVersion: Long,
            sourceImageSha256: String,
        ): ReceiptStampSnapshotV136 {
            val prefix = when (mode) {
                ReceiptHeaderModeV135.TEXT -> textPrefix
                ReceiptHeaderModeV135.IMAGE -> imagePrefix
                // Preserve the existing v1.36 physical order: image first, then text.
                ReceiptHeaderModeV135.BOTH -> imagePrefix + textPrefix
            }.copyOf()
            return ReceiptStampSnapshotV136(
                stampVersion = compositeVersion(imageStampVersion, textStampVersion, mode),
                headerMode = mode,
                imageStampVersion = imageStampVersion.coerceAtLeast(0L),
                textStampVersion = textStampVersion.coerceAtLeast(0L),
                sourceImageSha256 = sourceImageSha256.lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }.orEmpty(),
                prefixBytes = prefix,
            )
        }

        fun capture(context: Context, configuration: PrinterConfiguration): ReceiptStampSnapshotV136 {
            val appContext = context.applicationContext
            val storeSettings = InitialReleaseSettingsStoreV135(appContext).loadStore()
            val imageStore = ReceiptStampSettingsStoreV136(appContext)
            val imageSettings = imageStore.load()
            val textStore = ReceiptTextStampSettingsStoreV136(appContext)
            val textSettings = textStore.load()
            val paper = ReceiptPaper.fromWidth(configuration.paperWidthMm)
            val imagePrefix = imageStore.printPrefix(paper)
            val textPrefix = ReceiptTextStampEscPosV136.encode(textStore.resolvedLines(), configuration)
            return compose(
                mode = storeSettings.receiptHeaderMode,
                imagePrefix = imagePrefix,
                textPrefix = textPrefix,
                imageStampVersion = imageSettings.stampVersion,
                textStampVersion = textSettings.stampVersion,
                sourceImageSha256 = imageStore.sourceSha256().orEmpty(),
            )
        }

        private fun compositeVersion(image: Long, text: Long, mode: ReceiptHeaderModeV135): Long {
            val value = image.coerceAtLeast(0L) * 1_000_003L + text.coerceAtLeast(0L) * 97L + mode.ordinal
            return value and Long.MAX_VALUE
        }
    }
}
