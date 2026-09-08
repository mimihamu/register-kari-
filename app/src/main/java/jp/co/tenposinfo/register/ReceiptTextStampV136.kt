package jp.co.tenposinfo.register

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

enum class ReceiptTextStampFieldV136(val displayName: String) {
    STORE_NAME("店舗名"),
    BRANCH_NAME("支店名"),
    ADDRESS("住所"),
    PHONE("電話"),
    REGISTRATION_NUMBER("登録番号"),
}

enum class ReceiptTextStampAlignmentV136(val displayName: String, val escPosValue: Int) {
    LEFT("左揃え", 0),
    CENTER("中央揃え", 1),
}

data class ReceiptTextStampLineSettingV136(
    val field: ReceiptTextStampFieldV136,
    val enabled: Boolean,
    val alignment: ReceiptTextStampAlignmentV136 = ReceiptTextStampAlignmentV136.CENTER,
    val bold: Boolean = false,
    val magnification: Int = 1,
)

data class ReceiptTextStampSettingsV136(
    val lines: List<ReceiptTextStampLineSettingV136> = ReceiptTextStampPolicyV136.defaults(),
    val stampVersion: Long = 0L,
)

data class ReceiptTextStampResolvedLineV136(
    val field: ReceiptTextStampFieldV136,
    val text: String,
    val alignment: ReceiptTextStampAlignmentV136,
    val bold: Boolean,
    val magnification: Int,
)

/** 正式仕様 v2.5 §8.14 SCR-720 文字スタンプ。 */
object ReceiptTextStampPolicyV136 {
    const val MIN_MAGNIFICATION = 1
    const val MAX_MAGNIFICATION = 4

    fun defaults(): List<ReceiptTextStampLineSettingV136> = ReceiptTextStampFieldV136.entries.map { field ->
        ReceiptTextStampLineSettingV136(
            field = field,
            enabled = when (field) {
                ReceiptTextStampFieldV136.STORE_NAME,
                ReceiptTextStampFieldV136.ADDRESS,
                ReceiptTextStampFieldV136.PHONE,
                ReceiptTextStampFieldV136.REGISTRATION_NUMBER,
                -> true
                ReceiptTextStampFieldV136.BRANCH_NAME -> false
            },
            alignment = ReceiptTextStampAlignmentV136.CENTER,
            bold = field == ReceiptTextStampFieldV136.STORE_NAME,
            magnification = if (field == ReceiptTextStampFieldV136.STORE_NAME) 2 else 1,
        )
    }

    fun normalize(settings: ReceiptTextStampSettingsV136): ReceiptTextStampSettingsV136 {
        val byField = settings.lines.associateBy { it.field }
        val normalized = ReceiptTextStampFieldV136.entries.map { field ->
            val value = byField[field] ?: defaults().first { it.field == field }
            value.copy(magnification = value.magnification.coerceIn(MIN_MAGNIFICATION, MAX_MAGNIFICATION))
        }
        return settings.copy(lines = normalized, stampVersion = settings.stampVersion.coerceAtLeast(0L))
    }

    fun resolve(
        settings: ReceiptTextStampSettingsV136,
        store: StoreBasicSettingsV135,
    ): List<ReceiptTextStampResolvedLineV136> {
        val values = mapOf(
            ReceiptTextStampFieldV136.STORE_NAME to store.storeName.trim(),
            ReceiptTextStampFieldV136.BRANCH_NAME to store.branchName.trim(),
            ReceiptTextStampFieldV136.ADDRESS to listOf(store.postalCode, store.address1, store.address2)
                .map(String::trim).filter(String::isNotBlank).joinToString(" "),
            ReceiptTextStampFieldV136.PHONE to store.phone.trim().takeIf(String::isNotBlank)?.let { "TEL $it" }.orEmpty(),
            ReceiptTextStampFieldV136.REGISTRATION_NUMBER to store.registrationNumber.trim()
                .takeIf(String::isNotBlank)?.let { "登録番号 $it" }.orEmpty(),
        )
        return normalize(settings).lines.mapNotNull { setting ->
            val text = values[setting.field].orEmpty()
            if (!setting.enabled || text.isBlank()) null else ReceiptTextStampResolvedLineV136(
                field = setting.field,
                text = text,
                alignment = setting.alignment,
                bold = setting.bold,
                magnification = setting.magnification,
            )
        }
    }
}

