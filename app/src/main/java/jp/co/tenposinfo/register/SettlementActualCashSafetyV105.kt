package jp.co.tenposinfo.register

/**
 * Z精算の現金実査を必須化し、空欄を理論現金へ置換して差異照合を回避できないようにする。
 * X点検は従来互換として空欄を許可し、保存時は理論現金を使用する。
 */
internal object SettlementActualCashSafetyV105 {
    const val Z_REQUIRED_MESSAGE = "Z精算では現金実査を入力してください。"
    const val NON_NEGATIVE_MESSAGE = "現金実査額は0円以上で入力してください"

    fun validationMessage(type: SettlementReportType, actualCash: Long?): String? = when {
        type == SettlementReportType.Z_SETTLEMENT && actualCash == null -> Z_REQUIRED_MESSAGE
        actualCash != null && actualCash < 0L -> NON_NEGATIVE_MESSAGE
        else -> null
    }

    fun maySubmit(type: SettlementReportType, actualCash: Long?): Boolean =
        validationMessage(type, actualCash) == null

    fun validate(type: SettlementReportType, actualCash: Long?) {
        validationMessage(type, actualCash)?.let { throw IllegalArgumentException(it) }
    }

    fun effectiveActualCash(
        type: SettlementReportType,
        actualCash: Long?,
        expectedCash: Long,
    ): Long {
        validate(type, actualCash)
        return actualCash ?: expectedCash
    }
}
