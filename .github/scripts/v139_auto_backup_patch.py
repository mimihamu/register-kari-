from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'PATCH_MISS: {label}')
    if text.count(old) != 1:
        raise SystemExit(f'PATCH_AMBIGUOUS: {label} count={text.count(old)}')
    return text.replace(old, new, 1)


# ---- AutoBackup.kt: central Z-settlement toggle ---------------------------
p = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/AutoBackup.kt'
s = p.read_text()
s = replace_once(
    s,
'''    ) {
        enqueue(
            context = context,
            uniqueName = AutoBackupTriggerPolicy.uniqueZWorkName(settlementId),
            reason = BackupCreationReason.Z_SETTLEMENT,''',
'''    ) {
        val appContext = context.applicationContext
        if (!AutoBackupSettingsStore(appContext).load().settlementAutoBackupEnabled) {
            AutoBackupAudit.record(
                appContext,
                "DATA_BACKUP_Z_SKIPPED_DISABLED",
                "settlementId=$settlementId / businessSessionId=$businessSessionId",
                actorName,
                settlementId,
            )
            return
        }
        enqueue(
            context = appContext,
            uniqueName = AutoBackupTriggerPolicy.uniqueZWorkName(settlementId),
            reason = BackupCreationReason.Z_SETTLEMENT,''',
    'Z settlement setting gate',
)
p.write_text(s)


# ---- AutoBackupSettingsActivity.kt: settings UI ---------------------------
p = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/AutoBackupSettingsActivity.kt'
s = p.read_text()

s = replace_once(
    s,
'''    var periodicEnabled by remember { mutableStateOf(initial.periodicEnabled) }
    var cadence by remember { mutableStateOf(initial.cadence) }
    var preferredHourText by remember { mutableStateOf(initial.preferredHour.toString()) }
    var zRetentionText by remember { mutableStateOf(initial.zRetentionBusinessDays.toString()) }''',
'''    var periodicEnabled by remember { mutableStateOf(initial.periodicEnabled) }
    var cadence by remember { mutableStateOf(initial.cadence) }
    var preferredHourText by remember { mutableStateOf(initial.preferredHour.toString()) }
    var preferredWeekday by remember { mutableIntStateOf(initial.preferredWeekday) }
    var settlementAutoBackupEnabled by remember { mutableStateOf(initial.settlementAutoBackupEnabled) }
    var zRetentionText by remember { mutableStateOf(initial.zRetentionBusinessDays.toString()) }''',
    'settings state',
)

s = replace_once(
    s,
'''                    preferredHour = hour ?: error("実行時刻を入力してください"),
                    zRetentionBusinessDays = zDays ?: error("Z精算保持営業日を入力してください"),
                    monthlyRetentionMonths = months ?: error("定期保持月数を入力してください"),
                    failureNotificationsEnabled = failureNotificationsEnabled,''',
'''                    preferredHour = hour ?: error("実行時刻を入力してください"),
                    zRetentionBusinessDays = zDays ?: error("Z精算保持営業日を入力してください"),
                    monthlyRetentionMonths = months ?: error("定期保持月数を入力してください"),
                    failureNotificationsEnabled = failureNotificationsEnabled,
                    preferredWeekday = preferredWeekday,
                    settlementAutoBackupEnabled = settlementAutoBackupEnabled,''',
    'draft wiring',
)

s = replace_once(
    s,
'''            preferredHour = it.preferredHour,
            zoneId = ZoneId.systemDefault(),
        )''',
'''            preferredHour = it.preferredHour,
            zoneId = ZoneId.systemDefault(),
            cadence = it.cadence,
            preferredWeekday = it.preferredWeekday,
        )''',
    'preview schedule wiring',
)

s = replace_once(
    s,
'''                Text("Z精算後バックアップは常時有効", color = Color.White)''',
'''                Text("定期スケジュール・Z精算後を個別設定", color = Color.White)''',
    'header wording',
)

cadence_block = '''                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PeriodicBackupCadence.entries.forEach { option ->
                                    val selected = cadence == option
                                    if (selected) {
                                        Button(
                                            onClick = { cadence = option },
                                            modifier = Modifier.weight(1f).height(50.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AbsBlue),
                                        ) { Text(option.displayName, fontWeight = FontWeight.Bold) }
                                    } else {
                                        OutlinedButton(
                                            onClick = { cadence = option },
                                            modifier = Modifier.weight(1f).height(50.dp),
                                        ) { Text(option.displayName) }
                                    }
                                }
                            }
'''
weekday_and_settlement = cadence_block + '''                            if (cadence == PeriodicBackupCadence.WEEKLY) {
                                Text("指定曜日", color = AbsNavy, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (1..7).forEach { day ->
                                        val label = AutoBackupSettingsPolicy.weekdayDisplayName(day)
                                        if (preferredWeekday == day) {
                                            Button(
                                                onClick = { preferredWeekday = day; message = null },
                                                modifier = Modifier.weight(1f).height(46.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = AbsBlue),
                                            ) { Text(label, fontWeight = FontWeight.Bold) }
                                        } else {
                                            OutlinedButton(
                                                onClick = { preferredWeekday = day; message = null },
                                                modifier = Modifier.weight(1f).height(46.dp),
                                            ) { Text(label) }
                                        }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(
                                    checked = settlementAutoBackupEnabled,
                                    onCheckedChange = { settlementAutoBackupEnabled = it; message = null },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (settlementAutoBackupEnabled) "Z精算後バックアップ：ON" else "Z精算後バックアップ：OFF",
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "ONではZ精算確定後、同一精算につき1回だけ自動バックアップします。",
                                        color = Color.DarkGray,
                                    )
                                }
                            }
'''
s = replace_once(s, cadence_block, weekday_and_settlement, 'weekday and settlement UI')

s = replace_once(
    s,
'''                            "periodic=${saved.periodicEnabled} / cadence=${saved.cadence.name} / hour=${saved.preferredHour} / zDays=${saved.zRetentionBusinessDays} / months=${saved.monthlyRetentionMonths} / notify=${saved.failureNotificationsEnabled}",''',
'''                            "periodic=${saved.periodicEnabled} / cadence=${saved.cadence.name} / hour=${saved.preferredHour} / weekday=${saved.preferredWeekday} / settlementAutoBackup=${saved.settlementAutoBackupEnabled} / zDays=${saved.zRetentionBusinessDays} / months=${saved.monthlyRetentionMonths} / notify=${saved.failureNotificationsEnabled}",''',
    'settings audit detail',
)

p.write_text(s)
