from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


# OperationsStore: X inspection is a live read only aggregate. Persistent settlement path is Z only.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''    fun dailySummary(date: LocalDate? = null): DailyOperationsSummary {\n        BusinessSessionSchema.ensure(db)\n        val session = if (date == null) {\n            queryActiveSession(db)?.toWindow()\n                ?: BusinessSessionSchema.sessionForDate(db, LocalDate.now())\n                ?: BusinessSessionDisplayFallback.forDate(LocalDate.now())\n        } else {\n            BusinessSessionSchema.sessionForDate(db, date)\n                ?: BusinessSessionDisplayFallback.forDate(date)\n        } ?: error("営業日を特定できません")\n        return summaryForSession(session)\n    }\n''',
    '''    fun dailySummary(date: LocalDate? = null): DailyOperationsSummary {\n        BusinessSessionSchema.ensure(db)\n        val session = if (date == null) {\n            queryActiveSession(db)?.toWindow()\n                ?: BusinessSessionSchema.sessionForDate(db, LocalDate.now())\n                ?: BusinessSessionDisplayFallback.forDate(LocalDate.now())\n        } else {\n            BusinessSessionSchema.sessionForDate(db, date)\n                ?: BusinessSessionDisplayFallback.forDate(date)\n        } ?: error("営業日を特定できません")\n        return summaryForSession(session)\n    }\n\n    /** REP-003: X点検は現在値を読み取るだけで固定スナップショットを保存しない。 */\n    fun inspectX(): DailyOperationsSummary = dailySummary()\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''    fun recordSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        operatorName: String,\n        pendingPrintsAcknowledged: Boolean = false,\n    ): Long {\n        require(operatorName.isNotBlank()) { "担当者を入力してください" }\n''',
    '''    fun recordSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        operatorName: String,\n        pendingPrintsAcknowledged: Boolean = false,\n    ): Long {\n        require(type == SettlementReportType.Z_SETTLEMENT) {\n            "X点検はリアルタイム表示のみで固定スナップショットを保存しません"\n        }\n        require(operatorName.isNotBlank()) { "担当者を入力してください" }\n''',
)

# Coordinator: expose read-only X path, block X from persistent settlement path before any scheduler/backup side effects.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt',
    '''    fun recordSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        managerPin: String,\n        pendingPrintsAcknowledged: Boolean = false,\n    ): Long {\n        SettlementActualCashSafetyV105.validate(type, actualCash)\n''',
    '''    fun inspectX(): DailyOperationsSummary =\n        executionGuard.runExclusive("X_INSPECTION:LIVE", "X点検を更新中です") {\n            requireOperator(OperationsAction.X_INSPECTION)\n            store.inspectX()\n        }\n\n    fun recordSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        managerPin: String,\n        pendingPrintsAcknowledged: Boolean = false,\n    ): Long {\n        require(type == SettlementReportType.Z_SETTLEMENT) {\n            "X点検はリアルタイム表示のみで固定スナップショットを保存しません"\n        }\n        SettlementActualCashSafetyV105.validate(type, actualCash)\n''',
)

