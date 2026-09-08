from pathlib import Path

root = Path('.')

def replace_once(path: str, old: str, new: str):
    p = root / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing anchor in {path}: {old[:160]!r}')
    p.write_text(text.replace(old, new, 1))

# ---------------------------------------------------------------------------
# New composite stamp snapshot: freezes mode + exact prefix bytes at sale time.
# ---------------------------------------------------------------------------
(root / 'app/src/main/java/jp/co/tenposinfo/register/ReceiptStampSnapshotV136.kt').write_text(r'''package jp.co.tenposinfo.register

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
''')

# Image source SHA-256 is a first-class SCR-720 versioning field.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/ReceiptStampV136.kt',
    '''    fun hasImage(): Boolean = sourceFile.isFile && sourceFile.length() > 0L\n\n    fun save(settings: ReceiptStampSettingsV136): ReceiptStampSettingsV136 {''',
    '''    fun hasImage(): Boolean = sourceFile.isFile && sourceFile.length() > 0L\n\n    fun sourceSha256(): String? {\n        if (!hasImage()) return null\n        val digest = java.security.MessageDigest.getInstance("SHA-256")\n        sourceFile.inputStream().use { input ->\n            val buffer = ByteArray(16 * 1024)\n            while (true) {\n                val read = input.read(buffer)\n                if (read < 0) break\n                digest.update(buffer, 0, read)\n            }\n        }\n        return digest.digest().joinToString("") { "%02x".format(it) }\n    }\n\n    fun save(settings: ReceiptStampSettingsV136): ReceiptStampSettingsV136 {''',
)

# Receipt body can suppress the legacy plain-text issuer header when a frozen stamp prefix exists.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/Receipt.kt',
    '''    val documentFooter: String = ReceiptFooterMessagePolicyV136.DEFAULT_MESSAGE,\n)''',
    '''    val documentFooter: String = ReceiptFooterMessagePolicyV136.DEFAULT_MESSAGE,\n    val suppressStoreHeader: Boolean = false,\n)''',
)
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/Receipt.kt',
    '''        lines += center(data.storeName, width)\n        if (data.storeAddress.isNotBlank()) lines += center(data.storeAddress, width)\n        if (data.storePhone.isNotBlank()) lines += center("TEL ${data.storePhone}", width)\n        lines += center("領収書／レシート", width)''',
    '''        if (!data.suppressStoreHeader) {\n            lines += center(data.storeName, width)\n            if (data.storeAddress.isNotBlank()) lines += center(data.storeAddress, width)\n            if (data.storePhone.isNotBlank()) lines += center("TEL ${data.storePhone}", width)\n        }\n        lines += center("領収書／レシート", width)''',
)

# Freeze final stamp bytes as part of SYN-003 finalization-time payload.
syn = 'app/src/main/java/jp/co/tenposinfo/register/Syn003FrozenPrintPayloadV136.kt'
replace_once(
    syn,
    '''        documentPrintSetting: DocumentPrintSettingV136,\n    ): String {''',
    '''        documentPrintSetting: DocumentPrintSettingV136,\n        stampSnapshot: ReceiptStampSnapshotV136 = ReceiptStampSnapshotV136.none(),\n    ): String {''',
)
replace_once(
    syn,
    '''                invoiceAggregationBasis = settings.invoiceAggregationBasis,\n            ),''',
    '''                invoiceAggregationBasis = settings.invoiceAggregationBasis,\n                suppressStoreHeader = stampSnapshot.prefixBytes.isNotEmpty(),\n            ),''',
)
replace_once(
    syn,
    '''        val normalBytes = EscPosEncoder.encode(receipt(false), configuration)\n        val reprintBytes = EscPosEncoder.encode(receipt(true), configuration)''',
    '''        val normalBytes = stampSnapshot.applyToPayload(EscPosEncoder.encode(receipt(false), configuration))\n        val reprintBytes = stampSnapshot.applyToPayload(EscPosEncoder.encode(receipt(true), configuration))''',
)
replace_once(
    syn,
    '''            append("\\\"documentPrintSettingSnapshot\\\":{")''',
    '''            append("\\\"stampSnapshot\\\":{")\n            append("\\\"stampVersion\\\":").append(stampSnapshot.stampVersion).append(',')\n            append("\\\"headerMode\\\":\\\"").append(stampSnapshot.headerMode.name).append("\\\",")\n            append("\\\"imageStampVersion\\\":").append(stampSnapshot.imageStampVersion).append(',')\n            append("\\\"textStampVersion\\\":").append(stampSnapshot.textStampVersion).append(',')\n            append("\\\"sourceImageSha256\\\":\\\"").append(stampSnapshot.sourceImageSha256).append("\\\",")\n            append("\\\"prefixSha256\\\":\\\"").append(stampSnapshot.prefixSha256).append("\\\",")\n            append("\\\"prefixBase64\\\":\\\"").append(stampSnapshot.prefixBase64()).append("\\\"},")\n            append("\\\"documentPrintSettingSnapshot\\\":{")''',
)

