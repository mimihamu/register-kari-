package jp.co.tenposinfo.register

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrinterStatusProbePairComparison(
    val baseId: Long,
    val comparedId: Long,
    val sameResponse: Boolean,
    val baseSize: Int,
    val comparedSize: Int,
    val differentByteCount: Int,
    val changedPositions: List<Int>,
)

object PrinterStatusProbeComparisonPolicy {
    const val MIN_SELECTION = 2
    const val MAX_SELECTION = 4

    fun normalizeSelection(ids: Collection<Long>): List<Long> =
        ids.asSequence().filter { it > 0 }.distinct().take(MAX_SELECTION).toList()

    fun canCompare(records: Collection<PrinterStatusProbeHistoryRecord>): Boolean =
        records.map { it.id }.distinct().size in MIN_SELECTION..MAX_SELECTION

    fun compare(records: List<PrinterStatusProbeHistoryRecord>): List<PrinterStatusProbePairComparison> {
        require(canCompare(records)) { "比較は2～4件を選択してください" }
        val normalized = records.distinctBy { it.id }.take(MAX_SELECTION)
        val base = normalized.first()
        val baseBytes = parseHex(base.responseHex)
        return normalized.drop(1).map { compared ->
            val comparedBytes = parseHex(compared.responseHex)
            val max = maxOf(baseBytes.size, comparedBytes.size)
            val positions = (0 until max).filter { index ->
                baseBytes.getOrNull(index) != comparedBytes.getOrNull(index)
            }
            PrinterStatusProbePairComparison(
                baseId = base.id,
                comparedId = compared.id,
                sameResponse = positions.isEmpty(),
                baseSize = baseBytes.size,
                comparedSize = comparedBytes.size,
                differentByteCount = positions.size,
                changedPositions = positions,
            )
        }
    }

    fun changedPositionLabel(comparison: PrinterStatusProbePairComparison): String = when {
        comparison.sameResponse -> "差分なし"
        comparison.changedPositions.size > 24 ->
            comparison.changedPositions.take(24).joinToString(",") { it.toString() } + "…"
        else -> comparison.changedPositions.joinToString(",") { it.toString() }
    }

    fun parseHex(hex: String): List<Int> = hex.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .mapNotNull { token -> token.toIntOrNull(16)?.takeIf { it in 0..255 } }
}

object PrinterStatusProbeMultiCsv {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    fun render(records: List<PrinterStatusProbeHistoryRecord>): String {
        require(records.isNotEmpty()) { "出力対象がありません" }
        val normalized = records.distinctBy { it.id }.take(PrinterStatusProbeComparisonPolicy.MAX_SELECTION)
        return buildString {
            append("\uFEFF")
            appendRow(
                "ID", "実行日時", "機種", "プリセット", "検証区分", "接続先",
                "成功", "応答時間ms", "受信バイト数", "送信HEX", "受信HEX",
                "受信ASCII", "解析レベル", "解析概要", "固定ビット", "エラー", "実行者",
            )
            normalized.forEach { record ->
                appendRow(
                    record.id.toString(),
                    synchronized(dateFormat) { dateFormat.format(Date(record.startedAt)) },
                    record.profile.displayName,
                    record.preset.displayName,
                    record.verification.displayName,
                    "${record.host}:${record.port}",
                    if (record.success) "成功" else "失敗",
                    record.elapsedMillis.toString(),
                    record.responseSize.toString(),
                    record.requestHex,
                    record.responseHex,
                    record.responseAscii,
                    record.parsedLevel?.displayName.orEmpty(),
                    record.parsedSummary.orEmpty(),
                    record.protocolValid?.let { if (it) "一致" else "不一致" }.orEmpty(),
                    record.errorMessage.orEmpty(),
                    record.actor,
                )
            }
            if (PrinterStatusProbeComparisonPolicy.canCompare(normalized)) {
                append('\n')
                appendRow("比較基準ID", "比較先ID", "同一応答", "基準サイズ", "比較先サイズ", "差分バイト数", "差分位置（0始まり）")
                PrinterStatusProbeComparisonPolicy.compare(normalized).forEach { comparison ->
                    appendRow(
                        comparison.baseId.toString(),
                        comparison.comparedId.toString(),
                        if (comparison.sameResponse) "同一" else "差分あり",
                        comparison.baseSize.toString(),
                        comparison.comparedSize.toString(),
                        comparison.differentByteCount.toString(),
                        PrinterStatusProbeComparisonPolicy.changedPositionLabel(comparison),
                    )
                }
            }
        }
    }

    fun escape(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val escaped = normalized.replace("\"", "\"\"")
        return if (normalized.any { it == ',' || it == '\"' || it == '\n' }) "\"$escaped\"" else escaped
    }

    private fun StringBuilder.appendRow(vararg values: String) {
        append(values.joinToString(",") { escape(it) })
        append('\n')
    }
}
