from pathlib import Path

ROOT = Path('.')

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'PATCH_MISS: {label}')
    if text.count(old) != 1:
        raise SystemExit(f'PATCH_AMBIGUOUS: {label} count={text.count(old)}')
    return text.replace(old, new, 1)

# ---- DataProtection.kt -----------------------------------------------------
p = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt'
s = p.read_text()

s = replace_once(s,
'''                val manifest = readManifest(file)
                BackupRecord(file.name, file.length(), manifest.createdAt, true, manifest.appVersion, manifest.databaseUserVersion)''',
'''                val manifest = readPackageManifest(file)
                BackupRecord(file.name, file.length(), manifest.createdAt, true, manifest.appVersion, manifest.databaseUserVersion)''',
'listBackups secure manifest')

s = replace_once(s,
'''        val temporaryArchive = File(backupDir, "$fileName.tmp")
        val finalArchive = File(backupDir, fileName)
        try {''',
'''        val temporaryArchive = File(backupDir, "$fileName.tmp")
        val finalArchive = File(backupDir, fileName)
        val privatePlainBundle = File(stagingDir, "legacy-inner.tgbak")
        try {''',
'create backup private bundle var')

s = replace_once(s,
'''            writeArchive(temporaryArchive, stagedDatabase, manifest)
            require(temporaryArchive.length() > 0L) { "バックアップアーカイブを作成できませんでした" }
            atomicReplace(temporaryArchive, finalArchive)
            recordAudit("DATA_BACKUP_CREATED", "${finalArchive.name} / ${manifest.databaseSha256}", actorName)''',
'''            // V1 bundle exists only under app-private cache and is never exported as plaintext.
            writeArchive(privatePlainBundle, stagedDatabase, manifest)
            BackupEnvelopeV136.createLocalEnvelope(
                context = appContext,
                plainBundle = privatePlainBundle,
                metadata = manifest,
                databaseBytes = stagedDatabase.length(),
                target = temporaryArchive,
            )
            require(temporaryArchive.length() > 0L) { "暗号化バックアップアーカイブを作成できませんでした" }
            verifyArchive(temporaryArchive, fileName)
            atomicReplace(temporaryArchive, finalArchive)
            recordAudit("DATA_BACKUP_CREATED", "${finalArchive.name} / AES-256-GCM / ${manifest.databaseSha256}", actorName)''',
'create encrypted envelope')

