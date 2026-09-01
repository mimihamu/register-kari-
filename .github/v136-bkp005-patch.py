from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"anchor count={count}: {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


# Sales journal identity: persistent generation is carried in every exported event.
p = "app/src/main/java/jp/co/tenposinfo/register/SalesJournalJsonContract.kt"
replace_once(p,
'''data class SalesJournalIdentity(
    val storeId: String,
    val terminalId: String,
)''',
'''data class SalesJournalIdentity(
    val storeId: String,
    val terminalId: String,
    val generation: Long = 1L,
)''')
replace_once(p,
'''            append("\\\"terminalId\\\":\\\"").append(escape(identity.terminalId)).append("\\\",")
            append("\\\"businessDate\\\":\\\"")''',
'''            append("\\\"terminalId\\\":\\\"").append(escape(identity.terminalId)).append("\\\",")
            append("\\\"terminalGeneration\\\":").append(identity.generation).append(',')
            append("\\\"businessDate\\\":\\\"")''')
replace_once(p,
'''    private const val TERMINAL_ID_KEY = "sales_journal_terminal_id"
    private const val DEFAULT_STORE_ID = "STORE-UNCONFIGURED"''',
'''    private const val TERMINAL_ID_KEY = "sales_journal_terminal_id"
    private const val GENERATION_KEY = "sales_journal_terminal_generation"
    private const val DEFAULT_STORE_ID = "STORE-UNCONFIGURED"''')
replace_once(p,
'''        putIfMissing(db, TERMINAL_ID_KEY, "TERMINAL-${UUID.randomUUID().toString().uppercase(Locale.ROOT)}")
        return SalesJournalIdentity(
            storeId = read(db, STORE_ID_KEY) ?: DEFAULT_STORE_ID,
            terminalId = read(db, TERMINAL_ID_KEY) ?: error("terminal id was not persisted"),
        )''',
'''        putIfMissing(db, TERMINAL_ID_KEY, "TERMINAL-${UUID.randomUUID().toString().uppercase(Locale.ROOT)}")
        putIfMissing(db, GENERATION_KEY, "1")
        return SalesJournalIdentity(
            storeId = read(db, STORE_ID_KEY) ?: DEFAULT_STORE_ID,
            terminalId = read(db, TERMINAL_ID_KEY) ?: error("terminal id was not persisted"),
            generation = read(db, GENERATION_KEY)?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L,
        )''')
replace_once(p,
'''    fun update(db: SQLiteDatabase, storeId: String, terminalId: String) {
        ensureSettingsTable(db)
        put(db, STORE_ID_KEY, normalize(storeId, "storeId"))
        put(db, TERMINAL_ID_KEY, normalize(terminalId, "terminalId"))
    }''',
'''    fun update(db: SQLiteDatabase, storeId: String, terminalId: String) {
        val generation = resolve(db).generation
        updateForRestore(db, storeId, terminalId, generation)
    }

    fun updateForRestore(db: SQLiteDatabase, storeId: String, terminalId: String, generation: Long) {
        require(generation >= 1L) { "generation must be >= 1" }
        ensureSettingsTable(db)
        put(db, STORE_ID_KEY, normalize(storeId, "storeId"))
        put(db, TERMINAL_ID_KEY, normalize(terminalId, "terminalId"))
        put(db, GENERATION_KEY, generation.toString())
    }''')

# BKP-004 preflight remains fail-closed except explicit BKP-005 spare migration.
p = "app/src/main/java/jp/co/tenposinfo/register/RestorePreflightV136.kt"
replace_once(p,
'''    val backupDrive: RestoreDriveDestinationV136?,
    val currentDrive: RestoreDriveDestinationV136,
)''',
'''    val backupDrive: RestoreDriveDestinationV136?,
    val currentDrive: RestoreDriveDestinationV136,
    val allowSpareTerminalMigration: Boolean = false,
)''')
replace_once(p,
'''            content == null -> migrate(
                "BKP_SCHEMA",
                "バックアップschema",
                "${input.envelopeFormat} / DB-only旧形式。設定・画像は旧バックアップ範囲として移行します",
            )''',
'''            content == null && input.allowSpareTerminalMigration -> block(
                "BKP_SCHEMA",
                "バックアップschema",
                "予備端末移行には店舗設定を含むBKP-003バックアップが必要です",
            )
            content == null -> migrate(
                "BKP_SCHEMA",
                "バックアップschema",
                "${input.envelopeFormat} / DB-only旧形式。設定・画像は旧バックアップ範囲として移行します",
            )''')
