package jp.co.tenposinfo.register

enum class SettlementReconciliationSeverity {
    OK,
    INFO,
    ALERT,
}

data class SettlementReconciliationField(
    val label: String,
    val savedValue: String,
    val currentValue: String,
) {
    val matches: Boolean get() = savedValue == currentValue
}

data class SettlementReconciliationResult(
    val reportId: Long,
    val reportType: SettlementReportType,
    val businessSessionId: Long,
    val businessDate: String,
    val fullSnapshot: Boolean,
    val fields: List<SettlementReconciliationField>,
    val severity: SettlementReconciliationSeverity,
    val message: String,
) {
    val differences: List<SettlementReconciliationField> get() = fields.filterNot { it.matches }
    val exactMatch: Boolean get() = fullSnapshot && differences.isEmpty()
}

/**
 * v0.78 点検・精算の保存snapshotと現在DB集計を、業務データを変更せず比較する。
 * X点検後は営業継続により差が発生し得るためINFO、Z精算後の差異はALERTとする。
 */
object SettlementReconciliationPolicyV078 {
    fun compare(
        saved: SettlementRecord,
        current: DailyOperationsSummary,
    ): SettlementReconciliationResult {
        val fullSnapshot = saved.snapshotVersion >= SettlementSnapshotSchemaV027.SNAPSHOT_VERSION
        val fields = buildList {
            add(field("営業日", saved.businessDate, current.businessDate))
            add(field("営業セッション", saved.businessSessionId, current.businessSessionId))
            add(field("売上総額", saved.salesGross, current.salesGross))
            add(field("返品・取消", saved.reversalGross, current.reversalGross))
            add(field("純売上", saved.netSales, current.netSales))
            add(field("現金理論", saved.expectedCash, current.expectedCash))
            add(field("売上件数", saved.transactionCount.toLong(), current.transactionCount.toLong()))
            add(field("返品・取消件数", saved.reversalCount.toLong(), current.reversalCount.toLong()))
            if (fullSnapshot) {
                add(field("開始釣銭", saved.openingCash, current.openingCash))
                add(field("入金", saved.cashIn, current.cashIn))
                add(field("出金", saved.cashOut, current.cashOut))
            }
        }
        val differences = fields.filterNot { it.matches }
        val severity = when {
            differences.isEmpty() && fullSnapshot -> SettlementReconciliationSeverity.OK
            differences.isNotEmpty() && saved.type == SettlementReportType.Z_SETTLEMENT ->
                SettlementReconciliationSeverity.ALERT
            else -> SettlementReconciliationSeverity.INFO
        }
        val message = when {
            differences.isEmpty() && fullSnapshot ->
                "保存値と現在DB集計は一致しています。"
            differences.isEmpty() ->
                "比較可能な保存値は一致しています。旧形式snapshotのため完全照合はできません。"
            saved.type == SettlementReportType.X_INSPECTION ->
                "X点検後の取引・返品・入出金で現在値が変わるため、差異は参考情報です。"
            !fullSnapshot ->
                "Z精算の保存値と現在DB集計に差異があります。旧形式snapshotのため比較範囲は限定されています。監査してください。"
            else ->
                "Z精算の保存値と現在DB集計に差異があります。監査してください。"
        }
        return SettlementReconciliationResult(
            reportId = saved.id,
            reportType = saved.type,
            businessSessionId = saved.businessSessionId,
            businessDate = saved.businessDate,
            fullSnapshot = fullSnapshot,
            fields = fields,
            severity = severity,
            message = message,
        )
    }

    private fun field(label: String, saved: Long, current: Long) = SettlementReconciliationField(
        label = label,
        savedValue = saved.toString(),
        currentValue = current.toString(),
    )

    private fun field(label: String, saved: String, current: String) = SettlementReconciliationField(
        label = label,
        savedValue = saved,
        currentValue = current,
    )
}
