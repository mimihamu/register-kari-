package jp.co.tenposinfo.register

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrinterStatusProbeDeviceKey(
    val profile: PrinterProfile,
    val preset: PrinterStatusProbePreset,
    val host: String,
    val port: Int,
    val printerModel: String,
    val emulationMode: String,
) {
    val displayName: String
        get() = buildString {
            append(printerModel.ifBlank { "型番未入力" })
            if (emulationMode.isNotBlank()) append(" / $emulationMode")
        }
}

data class PrinterStatusConditionProgress(
    val condition: PrinterStatusTestCondition,
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val latestAt: Long,
) {
    val stateLabel: String
        get() = when {
            successCount > 0 -> "成功あり"
            failureCount > 0 -> "失敗のみ"
            else -> "未実施"
        }
}

data class PrinterStatusStableBitChange(
    val byteIndex: Int,
    val bitIndex: Int,
    val normalValue: Int,
    val conditionValue: Int,
) {
    val mask: Int
        get() = 1 shl bitIndex

    val label: String
        get() = "byte[$byteIndex] bit$bitIndex (0x${"%02X".format(mask)}): $normalValue→$conditionValue"
}

data class PrinterStatusConditionBitCandidate(
    val condition: PrinterStatusTestCondition,
    val normalSampleCount: Int,
    val conditionSampleCount: Int,
    val responseSize: Int?,
    val stableChanges: List<PrinterStatusStableBitChange>,
    val unstableBitCount: Int,
    val sizeMismatch: Boolean,
    val note: String,
)

data class PrinterStatusDeviceAnalysis(
    val key: PrinterStatusProbeDeviceKey,
    val records: List<PrinterStatusProbeHistoryRecord>,
    val progress: List<PrinterStatusConditionProgress>,
    val candidates: List<PrinterStatusConditionBitCandidate>,
) {
    val successfulConditionCount: Int
        get() = progress.count { it.successCount > 0 }

    val requiredConditionCount: Int
        get() = PrinterStatusProbeAnalysisPolicy.REQUIRED_CONDITIONS.size
}

object PrinterStatusProbeAnalysisPolicy {
    val REQUIRED_CONDITIONS = listOf(
        PrinterStatusTestCondition.NORMAL,
        PrinterStatusTestCondition.COVER_OPEN,
        PrinterStatusTestCondition.PAPER_NEAR_END,
        PrinterStatusTestCondition.PAPER_OUT,
        PrinterStatusTestCondition.CUTTER_ERROR,
        PrinterStatusTestCondition.POWER_OFF,
        PrinterStatusTestCondition.LAN_DISCONNECTED,
    )

    fun deviceKey(record: PrinterStatusProbeHistoryRecord): PrinterStatusProbeDeviceKey =
        PrinterStatusProbeDeviceKey(
            profile = record.profile,
            preset = record.preset,
            host = record.host.trim(),
            port = record.port,
            printerModel = record.printerModel.trim(),
            emulationMode = record.emulationMode.trim(),
        )

    fun analyze(records: List<PrinterStatusProbeHistoryRecord>): List<PrinterStatusDeviceAnalysis> =
        records
            .filter { it.condition != PrinterStatusTestCondition.UNSPECIFIED }
            .groupBy(::deviceKey)
            .map { (key, groupedRecords) -> analyzeDevice(key, groupedRecords) }
            .sortedWith(
                compareByDescending<PrinterStatusDeviceAnalysis> { it.records.maxOfOrNull { record -> record.startedAt } ?: 0L }
                    .thenBy { it.key.displayName },
            )

    fun analyzeDevice(
        key: PrinterStatusProbeDeviceKey,
        records: List<PrinterStatusProbeHistoryRecord>,
    ): PrinterStatusDeviceAnalysis {
        val ordered = records.sortedByDescending { it.startedAt }
        val progress = REQUIRED_CONDITIONS.map { condition ->
            val conditionRecords = ordered.filter { it.condition == condition }
            PrinterStatusConditionProgress(
                condition = condition,
                totalCount = conditionRecords.size,
                successCount = conditionRecords.count { it.success },
                failureCount = conditionRecords.count { !it.success },
                latestAt = conditionRecords.maxOfOrNull { it.startedAt } ?: 0L,
            )
        }
        val candidates = REQUIRED_CONDITIONS
            .filter { it != PrinterStatusTestCondition.NORMAL }
            .map { condition -> analyzeCondition(ordered, condition) }
        return PrinterStatusDeviceAnalysis(key, ordered, progress, candidates)
    }

