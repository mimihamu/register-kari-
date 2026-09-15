package jp.co.tenposinfo.register

import android.content.Context

data class InvoiceIssuerProfile(
    val storeName: String = "店舗名未設定",
    val address: String = "",
    val phone: String = "",
    val registrationNumber: String = "",
)

data class TaxInvoiceSettings(
    val mixedTaxPolicy: MixedTaxPolicy = MixedTaxPolicy.BLOCK,
    val issuer: InvoiceIssuerProfile = InvoiceIssuerProfile(),
    val invoiceAggregationBasis: InvoiceAggregationBasisV136 = InvoiceAggregationBasisV136.TAX_INCLUDED,
)

object TaxInvoiceSettingsPolicy {
    private val registrationPattern = Regex("T[0-9]{13}")

    fun normalize(settings: TaxInvoiceSettings): TaxInvoiceSettings {
        val issuer = settings.issuer
        val storeName = issuer.storeName.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        require(storeName.isNotBlank()) { "店舗名を入力してください" }
        require(storeName.length <= 80) { "店舗名は80文字以内です" }
        val address = issuer.address.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        require(address.length <= 160) { "住所は160文字以内です" }
        val phone = issuer.phone.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        require(phone.length <= 40) { "電話番号は40文字以内です" }
        val registration = issuer.registrationNumber
            .uppercase()
            .replace(" ", "")
            .replace("-", "")
            .trim()
        require(registration.isBlank() || registrationPattern.matches(registration)) {
            "登録番号はTに続く13桁で入力してください"
        }
        return settings.copy(
            issuer = InvoiceIssuerProfile(
                storeName = storeName,
                address = address,
                phone = phone,
                registrationNumber = registration,
            ),
        )
    }
}

class TaxInvoiceSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TaxInvoiceSettings {
        val policy = runCatching {
            MixedTaxPolicy.valueOf(preferences.getString(KEY_MIXED_POLICY, MixedTaxPolicy.BLOCK.name)!!)
        }.getOrDefault(MixedTaxPolicy.BLOCK)
        val aggregationBasis = runCatching {
            InvoiceAggregationBasisV136.valueOf(
                preferences.getString(
                    KEY_INVOICE_AGGREGATION_BASIS,
                    InvoiceAggregationBasisV136.TAX_INCLUDED.name,
                )!!,
            )
        }.getOrDefault(InvoiceAggregationBasisV136.TAX_INCLUDED)
        return TaxInvoiceSettings(
            mixedTaxPolicy = policy,
            issuer = InvoiceIssuerProfile(
                storeName = preferences.getString(KEY_STORE_NAME, "店舗名未設定").orEmpty(),
                address = preferences.getString(KEY_ADDRESS, "").orEmpty(),
                phone = preferences.getString(KEY_PHONE, "").orEmpty(),
                registrationNumber = preferences.getString(KEY_REGISTRATION, "").orEmpty(),
            ),
            invoiceAggregationBasis = aggregationBasis,
        )
    }

    fun save(settings: TaxInvoiceSettings): TaxInvoiceSettings {
        val clean = TaxInvoiceSettingsPolicy.normalize(settings)
        preferences.edit()
            .putString(KEY_MIXED_POLICY, clean.mixedTaxPolicy.name)
            .putString(KEY_INVOICE_AGGREGATION_BASIS, clean.invoiceAggregationBasis.name)
            .putString(KEY_STORE_NAME, clean.issuer.storeName)
            .putString(KEY_ADDRESS, clean.issuer.address)
            .putString(KEY_PHONE, clean.issuer.phone)
            .putString(KEY_REGISTRATION, clean.issuer.registrationNumber)
            .apply()
        TaxInvoiceSettingsRegistry.replace(clean)
        return clean
    }

    companion object {
        private const val PREFS = "tax_invoice_settings"
        private const val KEY_MIXED_POLICY = "mixed_tax_policy"
        private const val KEY_INVOICE_AGGREGATION_BASIS = "invoice_aggregation_basis"
        private const val KEY_STORE_NAME = "store_name"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PHONE = "phone"
        private const val KEY_REGISTRATION = "registration_number"
    }
}

object TaxInvoiceSettingsRegistry {
    @Volatile
    private var currentSettings = TaxInvoiceSettings()

    fun initialize(context: Context) {
        currentSettings = TaxInvoiceSettingsStore(context.applicationContext).load()
    }

    fun current(): TaxInvoiceSettings = currentSettings

    fun replace(settings: TaxInvoiceSettings) {
        currentSettings = TaxInvoiceSettingsPolicy.normalize(settings)
    }
}