# Thread the captured snapshot through the print-document snapshot authority.
pds = 'app/src/main/java/jp/co/tenposinfo/register/PrintDocumentSnapshotV136.kt'
replace_once(
    pds,
    '''        documentPrintSetting: DocumentPrintSettingV136,\n    ): String {''',
    '''        documentPrintSetting: DocumentPrintSettingV136,\n        stampSnapshot: ReceiptStampSnapshotV136 = ReceiptStampSnapshotV136.none(),\n    ): String {''',
)
replace_once(
    pds,
    '''            documentPrintSetting = documentPrintSetting,\n        )''',
    '''            documentPrintSetting = documentPrintSetting,\n            stampSnapshot = stampSnapshot,\n        )''',
)

# Capture mutable stamp state before entering the sale transaction, then freeze it atomically with the sale journal.
rdb = 'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt'
replace_once(
    rdb,
    '''        val saleReceiptSetting = DocumentPrintSettingsStoreV136(applicationContext)\n            .load(DocumentPrintKindV136.SALE_RECEIPT)\n        TaxEngine.validateMixedTax(items, mixedTaxPolicy)''',
    '''        val saleReceiptSetting = DocumentPrintSettingsStoreV136(applicationContext)\n            .load(DocumentPrintKindV136.SALE_RECEIPT)\n        val stampSnapshot = ReceiptStampSnapshotV136.capture(\n            applicationContext,\n            printerConfiguration.copy(paperWidthMm = paperWidthMm),\n        )\n        TaxEngine.validateMixedTax(items, mixedTaxPolicy)''',
)
replace_once(
    rdb,
    '''                documentPrintSetting = saleReceiptSetting,\n            )''',
    '''                documentPrintSetting = saleReceiptSetting,\n                stampSnapshot = stampSnapshot,\n            )''',
)

# New sales already contain exact frozen stamp bytes. Remove mutable current-setting wrappers to prevent double stamping.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt',
    '''                                val receiptGateway = ReceiptStampGatewayV136(\n                                    context = applicationContext,\n                                    delegate = rawGateway,\n                                    paperWidthMm = width,\n                                )\n                                val textStampGateway = ReceiptTextStampGatewayV136(\n                                    context = applicationContext,\n                                    delegate = receiptGateway,\n                                    configuration = configuration.copy(paperWidthMm = width),\n                                )\n                                val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(\n                                    context = applicationContext,\n                                    configuration = configuration.copy(paperWidthMm = width),\n                                    kind = PrintDeliveryJobKindV136.SALE_RECEIPT,\n                                    jobId = candidate.sourceId,\n                                    delegate = textStampGateway,\n                                )''',
    '''                                val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(\n                                    context = applicationContext,\n                                    configuration = configuration.copy(paperWidthMm = width),\n                                    kind = PrintDeliveryJobKindV136.SALE_RECEIPT,\n                                    jobId = candidate.sourceId,\n                                    delegate = rawGateway,\n                                )''',
)
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt',
    '''                        val receiptGateway = ReceiptStampGatewayV136(\n                            context = applicationContext,\n                            delegate = rawGateway,\n                            paperWidthMm = job.paperWidthMm,\n                        )\n                        val textStampGateway = ReceiptTextStampGatewayV136(\n                            context = applicationContext,\n                            delegate = receiptGateway,\n                            configuration = configuration.copy(paperWidthMm = job.paperWidthMm),\n                        )\n                        val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(\n                            context = applicationContext,\n                            configuration = configuration.copy(paperWidthMm = job.paperWidthMm),\n                            kind = PrintDeliveryJobKindV136.SALE_RECEIPT,\n                            jobId = job.sourceId,\n                            delegate = textStampGateway,\n                        )''',
    '''                        val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(\n                            context = applicationContext,\n                            configuration = configuration.copy(paperWidthMm = job.paperWidthMm),\n                            kind = PrintDeliveryJobKindV136.SALE_RECEIPT,\n                            jobId = job.sourceId,\n                            delegate = rawGateway,\n                        )''',
)

