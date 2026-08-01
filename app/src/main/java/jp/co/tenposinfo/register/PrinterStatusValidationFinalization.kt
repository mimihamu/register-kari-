package jp.co.tenposinfo.register

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存済みの実機RAW履歴だけから、責任者レビューへ進める証跡かを判定する。
 * この結果は候補であり、PrinterStatus解析・販売監視・自動印刷前確認へ自動適用しない。
 */
enum class PrinterEvidenceConfidence(
    val displayName: String,
    val rank: Int,
) {
    NOT_READY("未成立", 0),
    LOW("低", 1),
    MEDIUM("中", 2),
    HIGH("高", 3),
}

enum class PrinterEvidenceExpectation(val displayName: String) {
    RESPONSE_PATTERN("応答パターン"),
    CONNECTION_FAILURE("通信失敗"),
}

data class PrinterStatusResponseCluster(
    val condition: PrinterStatusTestCondition,
    val validSampleCount: Int,
    val distinctResponseCount: Int,
    val dominantResponseHex: String,
    val dominantCount: Int,
    val outlierCount: Int,
    val agreementRate: Double,
    val responseLengths: List<Int> = emptyList(),
    val sourceRecordIds: List<Long> = emptyList(),
    val outlierRecordIds: List<Long> = emptyList(),
) {
    val agreementPercent: Int
        get() = (agreementRate * 100.0).toInt().coerceIn(0, 100)

    val responseLengthMismatch: Boolean
        get() = responseLengths.size > 1
}

data class PrinterStatusConditionEvidence(
    val condition: PrinterStatusTestCondition,
    val expectation: PrinterEvidenceExpectation,
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val cluster: PrinterStatusResponseCluster?,
    val candidate: PrinterStatusConditionBitCandidate?,
    val confidence: PrinterEvidenceConfidence,
    val ready: Boolean,
    val reason: String,
    val sourceRecordIds: List<Long> = emptyList(),
)

data class PrinterStatusValidationReport(
    val analysis: PrinterStatusDeviceAnalysis,
    val evidence: List<PrinterStatusConditionEvidence>,
    val overallConfidence: PrinterEvidenceConfidence,
    val evidenceReadyForReview: Boolean,
    val blockers: List<String>,
    val generatedAt: Long = System.currentTimeMillis(),
) {
    val key: PrinterStatusProbeDeviceKey
        get() = analysis.key

    val sourceRecordIds: List<Long>
        get() = analysis.records.map { it.id }.distinct().sorted()

    val stableChangeCount: Int
        get() = evidence.sumOf { it.candidate?.stableChanges?.size ?: 0 }

    val totalOutlierCount: Int
        get() = evidence.sumOf { it.cluster?.outlierCount ?: 0 }

    val responseLengthMismatchCount: Int
        get() = evidence.count { it.cluster?.responseLengthMismatch == true || it.candidate?.sizeMismatch == true }

    val manufacturerVerificationNote: String
        get() = when (key.profile) {
            PrinterProfile.EPSON_TM_JAPAN ->
                "EPSON DLE EOTの解析方式はメーカー仕様確認済みです。ただし対象実機での動作確認は別途必要です"
            PrinterProfile.STAR_ESC_POS ->
                "STARの状態応答方式はメーカー仕様確認済みとは扱いません。保存済みRAWから算出した候補です"
            PrinterProfile.GENERIC_ESC_POS ->
                "汎用ESC/POSの状態応答方式はメーカー仕様確認済みとは扱いません。保存済みRAWから算出した候補です"
        }
}

object PrinterStatusValidationPolicy {
    const val MIN_RESPONSE_SAMPLES = 3
    const val HIGH_CONFIDENCE_SAMPLES = 5
    const val MIN_FAILURE_SAMPLES = 2
    const val HIGH_FAILURE_SAMPLES = 5
    const val MIN_AGREEMENT_RATE = 0.80

    val RESPONSE_CONDITIONS = listOf(
        PrinterStatusTestCondition.NORMAL,
        PrinterStatusTestCondition.COVER_OPEN,
        PrinterStatusTestCondition.PAPER_NEAR_END,
        PrinterStatusTestCondition.PAPER_OUT,
        PrinterStatusTestCondition.CUTTER_ERROR,
    )