replace_once(p,
'''    private fun storeIdentity(input: RestorePreflightInputsV136): RestorePreflightCheckV136 =
        if (input.backupStoreId == input.currentStoreId) {
            pass("STORE_ID", "storeId", input.currentStoreId)
        } else {
            block(
                "STORE_ID",
                "storeId",
                "店舗が一致しません: backup=${input.backupStoreId}, current=${input.currentStoreId}",
            )
        }

    private fun terminalIdentity(input: RestorePreflightInputsV136): RestorePreflightCheckV136 =
        if (input.backupTerminalId == input.currentTerminalId) {
            pass("TERMINAL_ID", "terminalId", input.currentTerminalId)
        } else {
            block(
                "TERMINAL_ID",
                "terminalId",
                "端末が一致しません: backup=${input.backupTerminalId}, current=${input.currentTerminalId}。予備端末移行はBKP-005の端末ID切替を選択してください",
            )
        }
''',
'''    private fun storeIdentity(input: RestorePreflightInputsV136): RestorePreflightCheckV136 = when {
        input.backupStoreId == input.currentStoreId -> pass("STORE_ID", "storeId", input.currentStoreId)
        input.allowSpareTerminalMigration && input.currentStoreId == "STORE-UNCONFIGURED" -> migrate(
            "STORE_ID",
            "storeId",
            "未設定の予備端末をbackup=${input.backupStoreId}へ移行します。店舗名再入力と責任者確認が必要です",
        )
        else -> block(
            "STORE_ID",
            "storeId",
            "店舗が一致しません: backup=${input.backupStoreId}, current=${input.currentStoreId}",
        )
    }

    private fun terminalIdentity(input: RestorePreflightInputsV136): RestorePreflightCheckV136 = when {
        input.allowSpareTerminalMigration -> migrate(
            "TERMINAL_ID",
            "terminalId",
            "BKP-005予備端末移行として新terminalId/generationを発行します。backup=${input.backupTerminalId}, current=${input.currentTerminalId}",
        )
        input.backupTerminalId == input.currentTerminalId -> pass("TERMINAL_ID", "terminalId", input.currentTerminalId)
        else -> block(
            "TERMINAL_ID",
            "terminalId",
            "端末が一致しません: backup=${input.backupTerminalId}, current=${input.currentTerminalId}。予備端末移行はBKP-005の端末ID切替を選択してください",
        )
    }
''')

# Restore manager: explicit mode, frozen sequence floor and migration plan.
p = "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt"
replace_once(p,
'''data class RestoreStageResult(
    val backup: BackupVerification,
    val actorName: String,
    val stagedAt: Long,
)''',
'''data class RestoreStageResult(
    val backup: BackupVerification,
    val actorName: String,
    val stagedAt: Long,
    val migrationPlan: RestoreTerminalMigrationPlanV136? = null,
)''')
replace_once(p,
'''    fun preflightRestore(fileName: String): RestorePreflightReportV136 {
        val safeName = BackupFilePolicy.requireSafe(fileName)''',
'''    fun preflightRestore(fileName: String): RestorePreflightReportV136 =
        preflightRestore(fileName, RestoreTerminalModeV136.SAME_TERMINAL)

    fun preflightRestore(fileName: String, mode: RestoreTerminalModeV136): RestorePreflightReportV136 {
        val safeName = BackupFilePolicy.requireSafe(fileName)''')
replace_once(p,
'''                    backupDrive = contentManifest?.driveDestination,
                    currentDrive = BackupContentBundleV136.currentDriveDestination(appContext),
                ),''',
'''                    backupDrive = contentManifest?.driveDestination,
                    currentDrive = BackupContentBundleV136.currentDriveDestination(appContext),
                    allowSpareTerminalMigration = mode == RestoreTerminalModeV136.SPARE_TERMINAL,
                ),''')
replace_once(p,
'''    fun stageRestore(fileName: String, managerPin: String): RestoreStageResult {
        val actorName = AdminSettingsStore(appContext).use { it.managerNameForPin(managerPin) } ?: error("責任者PINが違います")''',
'''    fun stageRestore(fileName: String, managerPin: String): RestoreStageResult =
        stageRestore(fileName, managerPin, RestoreTerminalMigrationRequestV136.sameTerminal())

    fun stageRestore(
        fileName: String,
        managerPin: String,
        migrationRequest: RestoreTerminalMigrationRequestV136,
    ): RestoreStageResult {
        val actorName = AdminSettingsStore(appContext).use { it.managerNameForPin(managerPin) } ?: error("責任者PINが違います")''')
