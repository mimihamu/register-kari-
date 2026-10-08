package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context

data class PaymentTenderPolicyV136(
    val allowOverpaymentWithChange: Boolean,
    val allowOverpay: Boolean = allowOverpaymentWithChange,
    val givesChange: Boolean = allowOverpaymentWithChange,
) {
    companion object {
        fun defaultFor(method: PaymentMethod): PaymentTenderPolicyV136 =
            PaymentTenderPolicyV136(
                allowOverpaymentWithChange = method == PaymentMethod.CASH,
                allowOverpay = method == PaymentMethod.CASH,
                givesChange = method == PaymentMethod.CASH,
            )
    }
}

data class PaymentMethodSettingV136(
    val method: PaymentMethod,
    val enabled: Boolean,
    val displayOrder: Int,
    val allowOverpaymentWithChange: Boolean,
    val allowOverpay: Boolean = allowOverpaymentWithChange,
    val givesChange: Boolean = allowOverpaymentWithChange,
    val gateway: PaymentTerminalAdapterV136 = PaymentTerminalAdapterV136.NONE,
    val drawerOpen: Boolean = method == PaymentMethod.CASH,
    val receiptName: String = method.displayName,
)

enum class PaymentTerminalAdapterV136(val displayName: String) {
    NONE("連携なし"),
}

data class PaymentSettingsV136(
    val methods: List<PaymentMethodSettingV136>,
    val terminalAdapter: PaymentTerminalAdapterV136 = PaymentTerminalAdapterV136.NONE,
) {
    fun settingFor(method: PaymentMethod): PaymentMethodSettingV136 =
        methods.firstOrNull { it.method == method } ?: PaymentSettingsStoreV136.defaultSetting(method)

    fun enabledMethods(): List<PaymentMethod> =
        methods
            .filter { it.enabled || it.method == PaymentMethod.CASH }
            .sortedBy { it.displayOrder }
            .map { it.method }

    fun tenderPolicyFor(method: PaymentMethod): PaymentTenderPolicyV136 {
        val setting = settingFor(method)
        val allowOverpay = if (method == PaymentMethod.CASH) true else setting.allowOverpay
        val givesChange = if (method == PaymentMethod.CASH) true else setting.givesChange
        return PaymentTenderPolicyV136(
            allowOverpaymentWithChange = allowOverpay && givesChange,
            allowOverpay = allowOverpay,
            givesChange = givesChange,
        )
    }

    fun receiptNameFor(method: PaymentMethod): String = settingFor(method).receiptName

    fun gatewayFor(method: PaymentMethod): PaymentTerminalAdapterV136 = settingFor(method).gateway

    fun drawerOpenFor(method: PaymentMethod): Boolean =
        method == PaymentMethod.CASH && settingFor(method).drawerOpen
}

