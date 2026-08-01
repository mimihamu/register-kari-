package jp.co.tenposinfo.register

enum class PrinterStatusTestCondition(
    val displayName: String,
    val shortLabel: String,
) {
    UNSPECIFIED("未指定", "未指定"),
    NORMAL("正常", "正常"),
    COVER_OPEN("カバー開", "カバー"),
    PAPER_NEAR_END("用紙ニアエンド", "ニアエンド"),
    PAPER_OUT("紙切れ", "紙切れ"),
    CUTTER_ERROR("カッター異常", "カッター"),
    POWER_OFF("電源OFF", "電源OFF"),
    LAN_DISCONNECTED("LAN切断", "LAN断"),
    OTHER("その他", "その他"),
}

data class PrinterStatusProbeAnnotation(
    val condition: PrinterStatusTestCondition = PrinterStatusTestCondition.UNSPECIFIED,
    val printerModel: String = "",
    val emulationMode: String = "",
    val memo: String = "",
)

object PrinterStatusProbeAnnotationPolicy {
    const val MAX_MODEL_LENGTH = 80
    const val MAX_EMULATION_LENGTH = 80
    const val MAX_MEMO_LENGTH = 500

    fun normalize(annotation: PrinterStatusProbeAnnotation): PrinterStatusProbeAnnotation = annotation.copy(
        printerModel = normalizeText(annotation.printerModel, MAX_MODEL_LENGTH),
        emulationMode = normalizeText(annotation.emulationMode, MAX_EMULATION_LENGTH),
        memo = normalizeText(annotation.memo, MAX_MEMO_LENGTH),
    )

    fun validationError(annotation: PrinterStatusProbeAnnotation): String? {
        val normalized = normalize(annotation)
        if (normalized.condition == PrinterStatusTestCondition.UNSPECIFIED) {
            return "試験条件を選択してください"
        }
        if (normalized.condition == PrinterStatusTestCondition.OTHER && normalized.memo.isBlank()) {
            return "「その他」の場合はメモへ試験条件を入力してください"
        }
        return null
    }

    fun matches(
        record: PrinterStatusProbeHistoryRecord,
        condition: PrinterStatusTestCondition?,
        query: String,
    ): Boolean {
        if (condition != null && record.condition != condition) return false
        val keyword = query.trim()
        if (keyword.isBlank()) return true
        val target = listOf(
            record.id.toString(),
            record.condition.displayName,
            record.printerModel,
            record.emulationMode,
            record.memo,
            record.profile.displayName,
            record.host,
            record.responseHex,
            record.errorMessage.orEmpty(),
        ).joinToString("\n")
        return target.contains(keyword, ignoreCase = true)
    }

    fun summary(annotation: PrinterStatusProbeAnnotation): String {
        val normalized = normalize(annotation)
        return buildList {
            add(normalized.condition.displayName)
            if (normalized.printerModel.isNotBlank()) add("機種:${normalized.printerModel}")
            if (normalized.emulationMode.isNotBlank()) add("モード:${normalized.emulationMode}")
            if (normalized.memo.isNotBlank()) add(normalized.memo)
        }.joinToString(" / ")
    }

    private fun normalizeText(value: String, maxLength: Int): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .take(maxLength)
}
