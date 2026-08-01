package jp.co.tenposinfo.register

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrinterSoakTestPlan(
    val totalPrints: Int,
    val intervalMillis: Long,
    val cutEachPrint: Boolean,
)

object PrinterSoakTestPolicy {
    const val MIN_PRINTS = 1
    const val MAX_PRINTS = 500
    const val MIN_INTERVAL_MILLIS = 1_000L
    const val MAX_INTERVAL_MILLIS = 60_000L

    fun validationError(plan: PrinterSoakTestPlan): String? = when {
        plan.totalPrints !in MIN_PRINTS..MAX_PRINTS ->
            "印刷回数は${MIN_PRINTS}～${MAX_PRINTS}回で指定してください"

        plan.intervalMillis !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS ->
            "印刷間隔は1～60秒で指定してください"

        else -> null
    }

    /** 長時間試験では注意状態も停止対象とし、READY時だけ次の1枚を送信する。 */
    fun canSend(status: PrinterRealtimeStatus): Boolean = status.level == PrinterStatusLevel.READY

    fun stoppedByStatusMessage(status: PrinterRealtimeStatus): String =
        "連続印刷を停止しました：${status.summary}。状態を確認し、試験を最初から再開してください"

    fun stoppedByFailureMessage(error: Throwable): String = when (PrinterRetrySafety.classify(error)) {
        PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED ->
            "送信結果が不明なため停止しました。紙が出ている可能性があります。必ず排紙を確認し、自動再送しないでください"

        PrinterFailureDisposition.SAFE_TO_RETRY ->
            "送信前に失敗したため停止しました。接続状態を確認してから、試験を手動で再開してください"
    }

    fun pageText(
        sequence: Int,
        total: Int,
        configuration: PrinterConfiguration,
        startedAt: Long,
    ): String {
        val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)
        return buildString {
            appendLine("つぐレジ 連続印刷試験")
            appendLine("試験番号：$sequence / $total")
            appendLine("開始時刻：${formatter.format(Date(startedAt))}")
            appendLine("印字時刻：${formatter.format(Date())}")
            appendLine("プリンター：${configuration.name}")
            appendLine("接続先：${configuration.host}:${configuration.port}")
            appendLine("機種：${configuration.profile.displayName}")
            appendLine("用紙幅：${configuration.paperWidthMm}mm")
            appendLine("--------------------------------")
            appendLine("あいうえお アイウエオ 1234567890")
            appendLine("日本語印字・通信・連続動作確認")
            appendLine("--------------------------------")
            append("この用紙は売上レシートではありません")
        }
    }
}