    val FAILURE_CONDITIONS = listOf(
        PrinterStatusTestCondition.POWER_OFF,
        PrinterStatusTestCondition.LAN_DISCONNECTED,
    )

    fun build(analysis: PrinterStatusDeviceAnalysis): PrinterStatusValidationReport {
        val candidateByCondition = analysis.candidates.associateBy { it.condition }
        val normalCluster = responseCluster(analysis.records, PrinterStatusTestCondition.NORMAL)
        val normalEvidence = responseEvidence(
            analysis = analysis,
            condition = PrinterStatusTestCondition.NORMAL,
            cluster = normalCluster,
            normalCluster = normalCluster,
            candidate = null,
        )
        val responseEvidence = RESPONSE_CONDITIONS
            .filter { it != PrinterStatusTestCondition.NORMAL }
            .map { condition ->
                responseEvidence(
                    analysis = analysis,
                    condition = condition,
                    cluster = responseCluster(analysis.records, condition),
                    normalCluster = normalCluster,
                    candidate = candidateByCondition[condition],
                )
            }
        val failureEvidence = FAILURE_CONDITIONS.map { condition ->
            failureEvidence(analysis.records, condition)
        }
        val evidence = listOf(normalEvidence) + responseEvidence + failureEvidence

        val blockers = buildList {
            if (analysis.key.printerModel.isBlank()) add("実機型番が未入力です")
            if (analysis.key.emulationMode.isBlank()) add("エミュレーション／設定モードが未入力です")
            if (analysis.records.isEmpty()) add("元RAW履歴がありません")
            if (analysis.records.any { it.id <= 0L }) add("元RAW履歴IDに未保存レコードが含まれます")
            evidence.filterNot { it.ready }.forEach { item ->
                add("${item.condition.displayName}：${item.reason}")
            }
        }.distinct()

        val overall = if (blockers.isNotEmpty()) {
            PrinterEvidenceConfidence.NOT_READY
        } else {
            evidence.minByOrNull { it.confidence.rank }?.confidence
                ?: PrinterEvidenceConfidence.NOT_READY
        }

        return PrinterStatusValidationReport(
            analysis = analysis,
            evidence = evidence,
            overallConfidence = overall,
            evidenceReadyForReview = blockers.isEmpty() &&
                overall.rank >= PrinterEvidenceConfidence.MEDIUM.rank,
            blockers = blockers,
        )
    }

    fun buildAll(analyses: List<PrinterStatusDeviceAnalysis>): List<PrinterStatusValidationReport> =
        analyses.map(::build)

    fun responseCluster(
        records: List<PrinterStatusProbeHistoryRecord>,
        condition: PrinterStatusTestCondition,
    ): PrinterStatusResponseCluster {
        val samples = records.asSequence()
            .filter { it.condition == condition && it.success }
            .mapNotNull { record ->
                val normalized = normalizeHex(record.responseHex)
                if (normalized.isBlank()) null else record to normalized
            }
            .toList()
        val counts = samples.map { it.second }.groupingBy { it }.eachCount()
        val dominant = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
        val dominantHex = dominant?.key.orEmpty()
        val dominantCount = dominant?.value ?: 0
        val sampleCount = samples.size
        val outlierIds = samples
            .filter { it.second != dominantHex }
            .map { it.first.id }
            .filter { it > 0L }
            .distinct()
            .sorted()
        val lengths = samples
            .map { PrinterStatusProbeComparisonPolicy.parseHex(it.second).size }
            .distinct()
            .sorted()
        return PrinterStatusResponseCluster(
            condition = condition,
            validSampleCount = sampleCount,
            distinctResponseCount = counts.size,
            dominantResponseHex = dominantHex,
            dominantCount = dominantCount,
            outlierCount = (sampleCount - dominantCount).coerceAtLeast(0),
            agreementRate = if (sampleCount == 0) 0.0 else dominantCount.toDouble() / sampleCount.toDouble(),
            responseLengths = lengths,
            sourceRecordIds = samples.map { it.first.id }.filter { it > 0L }.distinct().sorted(),
            outlierRecordIds = outlierIds,
        )
    }