replace_once(p,
'''        val preflight = preflightRestore(fileName)
        require(preflight.mayRestore) { preflight.blockingReasons.joinToString("\\n") }''',
'''        val preflight = if (migrationRequest.mode == RestoreTerminalModeV136.SAME_TERMINAL) {
            preflightRestore(fileName)
        } else {
            preflightRestore(fileName, migrationRequest.mode)
        }
        require(preflight.mayRestore) { preflight.blockingReasons.joinToString("\\n") }''')
replace_once(p,
'''            val extracted = extractDatabase(innerArchive, extractionDir)
            val extractedContent = File(extractionDir, "restore-content-v136")
            val hasContent = BackupContentBundleV136.extractAndVerify(innerArchive, extractedContent)
            val pendingContent = File(restoreDir, "pending-content-v136")''',
'''            val extracted = extractDatabase(innerArchive, extractionDir)
            val extractedContent = File(extractionDir, "restore-content-v136")
            val hasContent = BackupContentBundleV136.extractAndVerify(innerArchive, extractedContent)
            val currentIdentityAndMax = RegisterDatabase(appContext).use { helper ->
                val db = helper.writableDatabase
                SalesJournalIdentityStore.resolve(db) to SaleSequenceSafetyV136.maxKnownSaleId(db)
            }
            val backupIdentity = readJournalIdentity(extracted)
            val backupMaxSaleId = SQLiteDatabase.openDatabase(
                extracted.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).let { db ->
                try { SaleSequenceSafetyV136.maxKnownSaleId(db) } finally { db.close() }
            }
            val migrationPlan = RestoreTerminalMigrationPolicyV136.plan(
                request = migrationRequest,
                backupStoreName = RestoreTerminalMigrationPolicyV136.backupStoreName(extractedContent),
                backupIdentity = backupIdentity,
                currentIdentity = currentIdentityAndMax.first,
                currentKnownMaxSaleId = currentIdentityAndMax.second,
                backupKnownMaxSaleId = backupMaxSaleId,
            )
            val pendingContent = File(restoreDir, "pending-content-v136")''')
replace_once(p,
'''                "actor_name" to actorName,
                "staged_at" to stagedAt.toString(),
            ))
            recordAudit("DATA_RESTORE_STAGED", "${verification.fileName} / 次回起動時に復元", actorName)
            return RestoreStageResult(verification, actorName, stagedAt)''',
'''                "actor_name" to actorName,
                "staged_at" to stagedAt.toString(),
                "restore_mode" to migrationPlan.mode.name,
                "target_store_id" to migrationPlan.storeId,
                "source_terminal_id" to migrationPlan.sourceTerminalId,
                "target_terminal_id" to migrationPlan.targetTerminalId,
                "source_generation" to migrationPlan.sourceGeneration.toString(),
                "target_generation" to migrationPlan.targetGeneration.toString(),
                "sale_sequence_floor" to migrationPlan.saleSequenceFloor.toString(),
                "remote_ack_max_sale_id" to migrationPlan.remoteAckMaxSaleId.toString(),
            ))
            recordAudit("DATA_RESTORE_STAGED", "${verification.fileName} / ${migrationPlan.displaySummary()} / 次回起動時に復元", actorName)
            return RestoreStageResult(verification, actorName, stagedAt, migrationPlan)''')
replace_once(p,
'''            SalesJournalIdentity(
                storeId = read("sales_journal_store_id")?.takeIf(String::isNotBlank) ?: "<missing-storeId>",
                terminalId = read("sales_journal_terminal_id")?.takeIf(String::isNotBlank) ?: "<missing-terminalId>",
            )''',
'''            SalesJournalIdentity(
                storeId = read("sales_journal_store_id")?.takeIf(String::isNotBlank) ?: "<missing-storeId>",
                terminalId = read("sales_journal_terminal_id")?.takeIf(String::isNotBlank) ?: "<missing-terminalId>",
                generation = read("sales_journal_terminal_generation")?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L,
            )''')