old_transfer = '''    fun copyVerifiedBackup(fileName: String, output: OutputStream): BackupExportResult {
        val verification = verifyBackup(fileName)
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        val written = archive.inputStream().buffered().use { input ->
            BackupTransferPolicy.copyWithLimit(input, output, MAX_BACKUP_ARCHIVE_BYTES)
        }
        require(written == archive.length()) { "バックアップの外部出力サイズが一致しません" }
        return BackupExportResult(verification.fileName, written, verification.manifest)
    }

    fun exportBackup(fileName: String, output: OutputStream, actorName: String): BackupExportResult {
        val result = copyVerifiedBackup(fileName, output)
        recordAudit("DATA_BACKUP_EXPORTED", "${result.fileName} / ${result.bytesWritten} bytes", actorName)
        return result
    }

    fun importBackup(input: InputStream, actorName: String): BackupRecord {
        val temporary = File(appContext.cacheDir, "backup-import-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().buffered().use { output ->
                val copied = BackupTransferPolicy.copyWithLimit(input, output, MAX_BACKUP_ARCHIVE_BYTES)
                require(copied > 0L) { "取込ファイルが空です" }
            }
            val verification = verifyArchive(temporary, "external-import.tgbak")
            val targetName = BackupImportNamePolicy.canonical(verification.manifest)
            val target = File(backupDir, targetName)
            if (target.exists()) {
                val existing = verifyArchive(target, target.name)
                require(existing.manifest == verification.manifest) { "同名の異なるバックアップが存在します" }
                recordAudit("DATA_BACKUP_IMPORTED", "$targetName / 既存バックアップと同一", actorName)
                return BackupRecord(target.name, target.length(), existing.manifest.createdAt, true, existing.manifest.appVersion, existing.manifest.databaseUserVersion)
            }
            atomicReplace(temporary, target)
            val committed = verifyBackup(target.name)
            recordAudit("DATA_BACKUP_IMPORTED", "${target.name} / ${committed.manifest.databaseSha256}", actorName)
            return BackupRecord(target.name, target.length(), committed.manifest.createdAt, true, committed.manifest.appVersion, committed.manifest.databaseUserVersion)
        } finally {
            temporary.delete()
        }
    }
'''
new_transfer = '''    /**
     * Export is always portable. The local archive remains device-Keystore protected while the
     * exported copy receives a second DEK wrap derived from the administrator passphrase.
     */
    fun exportBackup(fileName: String, output: OutputStream, actorName: String, passphrase: CharArray): BackupExportResult {
        val verification = verifyBackup(fileName)
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        val counting = BackupCountingOutputStream(output)
        val portableManifest = try {
            BackupEnvelopeV136.exportPortable(appContext, archive, passphrase, counting)
        } finally {
            passphrase.fill('\\u0000')
        }
        require(portableManifest.databaseSha256 == verification.manifest.databaseSha256) { "外部保存前後のDB識別子が一致しません" }
        val result = BackupExportResult(verification.fileName, counting.count, portableManifest)
        recordAudit("DATA_BACKUP_EXPORTED", "${result.fileName} / portable AES-256-GCM / ${result.bytesWritten} bytes", actorName)
        return result
    }

    fun importBackup(input: InputStream, actorName: String, passphrase: CharArray): BackupRecord {
        val temporary = File(appContext.cacheDir, "backup-import-${UUID.randomUUID()}.tmp")
        try {
            try {
                BackupEnvelopeV136.importPortable(appContext, input, passphrase, temporary)
            } finally {
                passphrase.fill('\\u0000')
            }
            val verification = verifyArchive(temporary, "external-import.tgbak")
            val targetName = BackupImportNamePolicy.canonical(verification.manifest)
            val target = File(backupDir, targetName)
            if (target.exists()) {
                val existing = verifyArchive(target, target.name)
                require(existing.manifest.databaseSha256 == verification.manifest.databaseSha256 &&
                    existing.manifest.createdAt == verification.manifest.createdAt) { "同名の異なるバックアップが存在します" }
                recordAudit("DATA_BACKUP_IMPORTED", "$targetName / portable検証済み・既存バックアップと同一", actorName)
                return BackupRecord(target.name, target.length(), existing.manifest.createdAt, true, existing.manifest.appVersion, existing.manifest.databaseUserVersion)
            }
            atomicReplace(temporary, target)
            val committed = verifyBackup(target.name)
            recordAudit("DATA_BACKUP_IMPORTED", "${target.name} / portable復号検証済み / ${committed.manifest.databaseSha256}", actorName)
            return BackupRecord(target.name, target.length(), committed.manifest.createdAt, true, committed.manifest.appVersion, committed.manifest.databaseUserVersion)
        } finally {
            temporary.delete()
        }
    }
'''
s = replace_once(s, old_transfer, new_transfer, 'portable transfer API')