# Deterministic contract tests.
(root / 'app/src/test/java/jp/co/tenposinfo/register/V156ReceiptStampSnapshotContractTest.kt').write_text(r'''package jp.co.tenposinfo.register

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class V156ReceiptStampSnapshotContractTest {
    @Test fun textImageBothSelectExactDeterministicPrefix() {
        val image = byteArrayOf(0x11, 0x22)
        val text = byteArrayOf(0x33, 0x44)
        val common = mapOf(
            ReceiptHeaderModeV135.TEXT to text,
            ReceiptHeaderModeV135.IMAGE to image,
            ReceiptHeaderModeV135.BOTH to image + text,
        )
        common.forEach { (mode, expected) ->
            val snapshot = ReceiptStampSnapshotV136.compose(mode, image, text, 7L, 9L, "a".repeat(64))
            assertContentEquals(expected, snapshot.prefixBytes)
        }
    }

    @Test fun snapshotIdentityChangesWithModeOrComponentVersion() {
        val base = ReceiptStampSnapshotV136.compose(ReceiptHeaderModeV135.BOTH, byteArrayOf(1), byteArrayOf(2), 1L, 1L, "b".repeat(64))
        val imageChanged = ReceiptStampSnapshotV136.compose(ReceiptHeaderModeV135.BOTH, byteArrayOf(1), byteArrayOf(2), 2L, 1L, "b".repeat(64))
        val modeChanged = ReceiptStampSnapshotV136.compose(ReceiptHeaderModeV135.TEXT, byteArrayOf(1), byteArrayOf(2), 1L, 1L, "b".repeat(64))
        assertNotEquals(base.stampVersion, imageChanged.stampVersion)
        assertNotEquals(base.stampVersion, modeChanged.stampVersion)
        assertTrue(base.prefixSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun saleFinalizationOwnsStampSnapshotAndTransportDoesNotRestamp() {
        val db = source("RegisterDatabase.kt")
        val frozen = source("Syn003FrozenPrintPayloadV136.kt")
        val automatic = source("AutomaticPrintWorker.kt")
        val unified = source("UnifiedPrintQueue.kt")
        val receipt = source("Receipt.kt")
        assertTrue(db.contains("ReceiptStampSnapshotV136.capture("))
        assertTrue(db.contains("stampSnapshot = stampSnapshot"))
        assertTrue(frozen.contains("\\\"stampSnapshot\\\""))
        assertTrue(frozen.contains("sourceImageSha256"))
        assertTrue(frozen.contains("stampSnapshot.applyToPayload"))
        assertTrue(receipt.contains("suppressStoreHeader"))
        assertFalse(automatic.contains("ReceiptStampGatewayV136("))
        assertFalse(automatic.contains("ReceiptTextStampGatewayV136("))
        assertFalse(unified.contains("ReceiptStampGatewayV136("))
        assertFalse(unified.contains("ReceiptTextStampGatewayV136("))
    }

    private fun source(name: String): String = File("src/main/java/jp/co/tenposinfo/register/$name").readText()
}
''')

# Evidence doc is deliberately explicit about legacy behavior and physical-device boundary.
(root / 'docs/V1.36_RCPT_006_SCR_720_STAMP_SNAPSHOT.md').write_text(r'''# v1.36 RCPT-006 / SCR-720 店名スタンプ mode・snapshot

正式仕様 v2.5 §8.14 の版管理を実装する。

- 店舗基本設定の `ReceiptHeaderModeV135.TEXT / IMAGE / BOTH` を実印刷へ接続する。
- BOTH の既存物理順序は「画像 → 文字」を維持する。
- 選択モードに有効なスタンプが存在する場合、ReceiptRenderer の旧店舗名/住所/TELヘッダを抑止し二重印字しない。
- 売上確定前に文字設定・画像設定・元画像SHA-256・モードを取得する。
- `stampVersion`, `imageStampVersion`, `textStampVersion`, `sourceImageSha256`, `prefixSha256`, `prefixBase64` を SYN-003 frozen print snapshot に保存する。
- 通常印刷と後レシートの最終 ESC/POS バイト列へ、売上確定時のスタンプprefixを組み込んでfreezeする。
- 自動/手動送信経路は現在設定から再スタンプしない。これにより設定変更後の過去再印刷で現在ロゴへ化けることを防ぐ。
- snapshot導入前のlegacy売上は当時スタンプを復元できないため、現在スタンプを後付けせず既存本文ヘッダを維持する。

実機プリンター上の太字・倍率・画像品質・カット位置は未確認であり、実機確認済みとは扱わない。
''')

print('SCR-720 stamp snapshot patch applied')