# Reservation coordinator keeps the legacy call path and adds explicit BKP-005 request.
p = "app/src/main/java/jp/co/tenposinfo/register/DatabaseRecoveryIntegrityV116.kt"
replace_once(p,
'''    fun stage(
        context: Context,
        manager: DataProtectionManager,
        fileName: String,
        managerPin: String,
    ): RestoreStageResult {
        val appContext = context.applicationContext
        PendingRestoreWriteFenceV116.install(appContext)
        return try {
            manager.stageRestore(fileName, managerPin)
        } catch (error: Throwable) {
            runCatching { PendingRestoreWriteFenceV116.remove(appContext) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }''',
'''    fun stage(
        context: Context,
        manager: DataProtectionManager,
        fileName: String,
        managerPin: String,
    ): RestoreStageResult = stage(
        context,
        manager,
        fileName,
        managerPin,
        RestoreTerminalMigrationRequestV136.sameTerminal(),
    )

    fun stage(
        context: Context,
        manager: DataProtectionManager,
        fileName: String,
        managerPin: String,
        migrationRequest: RestoreTerminalMigrationRequestV136,
    ): RestoreStageResult {
        val appContext = context.applicationContext
        PendingRestoreWriteFenceV116.install(appContext)
        return try {
            if (migrationRequest.mode == RestoreTerminalModeV136.SAME_TERMINAL) {
                manager.stageRestore(fileName, managerPin)
            } else {
                manager.stageRestore(fileName, managerPin, migrationRequest)
            }
        } catch (error: Throwable) {
            runCatching { PendingRestoreWriteFenceV116.remove(appContext) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }''')

# Apply identity/generation/floor inside the existing rollback boundary.
p = "app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt"
replace_once(p,
'''            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)''',
'''            // BKP-005: identity/generationと採番floorをrollback境界内で確定する。
            RestoreTerminalMigrationV136.apply(database, plan)

            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)''')
replace_once(p,
'''                    " / BKP-003=${contentMode}",''',
'''                    " / BKP-003=${contentMode}" +
                    " / BKP-005=${plan["restore_mode"].orEmpty()}" +
                    " / terminalId=${plan["target_terminal_id"].orEmpty()}" +
                    " / generation=${plan["target_generation"].orEmpty()}" +
                    " / sale-floor=${plan["sale_sequence_floor"].orEmpty()}",''')
replace_once(p,
'''                    put("detail", "${plan["backup_file"].orEmpty()} / 起動時復元 / v1.16 WAL・migration-safe rollback")''',
'''                    put("detail", "${plan["backup_file"].orEmpty()} / 起動時復元 / v1.16 WAL・migration-safe rollback / BKP-005=${plan["restore_mode"].orEmpty()} / terminalId=${plan["target_terminal_id"].orEmpty()} / generation=${plan["target_generation"].orEmpty()} / sale-floor=${plan["sale_sequence_floor"].orEmpty()}")''')

# Enforce frozen/known maximum immediately before AUTOINCREMENT allocation.
p = "app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt"
replace_once(p,
'''            val businessLink = BusinessSessionSchema.currentOpen(this)
                ?: throw IllegalStateException("営業開始後に会計してください")
            val saleId = insertOrThrow(''',
'''            val businessLink = BusinessSessionSchema.currentOpen(this)
                ?: throw IllegalStateException("営業開始後に会計してください")
            // BKP-005: stale restore/予備端末移行後も既知最大番号以下へ巻き戻さない。
            SaleSequenceSafetyV136.enforceBeforeSale(this)
            val saleId = insertOrThrow(''')

# Restore UI requires an explicit mode and spare-terminal safety confirmations.
p = "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt"
replace_once(p,
'''    var pin by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }''',
'''    var pin by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf<RestoreTerminalModeV136?>(null) }
    var spareStoreName by remember { mutableStateOf("") }
    var spareRemoteMaxSaleId by remember { mutableStateOf("") }
    var oldTerminalStopped by remember { mutableStateOf(false) }
    var backupPassphrase by remember { mutableStateOf("") }''')