class ReceiptTextStampSettingsStoreV136(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("receipt_text_stamp_settings_v136", Context.MODE_PRIVATE)

    fun load(): ReceiptTextStampSettingsV136 {
        val defaults = ReceiptTextStampPolicyV136.defaults().associateBy { it.field }
        val lines = ReceiptTextStampFieldV136.entries.map { field ->
            val fallback = defaults.getValue(field)
            ReceiptTextStampLineSettingV136(
                field = field,
                enabled = preferences.getBoolean("${field.name}.enabled", fallback.enabled),
                alignment = runCatching {
                    ReceiptTextStampAlignmentV136.valueOf(
                        preferences.getString("${field.name}.alignment", fallback.alignment.name).orEmpty(),
                    )
                }.getOrDefault(fallback.alignment),
                bold = preferences.getBoolean("${field.name}.bold", fallback.bold),
                magnification = preferences.getInt("${field.name}.magnification", fallback.magnification),
            )
        }
        return ReceiptTextStampPolicyV136.normalize(
            ReceiptTextStampSettingsV136(
                lines = lines,
                stampVersion = preferences.getLong("stamp_version", 0L),
            ),
        )
    }

    fun save(settings: ReceiptTextStampSettingsV136): ReceiptTextStampSettingsV136 {
        val normalized = ReceiptTextStampPolicyV136.normalize(settings)
        val next = normalized.copy(stampVersion = load().stampVersion + 1L)
        val editor = preferences.edit().putLong("stamp_version", next.stampVersion)
        next.lines.forEach { line ->
            editor
                .putBoolean("${line.field.name}.enabled", line.enabled)
                .putString("${line.field.name}.alignment", line.alignment.name)
                .putBoolean("${line.field.name}.bold", line.bold)
                .putInt("${line.field.name}.magnification", line.magnification)
        }
        editor.apply()
        return next
    }

    fun resolvedLines(): List<ReceiptTextStampResolvedLineV136> = ReceiptTextStampPolicyV136.resolve(
        settings = load(),
        store = InitialReleaseSettingsStoreV135(appContext).loadStore(),
    )
}

object ReceiptTextStampEscPosV136 {
    fun encode(
        lines: List<ReceiptTextStampResolvedLineV136>,
        configuration: PrinterConfiguration,
    ): ByteArray {
        if (lines.isEmpty()) return ByteArray(0)
        val output = ByteArrayOutputStream()
        val charset = Charset.forName(configuration.profile.charsetName)
        output.write(PrinterCommandEncoder.beginDocument(configuration))
        lines.forEach { line ->
            val scale = line.magnification.coerceIn(
                ReceiptTextStampPolicyV136.MIN_MAGNIFICATION,
                ReceiptTextStampPolicyV136.MAX_MAGNIFICATION,
            ) - 1
            val size = ((scale shl 4) or scale).toByte()
            output.write(byteArrayOf(0x1B, 0x61, line.alignment.escPosValue.toByte()))
            output.write(byteArrayOf(0x1B, 0x45, if (line.bold) 1 else 0))
            output.write(byteArrayOf(0x1D, 0x21, size))
            output.write(line.text.toByteArray(charset))
            output.write(0x0A)
            output.write(byteArrayOf(0x1D, 0x21, 0x00))
            output.write(byteArrayOf(0x1B, 0x45, 0x00))
        }
        output.write(byteArrayOf(0x1B, 0x61, 0x00))
        return output.toByteArray()
    }
}

class ReceiptTextStampGatewayV136(
    context: Context,
    private val delegate: PrinterGateway,
    private val configuration: PrinterConfiguration,
) : PrinterGateway {
    private val store = ReceiptTextStampSettingsStoreV136(context.applicationContext)

    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        val prefix = ReceiptTextStampEscPosV136.encode(store.resolvedLines(), configuration)
        val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, prefix)
        delegate.send(composed).getOrThrow()
    }
}