# Main operations UI: X refresh never calls recordSettlement and never shows current-session persisted X history.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''    fun executeSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        pin: String,\n        pendingPrintsAcknowledged: Boolean,\n    ) {\n        val result = runCatching {\n            secureStore.recordSettlement(type, actualCash, pin, pendingPrintsAcknowledged)\n        }\n        message = result.fold(\n            onSuccess = {\n                if (type == SettlementReportType.Z_SETTLEMENT) {\n                    "Z精算を保存し、営業を終了しました（No.$it）"\n                } else {\n                    "X点検を保存しました（No.$it）"\n                }\n            },\n            onFailure = { it.message ?: "保存に失敗しました" },\n        )\n        if (result.isSuccess) revision++\n        activeOperator = OperatorSessionRegistry.current(appContext)\n    }\n''',
    '''    fun inspectX() {\n        val result = runCatching { secureStore.inspectX() }\n        message = result.fold(\n            onSuccess = { "X点検を更新しました（固定スナップショットは保存しません）" },\n            onFailure = { it.message ?: "X点検の更新に失敗しました" },\n        )\n        if (result.isSuccess) revision++\n        activeOperator = OperatorSessionRegistry.current(appContext)\n    }\n\n    fun executeSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        pin: String,\n        pendingPrintsAcknowledged: Boolean,\n    ) {\n        require(type == SettlementReportType.Z_SETTLEMENT) { "永続化する精算はZ精算だけです" }\n        val result = runCatching {\n            secureStore.recordSettlement(type, actualCash, pin, pendingPrintsAcknowledged)\n        }\n        message = result.fold(\n            onSuccess = { "Z精算を保存し、営業を終了しました（No.$it）" },\n            onFailure = { it.message ?: "保存に失敗しました" },\n        )\n        if (result.isSuccess) revision++\n        activeOperator = OperatorSessionRegistry.current(appContext)\n    }\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                history = store.activeBusinessSession()?.let {\n                    store.recentSettlementsForSession(it.id, SettlementReportType.X_INSPECTION)\n                } ?: emptyList(),\n''',
    '''                history = emptyList(),\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->\n                    executeSettlement(\n                        SettlementReportType.X_INSPECTION,\n                        actualCash,\n                        pin,\n                        pendingPrintsAcknowledged,\n                    )\n                },\n''',
    '''                onExecute = { _, _, _ -> inspectX() },\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                    } else {\n                        "X点検は現在の営業セッションの値を保存します。営業は終了せず、そのまま販売を継続できます。"\n                    },\n''',
    '''                    } else {\n                        "X点検は現在の営業セッションをリアルタイム集計します。固定スナップショットや印刷ジョブは保存せず、営業状態も変更しません。"\n                    },\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''            OpPanel(Modifier.weight(1f).fillMaxHeight()) {\n                Text("保存履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)\n                Spacer(Modifier.height(8.dp))\n                if (history.isEmpty()) {\n                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }\n                } else {\n                    LazyColumn {\n                        itemsIndexed(history) { _, record ->\n                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {\n                                Row(Modifier.fillMaxWidth()) {\n                                    Text(record.type.displayName, fontWeight = FontWeight.Bold, color = OpNavy)\n                                    Spacer(Modifier.weight(1f))\n                                    Text(opDateTime(record.createdAt), color = Color.Gray)\n                                }\n                                Text("${record.businessDate}  セッションNo.${record.businessSessionId}")\n                                Text("純売上 ${opYen(record.netSales)}  差異 ${signedYen(record.variance)}")\n                                Text("担当 ${record.operatorName}", color = Color.Gray)\n                            }\n                        }\n                    }\n                }\n            }\n''',
    '''            OpPanel(Modifier.weight(1f).fillMaxHeight()) {\n                Text(if (isZSettlement) "保存履歴" else "X点検の扱い", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)\n                Spacer(Modifier.height(8.dp))\n                if (!isZSettlement) {\n                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {\n                        Text(\n                            "X点検は現在値の表示のみです。\\n固定履歴・固定帳票・印刷ジョブは作成しません。",\n                            color = Color.Gray,\n                            textAlign = TextAlign.Center,\n                        )\n                    }\n                } else if (history.isEmpty()) {\n                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }\n                } else {\n                    LazyColumn {\n                        itemsIndexed(history) { _, record ->\n                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {\n                                Row(Modifier.fillMaxWidth()) {\n                                    Text(record.type.displayName, fontWeight = FontWeight.Bold, color = OpNavy)\n                                    Spacer(Modifier.weight(1f))\n                                    Text(opDateTime(record.createdAt), color = Color.Gray)\n                                }\n                                Text("${record.businessDate}  セッションNo.${record.businessSessionId}")\n                                Text("純売上 ${opYen(record.netSales)}  差異 ${signedYen(record.variance)}")\n                                Text("担当 ${record.operatorName}", color = Color.Gray)\n                            }\n                        }\n                    }\n                }\n            }\n''',
)