old = '''                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val file = selected ?: return@OutlinedButton
                                    runTask {
                                        val preflight = withContext(Dispatchers.IO) { manager.preflightRestore(file) }
                                        preflight.displayText()
                                    }
                                },
                                enabled = !busy && selected != null,
                            ) { Text("検証") }
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    runTask {
                                        val staged = withContext(Dispatchers.IO) {
                                            RestoreReservationCoordinatorV116.stage(appContext, manager, file, pin)
                                        }
                                        pin = ""
                                        "復元予約: ${staged.backup.fileName}。アプリを完全終了して再起動してください。"
                                    }
                                },
                                enabled = !busy && selected != null && pin.length >= 4 && report?.restoreReady == true && !pending.staged,
                                colors = ButtonDefaults.buttonColors(containerColor = DpDanger),
                            ) { Text("次回起動時に復元") }
'''
new = '''                        Spacer(Modifier.height(8.dp))
                        Text("復元方式（必須）", fontWeight = FontWeight.Bold, color = DpNavy)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { restoreMode = RestoreTerminalModeV136.SAME_TERMINAL },
                                enabled = !busy && !pending.staged,
                            ) { Text(if (restoreMode == RestoreTerminalModeV136.SAME_TERMINAL) "✓ 同一端末復旧" else "同一端末復旧") }
                            OutlinedButton(
                                onClick = { restoreMode = RestoreTerminalModeV136.SPARE_TERMINAL },
                                enabled = !busy && !pending.staged,
                            ) { Text(if (restoreMode == RestoreTerminalModeV136.SPARE_TERMINAL) "✓ 予備端末移行" else "予備端末移行") }
                        }
                        if (restoreMode == RestoreTerminalModeV136.SPARE_TERMINAL) {
                            Text(
                                "旧端末を停止し、バックアップ内店舗名とDrive/既存イベントの最大売上番号を確認してください。新terminalId/generationを発行します。",
                                color = DpDanger,
                                fontSize = 12.sp,
                            )
                            OutlinedTextField(
                                spareStoreName,
                                { spareStoreName = it.take(80) },
                                label = { Text("店舗名を再入力") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                spareRemoteMaxSaleId,
                                { spareRemoteMaxSaleId = it.filter(Char::isDigit).take(18) },
                                label = { Text("Drive ACK/既存イベント 最大売上番号（該当なしは0）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = { oldTerminalStopped = !oldTerminalStopped },
                                enabled = !busy && !pending.staged,
                            ) { Text(if (oldTerminalStopped) "✓ 旧端末停止を確認済み" else "旧端末停止を確認する") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val file = selected ?: return@OutlinedButton
                                    val mode = restoreMode ?: return@OutlinedButton
                                    runTask {
                                        val preflight = withContext(Dispatchers.IO) {
                                            if (mode == RestoreTerminalModeV136.SAME_TERMINAL) manager.preflightRestore(file)
                                            else manager.preflightRestore(file, mode)
                                        }
                                        preflight.displayText()
                                    }
                                },
                                enabled = !busy && selected != null && restoreMode != null,
                            ) { Text("検証") }
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    val mode = restoreMode ?: return@Button
                                    val request = RestoreTerminalMigrationRequestV136(
                                        mode = mode,
                                        confirmedStoreName = spareStoreName,
                                        oldTerminalStopped = oldTerminalStopped,
                                        remoteAckMaxSaleId = if (mode == RestoreTerminalModeV136.SPARE_TERMINAL) spareRemoteMaxSaleId.toLongOrNull() else null,
                                    )
                                    runTask {
                                        val staged = withContext(Dispatchers.IO) {
                                            if (mode == RestoreTerminalModeV136.SAME_TERMINAL) {
                                                RestoreReservationCoordinatorV116.stage(appContext, manager, file, pin)
                                            } else {
                                                RestoreReservationCoordinatorV116.stage(appContext, manager, file, pin, request)
                                            }
                                        }
                                        pin = ""
                                        "復元予約: ${staged.backup.fileName} / ${staged.migrationPlan?.displaySummary().orEmpty()}。アプリを完全終了して再起動してください。"
                                    }
                                },
                                enabled = !busy && selected != null && pin.length >= 4 && report?.restoreReady == true && !pending.staged &&
                                    restoreMode != null && (restoreMode != RestoreTerminalModeV136.SPARE_TERMINAL ||
                                    (spareStoreName.isNotBlank() && spareRemoteMaxSaleId.toLongOrNull() != null && oldTerminalStopped)),
                                colors = ButtonDefaults.buttonColors(containerColor = DpDanger),
                            ) { Text("次回起動時に復元") }
'''
replace_once(p, old, new)

print("BKP-005 patch applied")