    fun normalizeHex(value: String): String = PrinterStatusProbeComparisonPolicy.parseHex(value)
        .joinToString(" ") { "%02X".format(it) }

    private fun responseEvidence(
        analysis: PrinterStatusDeviceAnalysis,
        condition: PrinterStatusTestCondition,
        cluster: PrinterStatusResponseCluster,
        normalCluster: PrinterStatusResponseCluster,
        candidate: PrinterStatusConditionBitCandidate?,
    ): PrinterStatusConditionEvidence {
        val progress = analysis.progress.firstOrNull { it.condition == condition }
        val total = progress?.totalCount ?: 0
        val success = progress?.successCount ?: 0
        val failure = progress?.failureCount ?: 0

        val confidence = when {
            cluster.validSampleCount == 0 -> PrinterEvidenceConfidence.NOT_READY
            cluster.validSampleCount < MIN_RESPONSE_SAMPLES -> PrinterEvidenceConfidence.LOW
            cluster.responseLengthMismatch -> PrinterEvidenceConfidence.LOW
            cluster.agreementRate < MIN_AGREEMENT_RATE -> PrinterEvidenceConfidence.LOW
            condition == PrinterStatusTestCondition.NORMAL &&
                cluster.validSampleCount >= HIGH_CONFIDENCE_SAMPLES &&
                cluster.agreementRate == 1.0 -> PrinterEvidenceConfidence.HIGH
            condition == PrinterStatusTestCondition.NORMAL -> PrinterEvidenceConfidence.MEDIUM
            candidate == null || candidate.sizeMismatch || candidate.stableChanges.isEmpty() ->
                PrinterEvidenceConfidence.LOW
            normalCluster.validSampleCount >= HIGH_CONFIDENCE_SAMPLES &&
                cluster.validSampleCount >= HIGH_CONFIDENCE_SAMPLES &&
                normalCluster.agreementRate == 1.0 &&
                cluster.agreementRate == 1.0 &&
                candidate.unstableBitCount == 0 -> PrinterEvidenceConfidence.HIGH
            else -> PrinterEvidenceConfidence.MEDIUM
        }

        val reason = when {
            cluster.validSampleCount == 0 -> "成功RAWがありません"
            cluster.validSampleCount < MIN_RESPONSE_SAMPLES ->
                "成功RAWが${cluster.validSampleCount}件です。最低${MIN_RESPONSE_SAMPLES}件必要です"
            cluster.responseLengthMismatch ->
                "応答長が一致しません（${cluster.responseLengths.joinToString("/")}バイト）"
            cluster.agreementRate < MIN_AGREEMENT_RATE ->
                "同一応答一致率が${cluster.agreementPercent}%です。採取条件または外れ値を確認してください"
            condition == PrinterStatusTestCondition.NORMAL -> "正常応答が再現しています"
            candidate == null -> "正常との差分候補を計算できません"
            candidate.sizeMismatch -> candidate.note
            candidate.stableChanges.isEmpty() -> "正常との差分ビットが安定していません"
            normalCluster.validSampleCount < MIN_RESPONSE_SAMPLES ->
                "正常成功RAWが${normalCluster.validSampleCount}件です"
            normalCluster.responseLengthMismatch ->
                "正常応答長が一致しません（${normalCluster.responseLengths.joinToString("/")}バイト）"
            normalCluster.agreementRate < MIN_AGREEMENT_RATE ->
                "正常応答の一致率が${normalCluster.agreementPercent}%です"
            else -> "${candidate.stableChanges.size}ビットの安定差分を検出"
        }

        val ready = when (condition) {
            PrinterStatusTestCondition.NORMAL ->
                cluster.validSampleCount >= MIN_RESPONSE_SAMPLES &&
                    !cluster.responseLengthMismatch &&
                    cluster.agreementRate >= MIN_AGREEMENT_RATE
            else ->
                confidence.rank >= PrinterEvidenceConfidence.MEDIUM.rank &&
                    candidate != null &&
                    !candidate.sizeMismatch &&
                    candidate.stableChanges.isNotEmpty() &&
                    !cluster.responseLengthMismatch
        }

        return PrinterStatusConditionEvidence(
            condition = condition,
            expectation = PrinterEvidenceExpectation.RESPONSE_PATTERN,
            totalCount = total,
            successCount = success,
            failureCount = failure,
            cluster = cluster,
            candidate = candidate,
            confidence = confidence,
            ready = ready,
            reason = reason,
            sourceRecordIds = cluster.sourceRecordIds,
        )
    }

