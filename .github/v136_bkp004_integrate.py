from pathlib import Path


def replace_one(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


# BackupContentV136: persist a non-secret Drive destination fingerprint inside the encrypted
# content manifest. Older content manifests without these fields remain readable.
path = 'app/src/main/java/jp/co/tenposinfo/register/BackupContentV136.kt'
replace_one(
    path,
    '''        val receiptStampSha256: String?,
        val receiptStampBytes: Long,
    )
''',
    '''        val receiptStampSha256: String?,
        val receiptStampBytes: Long,
        val driveDestination: RestoreDriveDestinationV136? = null,
    )
''',
    'content manifest drive field',
)
replace_one(
    path,
    '''        val manifest = Manifest(
            preferenceSha256 = encodedPreferences.mapValues { (_, bytes) -> sha256(bytes) },
            preferenceBytes = encodedPreferences.mapValues { (_, bytes) -> bytes.size.toLong() },
            receiptStampPresent = stampFile.isFile,
            receiptStampSha256 = stampFile.takeIf(File::isFile)?.let(::sha256),
            receiptStampBytes = stampFile.takeIf(File::isFile)?.length() ?: 0L,
        )
''',
    '''        val manifest = Manifest(
            preferenceSha256 = encodedPreferences.mapValues { (_, bytes) -> sha256(bytes) },
            preferenceBytes = encodedPreferences.mapValues { (_, bytes) -> bytes.size.toLong() },
            receiptStampPresent = stampFile.isFile,
            receiptStampSha256 = stampFile.takeIf(File::isFile)?.let(::sha256),
            receiptStampBytes = stampFile.takeIf(File::isFile)?.length() ?: 0L,
            driveDestination = currentDriveDestination(appContext),
        )
''',
    'write drive descriptor',
)
replace_one(
    path,
    '''    fun hasStagedContent(root: File): Boolean = File(root, "content/manifest.properties").isFile

    /**
''',
    '''    fun hasStagedContent(root: File): Boolean = File(root, "content/manifest.properties").isFile

    fun readVerifiedManifest(root: File): Manifest = validateSnapshotDirectory(root)

    /**
''',
    'expose verified content manifest',
)
# The same Manifest construction exists in rollback capture; replace the remaining occurrence.
replace_one(
    path,
    '''        val manifest = Manifest(
            preferenceSha256 = encoded.mapValues { sha256(it.value) },
            preferenceBytes = encoded.mapValues { it.value.size.toLong() },
            receiptStampPresent = stampFile.isFile,
            receiptStampSha256 = stampFile.takeIf(File::isFile)?.let(::sha256),
            receiptStampBytes = stampFile.takeIf(File::isFile)?.length() ?: 0L,
        )
''',
    '''        val manifest = Manifest(
            preferenceSha256 = encoded.mapValues { sha256(it.value) },
            preferenceBytes = encoded.mapValues { it.value.size.toLong() },
            receiptStampPresent = stampFile.isFile,
            receiptStampSha256 = stampFile.takeIf(File::isFile)?.let(::sha256),
            receiptStampBytes = stampFile.takeIf(File::isFile)?.length() ?: 0L,
            driveDestination = currentDriveDestination(context),
        )
''',
    'rollback drive descriptor',
)
replace_one(
    path,
    '''        appendLine("receipt_stamp.present=${manifest.receiptStampPresent}")
        appendLine("receipt_stamp.bytes=${manifest.receiptStampBytes}")
        manifest.receiptStampSha256?.let { appendLine("receipt_stamp.sha256=$it") }
    }.toByteArray(Charsets.UTF_8)
''',
    '''        appendLine("receipt_stamp.present=${manifest.receiptStampPresent}")
        appendLine("receipt_stamp.bytes=${manifest.receiptStampBytes}")
        manifest.receiptStampSha256?.let { appendLine("receipt_stamp.sha256=$it") }
        manifest.driveDestination?.let { drive ->
            appendLine("drive.connected=${drive.connected}")
            appendLine("drive.folder_name_b64=${encodeText(drive.folderName)}")
            drive.accountKey?.let { appendLine("drive.account_key=$it") }
        }
    }.toByteArray(Charsets.UTF_8)
''',
    'encode drive descriptor',
)
replace_one(
    path,
    '''        if (present) {
            require(stampBytes in 1..MAX_STAMP_BYTES && stampHash != null) { "画像スタンプmanifestが不正です" }
        } else {
            require(stampBytes == 0L && stampHash == null) { "画像スタンプmanifestが不正です" }
        }
        val expectedKeys = buildSet {
''',
    '''        if (present) {
            require(stampBytes in 1..MAX_STAMP_BYTES && stampHash != null) { "画像スタンプmanifestが不正です" }
        } else {
            require(stampBytes == 0L && stampHash == null) { "画像スタンプmanifestが不正です" }
        }
        val drive = if ("drive.connected" in properties) {
            val connected = properties.getValue("drive.connected").toBooleanStrict()
            val folderName = decodeText(properties.getValue("drive.folder_name_b64"))
            require(folderName.isNotBlank() && folderName.length <= 100) { "Drive同期フォルダ名が不正です" }
            val accountKey = properties["drive.account_key"]?.trim()?.takeIf(String::isNotEmpty)
            require(!connected || accountKey != null) { "Drive接続先識別子がありません" }
            RestoreDriveDestinationV136(
                descriptorCaptured = true,
                connected = connected,
                accountKey = accountKey,
                folderName = folderName,
            )
        } else {
            null
        }
        val expectedKeys = buildSet {
''',
    'decode drive descriptor',
)
replace_one(
    path,
    '''            add("receipt_stamp.bytes")
            if (present) add("receipt_stamp.sha256")
        }
        require(properties.keys == expectedKeys) { "content manifestに未知の項目があります" }
        return Manifest(hashes, sizes, present, stampHash, stampBytes)
    }
''',
    '''            add("receipt_stamp.bytes")
            if (present) add("receipt_stamp.sha256")
            if (drive != null) {
                add("drive.connected")
                add("drive.folder_name_b64")
                if (drive.accountKey != null) add("drive.account_key")
            }
        }
        require(properties.keys == expectedKeys) { "content manifestに未知の項目があります" }
        return Manifest(hashes, sizes, present, stampHash, stampBytes, drive)
    }
''',
    'validate drive descriptor keys',
)
replace_one(
    path,
    '''    private fun preferenceEntry(name: String): String {
''',
    '''    fun currentDriveDestination(context: Context): RestoreDriveDestinationV136 {
        val appContext = context.applicationContext
        val account = GoogleDriveAccountStore(appContext).load()
        val email = account.email?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        val accountKey = account.permissionId?.trim()?.takeIf(String::isNotEmpty)
            ?: email?.let { "email-sha256:${sha256(it.toByteArray(Charsets.UTF_8))}" }
        return RestoreDriveDestinationV136(
            descriptorCaptured = true,
            connected = email != null,
            accountKey = accountKey,
            folderName = DriveSyncSettingsStore.load(appContext).folderName,
        )
    }

    private fun encodeText(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

    private fun preferenceEntry(name: String): String {
''',
    'drive descriptor runtime helpers',
)


# DataProtectionManager: build and enforce the BKP-004 preflight from verified encrypted backup data.
path = 'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt'
replace_one(
    path,
    '''    fun verifyBackup(fileName: String): BackupVerification {
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(archive.isFile) { "バックアップが見つかりません" }
        return verifyArchive(archive, archive.name)
    }

    private fun verifyArchive(archive: File, displayName: String): BackupVerification {
''',
    '''    fun verifyBackup(fileName: String): BackupVerification {
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(archive.isFile) { "バックアップが見つかりません" }
        return verifyArchive(archive, archive.name)
    }

    fun preflightRestore(fileName: String): RestorePreflightReportV136 {
        val safeName = BackupFilePolicy.requireSafe(fileName)
        val archive = File(backupDir, safeName)
        val verification = verifyBackup(safeName)
        val currentDb = appContext.getDatabasePath(DATABASE_NAME)
        val requiredFree = archive.length() * 3L + (if (currentDb.isFile) currentDb.length() else 0L) + 16L * 1024L * 1024L
        val availableFree = appContext.cacheDir.usableSpace
        val extractionDir = File(appContext.cacheDir, "preflight-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val innerArchive = File(extractionDir, "preflight-inner.tgbak")
            val decrypted = BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            require(decrypted.databaseSha256 == verification.manifest.databaseSha256) {
                "復元前検証で暗号化manifestのDB識別子が一致しません"
            }
            val backupDatabase = extractDatabase(innerArchive, extractionDir)
            val contentRoot = File(extractionDir, "preflight-content-v136")
            val hasContent = BackupContentBundleV136.extractAndVerify(innerArchive, contentRoot)
            val contentManifest = if (hasContent) BackupContentBundleV136.readVerifiedManifest(contentRoot) else null
            val backupIdentity = readJournalIdentity(backupDatabase)
            val currentState = RegisterDatabase(appContext).use { helper ->
                val db = helper.writableDatabase
                val identity = SalesJournalIdentityStore.resolve(db)
                val schema = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                identity to schema
            }
            val hashVerified = sha256(backupDatabase) == verification.manifest.databaseSha256
            val decision = RestorePreflightPolicyV136.evaluate(
                RestorePreflightInputsV136(
                    envelopeFormat = verification.manifest.format,
                    contentFormat = if (hasContent) BackupContentBundleV136.FORMAT else null,
                    backupAppVersion = verification.manifest.appVersion,
                    currentAppVersion = BuildConfig.VERSION_NAME,
                    backupDatabaseSchema = verification.manifest.databaseUserVersion,
                    currentDatabaseSchema = currentState.second,
                    backupStoreId = backupIdentity.storeId,
                    currentStoreId = currentState.first.storeId,
                    backupTerminalId = backupIdentity.terminalId,
                    currentTerminalId = currentState.first.terminalId,
                    hashVerified = hashVerified,
                    requiredFreeBytes = requiredFree,
                    availableFreeBytes = availableFree,
                    backupDrive = contentManifest?.driveDestination,
                    currentDrive = BackupContentBundleV136.currentDriveDestination(appContext),
                ),
            )
            return RestorePreflightReportV136(
                verification = verification,
                decision = decision,
                backupStoreId = backupIdentity.storeId,
                backupTerminalId = backupIdentity.terminalId,
                currentStoreId = currentState.first.storeId,
                currentTerminalId = currentState.first.terminalId,
            )
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    private fun verifyArchive(archive: File, displayName: String): BackupVerification {
''',
    'insert preflight restore',
)
replace_one(
    path,
    '''        val verification = verifyBackup(fileName)
        val extractionDir = File(appContext.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
''',
    '''        val preflight = preflightRestore(fileName)
        require(preflight.mayRestore) { preflight.blockingReasons.joinToString("\\n") }
        val verification = preflight.verification
        val extractionDir = File(appContext.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
''',
    'enforce preflight before stage',
)
replace_one(
    path,
    '''    private fun ensureSchemas() {
''',
    '''    private fun readJournalIdentity(databaseFile: File): SalesJournalIdentity {
        val database = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val hasSettings = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='sync_runtime_settings' LIMIT 1",
                null,
            ).use { cursor -> cursor.moveToFirst() }
            if (!hasSettings) return SalesJournalIdentity("<missing-storeId>", "<missing-terminalId>")
            fun read(key: String): String? = database.rawQuery(
                "SELECT setting_value FROM sync_runtime_settings WHERE setting_key = ?",
                arrayOf(key),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            SalesJournalIdentity(
                storeId = read("sales_journal_store_id")?.takeIf(String::isNotBlank) ?: "<missing-storeId>",
                terminalId = read("sales_journal_terminal_id")?.takeIf(String::isNotBlank) ?: "<missing-terminalId>",
            )
        } finally {
            database.close()
        }
    }

    private fun ensureSchemas() {
''',
    'read backup identity',
)


# UI verification uses the same detailed preflight object as restore staging.
path = 'app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt'
replace_one(
    path,
    '''                                    runTask {
                                        val verified = withContext(Dispatchers.IO) { manager.verifyBackup(file) }
                                        "検証成功: ${verified.fileName} / SHA-256 ${verified.manifest.databaseSha256.take(12)}…"
                                    }
''',
    '''                                    runTask {
                                        val preflight = withContext(Dispatchers.IO) { manager.preflightRestore(file) }
                                        preflight.displayText()
                                    }
''',
    'restore preflight UI',
)


# Implementation evidence kept in the repo, with real-device-only checks explicitly deferred.
Path('docs/V1.36_BKP_004_RESTORE_PREFLIGHT.md').write_text('''# v1.36 BKP-004 復元前検証

正式仕様 `REGISTER（仮）_Androidレジ_詳細仕様書_v2.5.docx` の BKP-004 を正本とする。

## 検証項目

復元予約前に以下を同一の `RestorePreflightPolicyV136` で判定する。

1. アプリ版
2. 暗号化バックアップ/content schema
3. SQLite `user_version`
4. `storeId`
5. `terminalId`
6. SHA-256 / 暗号化payload整合性
7. 復号・復元に必要な空き容量
8. Google Drive 接続先

## 不一致時の扱い

- バックアップが現在アプリより新しい: **拒否**。アプリ更新を要求する。
- 古いアプリ版/古いDB schema: **移行**。既存の起動時DB migration境界へ渡す。
- `storeId` 不一致: **拒否**。別店舗データの誤復元を防止する。
- `terminalId` 不一致: **拒否**。BKP-005の「同一端末復旧/予備端末移行」選択前に端末IDを暗黙変更しない。
- hash不一致/暗号化payload不整合: **拒否**。
- 空き容量不足: **拒否**。
- Driveアカウントがバックアップ作成時と異なる: **拒否**。別アカウントへの誤送信を防止する。
- バックアップ作成時Drive接続あり・現在未接続: **移行**。復元後の再認証を要求する。
- Drive同期フォルダ差異: **移行**。BKP-003で保存済みのバックアップ側設定へ戻す。
- BKP-004導入前の旧content manifestでDrive fingerprintが無い: **移行**。復元後の接続先再確認を要求する。

## Drive fingerprint

Google OAuth token/refresh tokenはバックアップしない。BKP-003の暗号化content manifest内にのみ、非秘密の接続先fingerprintを追加する。

- 接続有無
- `permissionId`（取得済みの場合）
- `permissionId` が無い場合は正規化emailのSHA-256
- 同期フォルダ名

端末のOAuth認証状態そのものは復元しない。

## 強制経路

画面の「検証」ボタンは `preflightRestore()` の全結果を理由付きで表示する。`stageRestore()` 側でも同じpreflightを再実行し、BLOCKが1件でもあれば復元予約を拒否するため、UIを迂回しても不一致復元はできない。

## BKP-005との境界

BKP-004では `terminalId` 不一致を理由付きで拒否する。予備端末移行時の新terminalId/generationと会計番号重複防止は次要件BKP-005で実装する。

## 実機未確認

- 同一端末の実バックアップで全8項目がPASSすること
- 古いschemaバックアップの実migration
- Drive未接続/別アカウント/フォルダ差異の実UI表示
- 実ストレージ逼迫時の空き容量拒否
- 破損バックアップを実端末SAFから選択した際の理由表示
''', encoding='utf-8')
