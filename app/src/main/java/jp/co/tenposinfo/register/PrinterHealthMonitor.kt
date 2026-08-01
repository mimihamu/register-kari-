package jp.co.tenposinfo.register

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PrinterHealthLevel {
    CHECKING,
    READY,
    WARNING,
    ERROR,
    DISABLED,
    UNCONFIGURED,
}

data class PrinterHealthSnapshot(
    val level: PrinterHealthLevel,
    val title: String,
    val detail: String,
    val checkedAt: Long,
    val printerName: String,
) {
    companion object {
        fun checking(): PrinterHealthSnapshot = PrinterHealthSnapshot(
            level = PrinterHealthLevel.CHECKING,
            title = "プリンター状態を確認中",
            detail = "販売は継続できます",
            checkedAt = 0L,
            printerName = "レシートプリンター",
        )
    }
}

/**
 * 販売画面の常時表示用状態確認。
 * 10秒間隔で呼ばれるため履歴・監査ログには書き込まず、手動診断と印刷前診断だけを記録対象とする。
 */
object PrinterHealthMonitor {
    fun check(context: Context): PrinterHealthSnapshot {
        val appContext = context.applicationContext
        val configuration = AdminSettingsStore(appContext).use { it.loadPrinterConfiguration() }
        val runtime = PrinterMonitoringStore(appContext).use { it.loadSettings() }
        val now = System.currentTimeMillis()
        val capability = PrinterStatusCapabilityRegistry.forProfile(configuration.profile)

        val snapshot = when {
            !configuration.enabled -> PrinterHealthSnapshot(
                level = PrinterHealthLevel.DISABLED,
                title = "プリンターは使用しない設定です",
                detail = "売上は保存されます。レシートは印刷されません",
                checkedAt = now,
                printerName = configuration.name,
            )

            configuration.host.isBlank() || configuration.port !in 1..65535 -> PrinterHealthSnapshot(
                level = PrinterHealthLevel.UNCONFIGURED,
                title = "プリンター接続先が未設定です",
                detail = "各種設定でIPアドレスとポートを登録してください",
                checkedAt = now,
                printerName = configuration.name,
            )

            !capability.automaticQueryAllowed -> PrinterHealthSnapshot(
                level = PrinterHealthLevel.WARNING,
                title = "状態自動監視は未検証です",
                detail = "${configuration.profile.displayName} / 印刷送信は利用可能 / 診断画面の手動互換試行で確認",
                checkedAt = now,
                printerName = configuration.name,
            )

            else -> TcpPrinterStatusClient(configuration).query(
                purpose = PrinterStatusCheckPurpose.SALES_MONITORING,
            ).fold(
                onSuccess = { status ->
                    val suffix = if (runtime.preflightEnabled) "印刷前診断：有効" else "印刷前診断：無効"
                    PrinterHealthSnapshot(
                        level = when (status.level) {
                            PrinterStatusLevel.READY -> PrinterHealthLevel.READY
                            PrinterStatusLevel.WARNING -> PrinterHealthLevel.WARNING
                            PrinterStatusLevel.OFFLINE,
                            PrinterStatusLevel.ERROR,
                            -> PrinterHealthLevel.ERROR
                        },
                        title = status.summary,
                        detail = "${configuration.host}:${configuration.port} / ${status.elapsedMillis}ms / $suffix",
                        checkedAt = status.checkedAt,
                        printerName = configuration.name,
                    )
                },
                onFailure = { error ->
                    PrinterHealthSnapshot(
                        level = PrinterHealthLevel.ERROR,
                        title = "プリンターから応答がありません",
                        detail = "${configuration.host}:${configuration.port} / ${error.message ?: error.javaClass.simpleName}",
                        checkedAt = now,
                        printerName = configuration.name,
                    )
                },
            )
        }

        return PrinterPersistentAlertCoordinator.apply(appContext, snapshot, now)
    }
}

object PrinterHealthPolicy {
    fun requiresAttention(snapshot: PrinterHealthSnapshot): Boolean = when (snapshot.level) {
        PrinterHealthLevel.WARNING,
        PrinterHealthLevel.ERROR,
        PrinterHealthLevel.UNCONFIGURED,
        -> true

        PrinterHealthLevel.CHECKING,
        PrinterHealthLevel.READY,
        PrinterHealthLevel.DISABLED,
        -> false
    }
}

object PrinterHealthUiPolicy {
    const val STALE_AFTER_MILLIS = 30_000L

    fun shouldPoll(isSalesScreenVisible: Boolean, isActivityResumed: Boolean): Boolean =
        isSalesScreenVisible && isActivityResumed

    fun isStale(snapshot: PrinterHealthSnapshot, nowMillis: Long): Boolean =
        snapshot.checkedAt <= 0L || nowMillis - snapshot.checkedAt >= STALE_AFTER_MILLIS

    fun checkedAtLabel(snapshot: PrinterHealthSnapshot, nowMillis: Long): String {
        if (snapshot.checkedAt <= 0L) return "最終確認：未確認"
        val checkedTime = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(snapshot.checkedAt))
        return if (isStale(snapshot, nowMillis)) {
            "最終確認：$checkedTime（古い情報）"
        } else {
            "最終確認：$checkedTime"
        }
    }
}