    private fun failureEvidence(
        records: List<PrinterStatusProbeHistoryRecord>,
        condition: PrinterStatusTestCondition,
    ): PrinterStatusConditionEvidence {
        val conditionRecords = records.filter { it.condition == condition }
        val success = conditionRecords.count { it.success }
        val failure = conditionRecords.count { !it.success }
        val sourceIds = conditionRecords.map { it.id }.filter { it > 0L }.distinct().sorted()
        val ready = failure >= MIN_FAILURE_SAMPLES && success == 0
        val confidence = when {
            failure == 0 -> PrinterEvidenceConfidence.NOT_READY
            !ready -> PrinterEvidenceConfidence.LOW
            failure >= HIGH_FAILURE_SAMPLES -> PrinterEvidenceConfidence.HIGH
            else -> PrinterEvidenceConfidence.MEDIUM
        }
        val reason = when {
            success > 0 -> "通信失敗を想定する条件ですが成功記録が${success}件あります"
            failure < MIN_FAILURE_SAMPLES -> "失敗記録が${failure}件です。最低${MIN_FAILURE_SAMPLES}件必要です"
            else -> "通信失敗が${failure}件再現しています"
        }
        return PrinterStatusConditionEvidence(
            condition = condition,
            expectation = PrinterEvidenceExpectation.CONNECTION_FAILURE,
            totalCount = conditionRecords.size,
            successCount = success,
            failureCount = failure,
            cluster = null,
            candidate = null,
            confidence = confidence,
            ready = ready,
            reason = reason,
            sourceRecordIds = sourceIds,
        )
    }
}