# Standalone v0.30 settlement route: X uses the same read-only path; only Z reads persisted current-session history.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt',
    '''        val history = session?.let {\n            store.recentSettlementsForSession(it.id, reportType)\n        }.orEmpty()\n''',
    '''        val history = if (reportType == SettlementReportType.X_INSPECTION) {\n            emptyList()\n        } else {\n            session?.let { store.recentSettlementsForSession(it.id, reportType) }.orEmpty()\n        }\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt',
    '''            onExecute = { actualCash, managerPin, pendingAcknowledged ->\n                val result = runCatching {\n                    secureStore.recordSettlement(\n                        type = reportType,\n                        actualCash = actualCash,\n                        managerPin = managerPin,\n                        pendingPrintsAcknowledged = pendingAcknowledged,\n                    )\n                }\n                message = result.fold(\n                    onSuccess = {\n                        if (reportType == SettlementReportType.Z_SETTLEMENT) {\n                            "Z精算を保存し、営業を終了しました（No.$it）"\n                        } else {\n                            "X点検を保存しました（No.$it）"\n                        }\n                    },\n                    onFailure = { it.message ?: "保存に失敗しました" },\n                )\n                if (result.isSuccess) revision++\n                operator = OperatorSessionRegistry.current(appContext)\n            },\n''',
    '''            onExecute = { actualCash, managerPin, pendingAcknowledged ->\n                if (reportType == SettlementReportType.X_INSPECTION) {\n                    val result = runCatching { secureStore.inspectX() }\n                    message = result.fold(\n                        onSuccess = { "X点検を更新しました（固定スナップショットは保存しません）" },\n                        onFailure = { it.message ?: "X点検の更新に失敗しました" },\n                    )\n                    if (result.isSuccess) revision++\n                } else {\n                    val result = runCatching {\n                        secureStore.recordSettlement(\n                            type = reportType,\n                            actualCash = actualCash,\n                            managerPin = managerPin,\n                            pendingPrintsAcknowledged = pendingAcknowledged,\n                        )\n                    }\n                    message = result.fold(\n                        onSuccess = { "Z精算を保存し、営業を終了しました（No.$it）" },\n                        onFailure = { it.message ?: "保存に失敗しました" },\n                    )\n                    if (result.isSuccess) revision++\n                }\n                operator = OperatorSessionRegistry.current(appContext)\n            },\n''',
)

# Pass report type to the history panel in both responsive layouts.
text_path = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt'
text = text_path.read_text(encoding='utf-8')
old = '''                    SettlementHistoryPanelV030(\n                        Modifier.fillMaxWidth().heightIn(min = 180.dp),\n                        history,\n                    )'''
new = '''                    SettlementHistoryPanelV030(\n                        Modifier.fillMaxWidth().heightIn(min = 180.dp),\n                        reportType,\n                        history,\n                    )'''
if text.count(old) != 1:
    raise SystemExit(f'SettlementActivity compact history call expected one match, found {text.count(old)}')
text = text.replace(old, new, 1)
old = '''                    SettlementHistoryPanelV030(\n                        Modifier.weight(1.05f).fillMaxHeight(),\n                        history,\n                    )'''
new = '''                    SettlementHistoryPanelV030(\n                        Modifier.weight(1.05f).fillMaxHeight(),\n                        reportType,\n                        history,\n                    )'''
if text.count(old) != 1:
    raise SystemExit(f'SettlementActivity wide history call expected one match, found {text.count(old)}')
text = text.replace(old, new, 1)
text_path.write_text(text, encoding='utf-8')

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt',
    '''private fun SettlementHistoryPanelV030(\n    modifier: Modifier,\n    history: List<SettlementRecord>,\n) {\n    SettlementPanelV030(modifier) {\n        Text("保存履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SettlementNavyV030)\n        Spacer(Modifier.height(8.dp))\n        if (history.isEmpty()) {\n            Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {\n                Text("履歴はありません", color = Color.Gray)\n            }\n        } else {\n''',
    '''private fun SettlementHistoryPanelV030(\n    modifier: Modifier,\n    reportType: SettlementReportType,\n    history: List<SettlementRecord>,\n) {\n    val isZ = reportType == SettlementReportType.Z_SETTLEMENT\n    SettlementPanelV030(modifier) {\n        Text(if (isZ) "保存履歴" else "X点検の扱い", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SettlementNavyV030)\n        Spacer(Modifier.height(8.dp))\n        if (!isZ) {\n            Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {\n                Text("X点検はリアルタイム表示のみです。固定履歴・固定帳票・印刷ジョブは作成しません。", color = Color.Gray)\n            }\n        } else if (history.isEmpty()) {\n            Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {\n                Text("履歴はありません", color = Color.Gray)\n            }\n        } else {\n''',
)
