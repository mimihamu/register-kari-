package jp.co.tenposinfo.register

import android.content.Context

enum class ReceiptTaxDisplayModeV136(val displayName: String) {
    DETAIL("詳細"),
    SUMMARY("要約"),
    MINIMUM("最小"),
}

enum class ReceiptReprintAuthV136(val displayName: String) {
    SELLER("販売担当者"),
    MANAGER("責任者"),
}

enum class ReceiptQrModeV136(val displayName: String) {
    NONE("印字しない"),
    TXN_ID("取引ID"),
    URL("URL"),
}

data class ReceiptLayoutSettingsV136(
    val defaultPrinterId: Long = 1L,
    val showLogo: Boolean = true,
    val showAddress: Boolean = true,
    val showPhone: Boolean = true,
    val showOperator: Boolean = true,
    val showProductCode: Boolean = false,
    val taxDisplayMode: ReceiptTaxDisplayModeV136 = ReceiptTaxDisplayModeV136.DETAIL,
    val reprintAuth: ReceiptReprintAuthV136 = ReceiptReprintAuthV136.SELLER,
    val qrMode: ReceiptQrModeV136 = ReceiptQrModeV136.NONE,
)

object ReceiptLayoutSettingsPolicyV136 {
    fun normalize(value: ReceiptLayoutSettingsV136): ReceiptLayoutSettingsV136 {
        require(value.defaultPrinterId == 1L) {
            "初版では登録済みレシートプリンター1台を既定出力先として使用します"
        }
        return value
    }

    /**
     * 現行v1.36では登録番号がある売上を適格簡易請求書相当として扱っているため、
     * 税表示の省略設定があっても必要税項目を落とさない。
     */
    fun effectiveTaxDisplayMode(
        requested: ReceiptTaxDisplayModeV136,
        registrationNumber: String,
    ): ReceiptTaxDisplayModeV136 =
        if (registrationNumber.isNotBlank()) ReceiptTaxDisplayModeV136.DETAIL else requested
}

class ReceiptLayoutSettingsStoreV136(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "receipt_layout_settings_v136",
        Context.MODE_PRIVATE,
    )

    fun load(): ReceiptLayoutSettingsV136 = ReceiptLayoutSettingsPolicyV136.normalize(
        ReceiptLayoutSettingsV136(
            defaultPrinterId = preferences.getLong("receipt.defaultPrinterId", 1L),
            showLogo = preferences.getBoolean("receipt.showLogo", true),
            showAddress = preferences.getBoolean("receipt.showAddress", true),
            showPhone = preferences.getBoolean("receipt.showPhone", true),
            showOperator = preferences.getBoolean("receipt.showOperator", true),
            showProductCode = preferences.getBoolean("receipt.showProductCode", false),
            taxDisplayMode = enumValue(
                "receipt.taxDisplayMode",
                ReceiptTaxDisplayModeV136.DETAIL,
            ),
            reprintAuth = enumValue(
                "receipt.reprintAuth",
                ReceiptReprintAuthV136.SELLER,
            ),
            qrMode = enumValue(
                "receipt.qrMode",
                ReceiptQrModeV136.NONE,
            ),
        ),
    )

    fun save(value: ReceiptLayoutSettingsV136): ReceiptLayoutSettingsV136 {
        val clean = ReceiptLayoutSettingsPolicyV136.normalize(value)
        check(
            preferences.edit()
                .putLong("receipt.defaultPrinterId", clean.defaultPrinterId)
                .putBoolean("receipt.showLogo", clean.showLogo)
                .putBoolean("receipt.showAddress", clean.showAddress)
                .putBoolean("receipt.showPhone", clean.showPhone)
                .putBoolean("receipt.showOperator", clean.showOperator)
                .putBoolean("receipt.showProductCode", clean.showProductCode)
                .putString("receipt.taxDisplayMode", clean.taxDisplayMode.name)
                .putString("receipt.reprintAuth", clean.reprintAuth.name)
                .putString("receipt.qrMode", clean.qrMode.name)
                .commit(),
        ) { "レシート表示設定を保存できませんでした" }
        ReceiptLayoutSettingsRegistryV136.update(clean)
        return clean
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching {
            enumValueOf<T>(preferences.getString(key, fallback.name).orEmpty())
        }.getOrDefault(fallback)
}

object ReceiptLayoutSettingsRegistryV136 {
    @Volatile
    private var current: ReceiptLayoutSettingsV136 = ReceiptLayoutSettingsV136()

    fun current(): ReceiptLayoutSettingsV136 = current

    fun reload(context: Context) {
        current = runCatching {
            ReceiptLayoutSettingsStoreV136(context.applicationContext).load()
        }.getOrDefault(ReceiptLayoutSettingsV136())
    }

    fun update(value: ReceiptLayoutSettingsV136) {
        current = ReceiptLayoutSettingsPolicyV136.normalize(value)
    }
}