old_verify = '''    private fun verifyArchive(archive: File, displayName: String): BackupVerification {
        require(archive.isFile && archive.length() in 1..MAX_BACKUP_ARCHIVE_BYTES) { "バックアップアーカイブのサイズが不正です" }
        val extractionDir = File(appContext.cacheDir, "verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = readManifest(archive)
            val database = extractDatabase(archive, extractionDir)
            require(database.length() in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
            require(sha256(database) == manifest.databaseSha256) { "バックアップDBのSHA-256が一致しません" }
            val currentUserVersion = RegisterDatabase(appContext).use { helper ->
                helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            require(manifest.databaseUserVersion <= currentUserVersion) { "このアプリより新しいDB版のバックアップは復元できません" }
            val report = inspectDatabaseFile(database)
            require(report.healthy) { "バックアップDBの整合性検査に失敗しました" }
            return BackupVerification(displayName, manifest, database.length(), report)
        } finally {
            extractionDir.deleteRecursively()
        }
    }
'''
new_verify = '''    private fun verifyArchive(archive: File, displayName: String): BackupVerification {
        require(archive.isFile && archive.length() in 1..MAX_BACKUP_ARCHIVE_BYTES) { "バックアップアーカイブのサイズが不正です" }
        require(BackupEnvelopeV136.isSecureEnvelope(archive)) {
            "旧式の平文バックアップは安全要件を満たさないため復元できません。現行版で新しいバックアップを作成してください"
        }
        val currentDb = appContext.getDatabasePath(DATABASE_NAME)
        val minimumFree = archive.length() * 3L + (if (currentDb.isFile) currentDb.length() else 0L) + 16L * 1024L * 1024L
        require(appContext.cacheDir.usableSpace >= minimumFree) { "復元前検証用の空き容量が不足しています" }
        val extractionDir = File(appContext.cacheDir, "verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val outerManifest = BackupEnvelopeV136.readBackupManifest(archive)
            val innerArchive = File(extractionDir, "decrypted-inner.tgbak")
            val decryptedManifest = BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            require(decryptedManifest.databaseSha256 == outerManifest.databaseSha256) { "暗号化manifestのDB識別子が一致しません" }
            val legacyManifest = readManifest(innerArchive)
            require(legacyManifest.databaseSha256 == outerManifest.databaseSha256 &&
                legacyManifest.databaseUserVersion == outerManifest.databaseUserVersion &&
                legacyManifest.tableCounts == outerManifest.tableCounts) { "暗号化manifestとDB bundleの内容が一致しません" }
            val database = extractDatabase(innerArchive, extractionDir)
            require(database.length() in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
            require(sha256(database) == outerManifest.databaseSha256) { "バックアップDBのSHA-256が一致しません" }
            val currentUserVersion = RegisterDatabase(appContext).use { helper ->
                helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            require(outerManifest.databaseUserVersion <= currentUserVersion) { "このアプリより新しいDB版のバックアップは復元できません" }
            val report = inspectDatabaseFile(database)
            require(report.healthy) { "バックアップDBの整合性検査に失敗しました" }
            require(report.tableCounts == outerManifest.tableCounts) { "バックアップ件数precheckが一致しません" }
            return BackupVerification(displayName, outerManifest, database.length(), report)
        } finally {
            extractionDir.deleteRecursively()
        }
    }
'''
s = replace_once(s, old_verify, new_verify, 'secure verify')

s = replace_once(s,
'''            val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
            val extracted = extractDatabase(archive, extractionDir)
            val pendingTmp = File(restoreDir, "pending-register.db.tmp")''',
'''            val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
            require(BackupEnvelopeV136.isSecureEnvelope(archive)) { "暗号化されていないバックアップは復元できません" }
            val innerArchive = File(extractionDir, "restore-inner.tgbak")
            BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            val extracted = extractDatabase(innerArchive, extractionDir)
            val pendingTmp = File(restoreDir, "pending-register.db.tmp")''',
'stage decrypt')

s = replace_once(s,
'''    private fun readManifest(archive: File): BackupManifest = ZipFile(archive).use { zip ->''',
'''    private fun readPackageManifest(archive: File): BackupManifest {
        require(BackupEnvelopeV136.isSecureEnvelope(archive)) { "旧式の平文バックアップです" }
        return BackupEnvelopeV136.readBackupManifest(archive)
    }

    private fun readManifest(archive: File): BackupManifest = ZipFile(archive).use { zip ->''',
'package manifest reader')

# counting output stream is deliberately tiny and does not own/close the SAF stream.
s = replace_once(s,
'''object BackupTransferPolicy {
    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {''',
'''private class BackupCountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()
}

object BackupTransferPolicy {
    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {''',
'counting output')

p.write_text(s)