class PaymentSettingsStoreV136(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PaymentSettingsV136 {
        val settings = PaymentMethod.entries.map { method ->
            val fallback = defaultSetting(method)
            val legacyOverpayChange = if (method == PaymentMethod.CASH) {
                true
            } else {
                prefs.getBoolean(
                    key(method, "overpay_change"),
                    fallback.allowOverpaymentWithChange,
                )
            }
            PaymentMethodSettingV136(
                method = method,
                enabled = if (method == PaymentMethod.CASH) {
                    true
                } else {
                    prefs.getBoolean(key(method, "enabled"), fallback.enabled)
                },
                displayOrder = prefs.getInt(key(method, "order"), fallback.displayOrder),
                allowOverpaymentWithChange = legacyOverpayChange,
                allowOverpay = if (method == PaymentMethod.CASH) {
                    true
                } else if (prefs.contains(key(method, "allow_overpay"))) {
                    prefs.getBoolean(key(method, "allow_overpay"), fallback.allowOverpay)
                } else {
                    legacyOverpayChange
                },
                givesChange = if (method == PaymentMethod.CASH) {
                    true
                } else if (prefs.contains(key(method, "gives_change"))) {
                    prefs.getBoolean(key(method, "gives_change"), fallback.givesChange)
                } else {
                    legacyOverpayChange
                },
                gateway = runCatching {
                    PaymentTerminalAdapterV136.valueOf(
                        prefs.getString(key(method, "gateway"), fallback.gateway.name).orEmpty(),
                    )
                }.getOrDefault(fallback.gateway),
                drawerOpen = prefs.getBoolean(key(method, "drawer_open"), fallback.drawerOpen),
                receiptName = prefs.getString(key(method, "receipt_name"), fallback.receiptName).orEmpty(),
            )
        }
        val normalized = normalize(settings)
        return PaymentSettingsV136(
            methods = normalized,
            terminalAdapter = PaymentTerminalAdapterV136.NONE,
        )
    }

    fun save(settings: PaymentSettingsV136, actor: String): PaymentSettingsV136 {
        val normalized = normalize(settings.methods)
        require(normalized.any { it.method == PaymentMethod.CASH && it.enabled }) {
            "現金は無効にできません"
        }
        require(normalized.count { it.enabled } >= 1) { "支払方法を1つ以上有効にしてください" }
        normalized.forEach { setting ->
            val receiptName = setting.receiptName.trim()
            val codePoints = receiptName.codePointCount(0, receiptName.length)
            require(codePoints in 1..16) {
                "${setting.method.displayName}のレシート表示名は1～16文字で入力してください"
            }
            require(setting.method == PaymentMethod.CASH || !setting.drawerOpen) {
                "ドロア開放は現金支払だけ設定できます"
            }
            require(setting.gateway == PaymentTerminalAdapterV136.NONE) {
                "未接続の決済端末アダプタは選択できません"
            }
        }

        val editor = prefs.edit()
        normalized.forEach { setting ->
            editor
                .putBoolean(key(setting.method, "enabled"), setting.enabled)
                .putInt(key(setting.method, "order"), setting.displayOrder)
                .putBoolean(
                    key(setting.method, "overpay_change"),
                    setting.allowOverpaymentWithChange,
                )
                .putBoolean(key(setting.method, "allow_overpay"), setting.allowOverpay)
                .putBoolean(key(setting.method, "gives_change"), setting.givesChange)
                .putString(key(setting.method, "gateway"), setting.gateway.name)
                .putBoolean(key(setting.method, "drawer_open"), setting.drawerOpen)
                .putString(key(setting.method, "receipt_name"), setting.receiptName.trim())
        }
        check(editor.commit()) { "支払設定を保存できませんでした" }

        PaymentSettingsAuditV136.record(
            appContext,
            actor,
            normalized.joinToString(" / ") {
                "${it.method.name}:enabled=${it.enabled},order=${it.displayOrder},allowOverpay=${it.allowOverpay},givesChange=${it.givesChange},gateway=${it.gateway.name},drawerOpen=${it.drawerOpen},receiptName=${it.receiptName}"
            },
        )
        val saved = PaymentSettingsV136(normalized, PaymentTerminalAdapterV136.NONE)
        PaymentSettingsRegistryV136.update(saved)
        return saved
    }

    companion object {
        private const val PREFS = "payment_settings_v136"

        fun defaultSetting(method: PaymentMethod): PaymentMethodSettingV136 {
            val order = when (method) {
                PaymentMethod.CASH -> 0
                PaymentMethod.CARD -> 10
                PaymentMethod.ELECTRONIC_MONEY -> 20
                PaymentMethod.QR -> 30
                PaymentMethod.GIFT_CERTIFICATE -> 40
                PaymentMethod.ACCOUNT_RECEIVABLE -> 50
                PaymentMethod.OTHER -> 60
            }
            val enabled = when (method) {
                PaymentMethod.CASH,
                PaymentMethod.CARD,
                PaymentMethod.GIFT_CERTIFICATE,
                PaymentMethod.ACCOUNT_RECEIVABLE,
                PaymentMethod.OTHER,
                -> true
                PaymentMethod.ELECTRONIC_MONEY,
                PaymentMethod.QR,
                -> false
            }
            return PaymentMethodSettingV136(
                method = method,
                enabled = enabled,
                displayOrder = order,
                allowOverpaymentWithChange = method == PaymentMethod.CASH,
                allowOverpay = method == PaymentMethod.CASH,
                givesChange = method == PaymentMethod.CASH,
                gateway = PaymentTerminalAdapterV136.NONE,
                drawerOpen = method == PaymentMethod.CASH,
                receiptName = method.displayName,
            )
        }

        private fun normalize(rows: List<PaymentMethodSettingV136>): List<PaymentMethodSettingV136> {
            val byMethod = rows.associateBy { it.method }
            return PaymentMethod.entries
                .map { method ->
                    val raw = byMethod[method] ?: defaultSetting(method)
                    val allowOverpay = if (method == PaymentMethod.CASH) true else raw.allowOverpay
                    val givesChange = if (method == PaymentMethod.CASH) true else raw.givesChange
                    raw.copy(
                        enabled = if (method == PaymentMethod.CASH) true else raw.enabled,
                        allowOverpaymentWithChange = allowOverpay && givesChange,
                        allowOverpay = allowOverpay,
                        givesChange = givesChange,
                        gateway = raw.gateway,
                        drawerOpen = method == PaymentMethod.CASH && raw.drawerOpen,
                        receiptName = raw.receiptName.trim().ifBlank { method.displayName },
                    )
                }
                .sortedWith(compareBy<PaymentMethodSettingV136> { it.displayOrder }.thenBy { it.method.ordinal })
                .mapIndexed { index, row -> row.copy(displayOrder = index * 10) }
        }

        private fun key(method: PaymentMethod, suffix: String): String =
            "method.${method.name}.$suffix"
    }
}

object PaymentSettingsRegistryV136 {
    @Volatile
    private var current: PaymentSettingsV136? = null

    fun current(): PaymentSettingsV136 =
        current ?: PaymentSettingsV136(PaymentMethod.entries.map(PaymentSettingsStoreV136::defaultSetting))

    fun reload(context: Context) {
        current = runCatching { PaymentSettingsStoreV136(context.applicationContext).load() }.getOrNull()
    }

    fun update(settings: PaymentSettingsV136) {
        current = settings
    }
}

private object PaymentSettingsAuditV136 {
    fun record(context: Context, actor: String, detail: String) {
        RegisterDatabase(context.applicationContext).use { helper ->
            val db = helper.writableDatabase
            OperationAuditSchemaV136.ensure(db)
            db.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "PAYMENT_SETTINGS_UPDATED")
                    put("reference_id", 0L)
                    put("detail", "SCR-630B 支払設定を保存 / $detail")
                    put("operator_name", actor.trim().ifBlank { "責任者" })
                    put("created_at", System.currentTimeMillis())
                },
            )
        }
    }
}
