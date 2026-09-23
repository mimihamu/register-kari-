package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context

data class PaymentTenderPolicyV136(
    val allowOverpaymentWithChange: Boolean,
) {
    companion object {
        fun defaultFor(method: PaymentMethod): PaymentTenderPolicyV136 =
            PaymentTenderPolicyV136(allowOverpaymentWithChange = method == PaymentMethod.CASH)
    }
}

data class PaymentMethodSettingV136(
    val method: PaymentMethod,
    val enabled: Boolean,
    val displayOrder: Int,
    val allowOverpaymentWithChange: Boolean,
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

    fun tenderPolicyFor(method: PaymentMethod): PaymentTenderPolicyV136 =
        PaymentTenderPolicyV136(
            allowOverpaymentWithChange = if (method == PaymentMethod.CASH) {
                true
            } else {
                settingFor(method).allowOverpaymentWithChange
            },
        )
}

class PaymentSettingsStoreV136(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PaymentSettingsV136 {
        val settings = PaymentMethod.entries.map { method ->
            val fallback = defaultSetting(method)
            PaymentMethodSettingV136(
                method = method,
                enabled = if (method == PaymentMethod.CASH) {
                    true
                } else {
                    prefs.getBoolean(key(method, "enabled"), fallback.enabled)
                },
                displayOrder = prefs.getInt(key(method, "order"), fallback.displayOrder),
                allowOverpaymentWithChange = if (method == PaymentMethod.CASH) {
                    true
                } else {
                    prefs.getBoolean(
                        key(method, "overpay_change"),
                        fallback.allowOverpaymentWithChange,
                    )
                },
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

        val editor = prefs.edit()
        normalized.forEach { setting ->
            editor
                .putBoolean(key(setting.method, "enabled"), setting.enabled)
                .putInt(key(setting.method, "order"), setting.displayOrder)
                .putBoolean(
                    key(setting.method, "overpay_change"),
                    setting.allowOverpaymentWithChange,
                )
        }
        check(editor.commit()) { "支払設定を保存できませんでした" }

        PaymentSettingsAuditV136.record(
            appContext,
            actor,
            normalized.joinToString(" / ") {
                "${it.method.name}:enabled=${it.enabled},order=${it.displayOrder},overpayChange=${it.allowOverpaymentWithChange}"
            },
        )
        return PaymentSettingsV136(normalized, PaymentTerminalAdapterV136.NONE)
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
            )
        }

        private fun normalize(rows: List<PaymentMethodSettingV136>): List<PaymentMethodSettingV136> {
            val byMethod = rows.associateBy { it.method }
            return PaymentMethod.entries
                .map { method ->
                    val raw = byMethod[method] ?: defaultSetting(method)
                    raw.copy(
                        enabled = if (method == PaymentMethod.CASH) true else raw.enabled,
                        allowOverpaymentWithChange = if (method == PaymentMethod.CASH) {
                            true
                        } else {
                            raw.allowOverpaymentWithChange
                        },
                    )
                }
                .sortedWith(compareBy<PaymentMethodSettingV136> { it.displayOrder }.thenBy { it.method.ordinal })
                .mapIndexed { index, row -> row.copy(displayOrder = index * 10) }
        }

        private fun key(method: PaymentMethod, suffix: String): String =
            "method.${method.name}.$suffix"
    }
}

private object PaymentSettingsAuditV136 {
    fun record(context: Context, actor: String, detail: String) {
        OperationAuditSchemaV136.ensure(context)
        RegisterDatabase(context.applicationContext).use { helper ->
            helper.writableDatabase.insertOrThrow(
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