# ---- DataProtectionActivity.kt --------------------------------------------
p = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt'
s = p.read_text()

s = replace_once(s,
'''    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("診断を実行してください") }''',
'''    var pin by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }
    var backupPassphraseConfirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("診断を実行してください") }''',
'passphrase state')

s = replace_once(s,
'''        if (uri != null && fileName != null) {
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.exportBackup(fileName, output, actor)
                    } ?: error("保存先を開けません")
                }
                withContext(Dispatchers.IO) { metadataStore.registerExport(result) }
                "外部保存完了: ${result.fileName} / ${result.bytesWritten} bytes"
            }
        }''',
'''        if (uri != null && fileName != null) {
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.exportBackup(fileName, output, actor, chars)
                    } ?: error("保存先を開けません")
                }
                withContext(Dispatchers.IO) { metadataStore.registerExport(result) }
                "外部保存完了: portable暗号化済み / ${result.fileName} / ${result.bytesWritten} bytes"
            }
        }''',
'export passphrase')

s = replace_once(s,
'''        if (uri != null) {
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val imported = withContext(Dispatchers.IO) {
                    val record = context.contentResolver.openInputStream(uri)?.use { input ->
                        manager.importBackup(input, actor)
                    } ?: error("取込ファイルを開けません")
                    metadataStore.registerManualBackup(manager.verifyBackup(record.fileName))
                    record
                }
                selected = imported.fileName
                "外部バックアップ取込完了: ${imported.fileName}"
            }
        }''',
'''        if (uri != null) {
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val imported = withContext(Dispatchers.IO) {
                    val record = context.contentResolver.openInputStream(uri)?.use { input ->
                        manager.importBackup(input, actor, chars)
                    } ?: error("取込ファイルを開けません")
                    metadataStore.registerManualBackup(manager.verifyBackup(record.fileName))
                    record
                }
                selected = imported.fileName
                "外部バックアップ取込完了: パスフレーズ復号検証済み / ${imported.fileName}"
            }
        }''',
'import passphrase')

old_buttons = '''                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    pendingExport = file
                                    exportLauncher.launch(file)
                                },
                                enabled = !busy && selected != null,
                                colors = ButtonDefaults.buttonColors(containerColor = DpBlue),
                            ) { Text("外部へ保存") }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed")) },
                                enabled = !busy && !pending.staged,
                            ) { Text("外部から取込") }
                            Text(
                                "Google Drive・USB・端末フォルダを選択できます",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
'''
new_buttons = '''                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            backupPassphrase,
                            { backupPassphrase = it },
                            label = { Text("外部バックアップ用パスフレーズ") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            backupPassphraseConfirm,
                            { backupPassphraseConfirm = it },
                            label = { Text("パスフレーズ確認（外部保存時）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            when {
                                backupPassphrase.isEmpty() -> "外部保存・別端末取込にはパスフレーズが必要です。端末内には保存しません。"
                                backupPassphraseConfirm.isNotEmpty() && backupPassphrase != backupPassphraseConfirm -> "確認用パスフレーズが一致しません"
                                else -> "AES-256-GCM / PBKDF2-HMAC-SHA256 210,000回でportable鍵を保護します"
                            },
                            color = if (backupPassphraseConfirm.isNotEmpty() && backupPassphrase != backupPassphraseConfirm) DpDanger else Color.Gray,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    pendingExport = file
                                    exportLauncher.launch(file)
                                },
                                enabled = !busy && selected != null && backupPassphrase.isNotEmpty() && backupPassphrase == backupPassphraseConfirm,
                                colors = ButtonDefaults.buttonColors(containerColor = DpBlue),
                            ) { Text("外部へ暗号化保存") }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed")) },
                                enabled = !busy && !pending.staged && backupPassphrase.isNotEmpty(),
                            ) { Text("外部から取込") }
                            Text(
                                "Google Drive・USB・端末フォルダをSAFで選択できます",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
'''
s = replace_once(s, old_buttons, new_buttons, 'portable UI')
p.write_text(s)

print('PATCH_OK')