    fun analyzeCondition(
        records: List<PrinterStatusProbeHistoryRecord>,
        condition: PrinterStatusTestCondition,
    ): PrinterStatusConditionBitCandidate {
        require(condition != PrinterStatusTestCondition.NORMAL) { "正常条件は比較先に指定できません" }
        val normalSamples = successfulResponses(records, PrinterStatusTestCondition.NORMAL)
        val conditionSamples = successfulResponses(records, condition)
        if (normalSamples.isEmpty() || conditionSamples.isEmpty()) {
            return PrinterStatusConditionBitCandidate(
                condition = condition,
                normalSampleCount = normalSamples.size,
                conditionSampleCount = conditionSamples.size,
                responseSize = null,
                stableChanges = emptyList(),
                unstableBitCount = 0,
                sizeMismatch = false,
                note = when {
                    normalSamples.isEmpty() && conditionSamples.isEmpty() -> "正常・条件側とも成功RAWがありません"
                    normalSamples.isEmpty() -> "正常条件の成功RAWがありません"
                    else -> "${condition.displayName}の成功RAWがありません"
                },
            )
        }

        val sizes = (normalSamples + conditionSamples).map { it.size }.distinct()
        if (sizes.size != 1) {
            return PrinterStatusConditionBitCandidate(
                condition = condition,
                normalSampleCount = normalSamples.size,
                conditionSampleCount = conditionSamples.size,
                responseSize = null,
                stableChanges = emptyList(),
                unstableBitCount = 0,
                sizeMismatch = true,
                note = "応答長が一致しないためビット候補を計算しません（${sizes.sorted().joinToString("/")}バイト）",
            )
        }

        val responseSize = sizes.single()
        val changes = mutableListOf<PrinterStatusStableBitChange>()
        var unstableBits = 0
        for (byteIndex in 0 until responseSize) {
            for (bitIndex in 0..7) {
                val normalValues = normalSamples.map { (it[byteIndex] shr bitIndex) and 1 }.toSet()
                val conditionValues = conditionSamples.map { (it[byteIndex] shr bitIndex) and 1 }.toSet()
                if (normalValues.size == 1 && conditionValues.size == 1) {
                    val normalValue = normalValues.single()
                    val conditionValue = conditionValues.single()
                    if (normalValue != conditionValue) {
                        changes += PrinterStatusStableBitChange(
                            byteIndex = byteIndex,
                            bitIndex = bitIndex,
                            normalValue = normalValue,
                            conditionValue = conditionValue,
                        )
                    }
                } else {
                    unstableBits++
                }
            }
        }
        val sampleWarning = if (normalSamples.size == 1 || conditionSamples.size == 1) {
            "単一サンプルを含むため候補の再現確認が必要です"
        } else {
            "複数サンプルで値が安定した差分だけを表示しています"
        }
        return PrinterStatusConditionBitCandidate(
            condition = condition,
            normalSampleCount = normalSamples.size,
            conditionSampleCount = conditionSamples.size,
            responseSize = responseSize,
            stableChanges = changes,
            unstableBitCount = unstableBits,
            sizeMismatch = false,
            note = when {
                changes.isEmpty() -> "安定した差分ビットは見つかりません。$sampleWarning"
                else -> "${changes.size}ビットを変化候補として検出。$sampleWarning"
            },
        )
    }

    private fun successfulResponses(
        records: List<PrinterStatusProbeHistoryRecord>,
        condition: PrinterStatusTestCondition,
    ): List<List<Int>> = records.asSequence()
        .filter { it.condition == condition && it.success && it.responseHex.isNotBlank() }
        .map { PrinterStatusProbeComparisonPolicy.parseHex(it.responseHex) }
        .filter { it.isNotEmpty() }
        .toList()
}

object PrinterStatusProbeAnalysisCsv {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    fun render(analysis: PrinterStatusDeviceAnalysis): String = buildString {
        append('\uFEFF')
        appendRow("つぐレジ プリンター状態条件別分析")
        appendRow("実機型番", analysis.key.printerModel)
        appendRow("エミュレーション", analysis.key.emulationMode)
        appendRow("プロファイル", analysis.key.profile.displayName)
        appendRow("プリセット", analysis.key.preset.displayName)
        appendRow("接続先", "${analysis.key.host}:${analysis.key.port}")
        appendRow("注意", "本出力は採取RAWから機械的に算出した候補であり、メーカー仕様や実機互換性の確認完了を意味しません")
        append('\n')
        appendRow("試験条件", "総数", "成功", "失敗", "状態", "最終採取日時")
        analysis.progress.forEach { progress ->
            appendRow(
                progress.condition.displayName,
                progress.totalCount.toString(),
                progress.successCount.toString(),
                progress.failureCount.toString(),
                progress.stateLabel,
                progress.latestAt.takeIf { it > 0 }?.let {
                    synchronized(dateFormat) { dateFormat.format(Date(it)) }
                }.orEmpty(),
            )
        }
        append('\n')
        appendRow(
            "比較条件", "正常サンプル数", "条件サンプル数", "応答長", "安定差分数",
            "不安定ビット数", "byte index", "bit", "mask", "正常値", "条件値", "注記",
        )
        analysis.candidates.forEach { candidate ->
            if (candidate.stableChanges.isEmpty()) {
                appendRow(
                    candidate.condition.displayName,
                    candidate.normalSampleCount.toString(),
                    candidate.conditionSampleCount.toString(),
                    candidate.responseSize?.toString().orEmpty(),
                    "0",
                    candidate.unstableBitCount.toString(),
                    "", "", "", "", "",
                    candidate.note,
                )
            } else {
                candidate.stableChanges.forEach { change ->
                    appendRow(
                        candidate.condition.displayName,
                        candidate.normalSampleCount.toString(),
                        candidate.conditionSampleCount.toString(),
                        candidate.responseSize?.toString().orEmpty(),
                        candidate.stableChanges.size.toString(),
                        candidate.unstableBitCount.toString(),
                        change.byteIndex.toString(),
                        change.bitIndex.toString(),
                        "0x${"%02X".format(change.mask)}",
                        change.normalValue.toString(),
                        change.conditionValue.toString(),
                        candidate.note,
                    )
                }
            }
        }
    }

    private fun StringBuilder.appendRow(vararg values: String) {
        append(values.joinToString(",") { PrinterStatusProbeMultiCsv.escape(it) })
        append('\n')
    }
}