object PrinterStatusValidationCsv {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    fun render(report: PrinterStatusValidationReport): String = buildString {
        append('\uFEFF')
        appendRow("つぐレジ プリンター検証最終レポート")
        appendRow("生成日時", format(report.generatedAt))
        appendRow("実機型番", report.key.printerModel)
        appendRow("エミュレーション", report.key.emulationMode)
        appendRow("プロファイル", report.key.profile.displayName)
        appendRow("プリセット", report.key.preset.displayName)
        appendRow("接続先", "${report.key.host}:${report.key.port}")
        appendRow("レビュー可能", if (report.evidenceReadyForReview) "はい" else "いいえ")
        appendRow("総合信頼度", report.overallConfidence.displayName)
        appendRow("安定差分ビット数", report.stableChangeCount.toString())
        appendRow("外れ値候補数", report.totalOutlierCount.toString())
        appendRow("応答長不一致条件数", report.responseLengthMismatchCount.toString())
        appendRow("元履歴ID", report.sourceRecordIds.joinToString("/"))
        appendRow("メーカー仕様区分", report.manufacturerVerificationNote)
        appendRow(
            "重要",
            "本レポートは保存済み実機証跡から作成した解析候補です。承認してもPrinterStatus解析、販売監視、自動印刷前確認へ自動適用しません",
        )
        appendRow(
            "安全注意",
            "CI成功は実機確認済みを意味しません。STAR／汎用DLE EOT互換をメーカー仕様確認済みとは表現しません",
        )
        append('\n')
        appendRow(
            "条件", "期待結果", "総数", "成功", "失敗", "有効応答", "応答長",
            "異なる応答数", "代表応答", "代表応答数", "外れ値候補", "外れ値履歴ID",
            "一致率", "信頼度", "レビュー成立", "判定理由", "元履歴ID",
        )
        report.evidence.forEach { item ->
            appendRow(
                item.condition.displayName,
                item.expectation.displayName,
                item.totalCount.toString(),
                item.successCount.toString(),
                item.failureCount.toString(),
                item.cluster?.validSampleCount?.toString().orEmpty(),
                item.cluster?.responseLengths?.joinToString("/").orEmpty(),
                item.cluster?.distinctResponseCount?.toString().orEmpty(),
                item.cluster?.dominantResponseHex.orEmpty(),
                item.cluster?.dominantCount?.toString().orEmpty(),
                item.cluster?.outlierCount?.toString().orEmpty(),
                item.cluster?.outlierRecordIds?.joinToString("/").orEmpty(),
                item.cluster?.agreementPercent?.let { "$it%" }.orEmpty(),
                item.confidence.displayName,
                if (item.ready) "成立" else "未成立",
                item.reason,
                item.sourceRecordIds.joinToString("/"),
            )
        }
        append('\n')
        appendRow("条件", "byte index", "bit", "mask", "正常値", "条件値", "正常サンプル", "条件サンプル")
        report.evidence.forEach { item ->
            item.candidate?.stableChanges.orEmpty().forEach { change ->
                appendRow(
                    item.condition.displayName,
                    change.byteIndex.toString(),
                    change.bitIndex.toString(),
                    "0x${"%02X".format(change.mask)}",
                    change.normalValue.toString(),
                    change.conditionValue.toString(),
                    item.candidate.normalSampleCount.toString(),
                    item.candidate.conditionSampleCount.toString(),
                )
            }
        }
        if (report.blockers.isNotEmpty()) {
            append('\n')
            appendRow("未成立理由")
            report.blockers.forEach { appendRow(it) }
        }
        append('\n')
        appendRow("ソフトウェア実装範囲")
        PrinterSoftwareCompletionPolicy.items.forEach { item ->
            appendRow(item.title, if (item.completed) "実装済み" else "未完了", item.detail)
        }
    }

    private fun StringBuilder.appendRow(vararg values: String) {
        append(values.joinToString(",") { PrinterStatusProbeMultiCsv.escape(it) })
        append('\n')
    }

    private fun format(epochMillis: Long): String = synchronized(dateFormat) {
        dateFormat.format(Date(epochMillis))
    }
}

data class PrinterSoftwareCompletionItem(
    val title: String,
    val completed: Boolean,
    val detail: String,
)

object PrinterSoftwareCompletionPolicy {
    val items = listOf(
        PrinterSoftwareCompletionItem("送信結果不明時の自動再送禁止", true, "WRITE_STARTED以降の失敗は紙確認を要求"),
        PrinterSoftwareCompletionItem("全帳票の統合印刷キュー", true, "売上・返品取消・点検・精算を統合"),
        PrinterSoftwareCompletionItem("状態取得方式の信頼区分", true, "EPSON仕様確認済みとSTAR／汎用未検証を分離"),
        PrinterSoftwareCompletionItem("販売中の状態監視と管理者通知", true, "継続異常を画面・Android通知へ表示"),
        PrinterSoftwareCompletionItem("安全停止付き連続印刷試験", true, "能力判定・各送信前確認・バックグラウンド停止・結果保存"),
        PrinterSoftwareCompletionItem("RAW採取・条件注記・履歴比較", true, "型番・モード・条件・HEX・CSVを保存"),
        PrinterSoftwareCompletionItem("再現性・外れ値・変化ビット候補", true, "保存済みRAWだけで機械集計"),
        PrinterSoftwareCompletionItem("最終検証・承認候補と監査証跡", true, "候補承認はランタイムへ自動適用しない"),
        PrinterSoftwareCompletionItem("実機確認", false, "プリンター実機で別途実施が必要"),
        PrinterSoftwareCompletionItem("本番署名", false, "現在のAPKは開発版署名"),
    )

    val softwareImplemented: Boolean
        get() = items.filterNot { it.title == "実機確認" || it.title == "本番署名" }.all { it.completed }
}
