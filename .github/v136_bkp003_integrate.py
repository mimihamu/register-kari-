from pathlib import Path


def replace_one(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))

# Fix the helper to use a local persisted-permission check rather than relying on a domain helper.
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/BackupContentV136.kt',
    '''            if (settings.enabled && (uri == null || !OutboxDeliveryDestinationAccess.hasPersistedWritePermission(context, uri))) {
                store.save(settings.copy(enabled = false))
            }
''',
    '''            if (settings.enabled && (uri == null || !hasPersistedWritePermission(context, uri))) {
                store.save(settings.copy(enabled = false))
            }
''',
    'outbox permission helper',
)
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/BackupContentV136.kt',
    '''            if (settings.enabled && (uri == null || !ExternalBackupDestinationAccess.hasPersistedWritePermission(context, uri))) {
                store.save(settings.copy(enabled = false))
            }
        }
    }

    private fun preferenceEntry(name: String): String {
''',
    '''            if (settings.enabled && (uri == null || !hasPersistedWritePermission(context, uri))) {
                store.save(settings.copy(enabled = false))
            }
        }
    }

    private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }

    private fun preferenceEntry(name: String): String {
''',
    'external permission helper',
)

# DataProtection: verify/stage/write BKP-003 inner content while retaining legacy DB-only restore.
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt',
    '''            val legacyManifest = readManifest(innerArchive)
            require(legacyManifest.databaseSha256 == outerManifest.databaseSha256 &&
                legacyManifest.databaseUserVersion == outerManifest.databaseUserVersion &&
                legacyManifest.tableCounts == outerManifest.tableCounts) { "暗号化manifestとDB bundleの内容が一致しません" }
            val database = extractDatabase(innerArchive, extractionDir)
''',
    '''            val legacyManifest = readManifest(innerArchive)
            require(legacyManifest.databaseSha256 == outerManifest.databaseSha256 &&
                legacyManifest.databaseUserVersion == outerManifest.databaseUserVersion &&
                legacyManifest.tableCounts == outerManifest.tableCounts) { "暗号化manifestとDB bundleの内容が一致しません" }
            // BKP-003 content is inside the encrypted payload. Legacy DB-only bundles remain readable.
            BackupContentBundleV136.extractAndVerify(innerArchive, File(extractionDir, "verified-content-v136"))
            val database = extractDatabase(innerArchive, extractionDir)
''',
    'verify content',
)
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt',
    '''            BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            val extracted = extractDatabase(innerArchive, extractionDir)
            val pendingTmp = File(restoreDir, "pending-register.db.tmp")
            val pending = File(restoreDir, "pending-register.db")
            extracted.copyTo(pendingTmp, overwrite = true)
            require(sha256(pendingTmp) == verification.manifest.databaseSha256) { "復元予約DBのコピー検証に失敗しました" }
            atomicReplace(pendingTmp, pending)
            writeRestorePlan(mapOf(
                "backup_file" to verification.fileName,
                "database_sha256" to verification.manifest.databaseSha256,
                "actor_name" to actorName,
                "staged_at" to stagedAt.toString(),
            ))
''',
    '''            BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            val extracted = extractDatabase(innerArchive, extractionDir)
            val extractedContent = File(extractionDir, "restore-content-v136")
            val hasContent = BackupContentBundleV136.extractAndVerify(innerArchive, extractedContent)
            val pendingContent = File(restoreDir, "pending-content-v136")
            if (hasContent) {
                BackupContentBundleV136.copyVerifiedSnapshot(extractedContent, pendingContent)
            } else {
                BackupContentBundleV136.removePending(pendingContent)
            }
            val pendingTmp = File(restoreDir, "pending-register.db.tmp")
            val pending = File(restoreDir, "pending-register.db")
            extracted.copyTo(pendingTmp, overwrite = true)
            require(sha256(pendingTmp) == verification.manifest.databaseSha256) { "復元予約DBのコピー検証に失敗しました" }
            atomicReplace(pendingTmp, pending)
            writeRestorePlan(mapOf(
                "backup_file" to verification.fileName,
                "database_sha256" to verification.manifest.databaseSha256,
                "content_bundle" to if (hasContent) BackupContentBundleV136.FORMAT else "LEGACY_DB_ONLY",
                "actor_name" to actorName,
                "staged_at" to stagedAt.toString(),
            ))
''',
    'stage content',
)
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt',
    '''        File(restoreDir, "pending-register.db").delete()
        File(restoreDir, "restore-plan.properties").delete()
''',
    '''        File(restoreDir, "pending-register.db").delete()
        BackupContentBundleV136.removePending(File(restoreDir, "pending-content-v136"))
        File(restoreDir, "restore-plan.properties").delete()
''',
    'cancel pending content',
)
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt',
    '''        val names = zip.entries().asSequence().map { it.name }.toSet()
        require(names == setOf(MANIFEST_ENTRY, DATABASE_ENTRY)) { "バックアップ内のファイル構成が不正です" }
        val entry = zip.getEntry(DATABASE_ENTRY) ?: error("バックアップDBがありません")
''',
    '''        val names = zip.entries().asSequence().map { it.name }.toSet()
        val nonContentNames = names.filterNot { it.startsWith("content/") }.toSet()
        require(nonContentNames == setOf(MANIFEST_ENTRY, DATABASE_ENTRY)) { "バックアップ内のファイル構成が不正です" }
        val contentNames = names.filter { it.startsWith("content/") }
        require(contentNames.isEmpty() || BackupContentBundleV136.CONTENT_MANIFEST_ENTRY in contentNames) {
            "バックアップcontent manifestがありません"
        }
        val entry = zip.getEntry(DATABASE_ENTRY) ?: error("バックアップDBがありません")
''',
    'allow verified content entries',
)
replace_one(
    'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt',
    '''            zip.putNextEntry(ZipEntry(DATABASE_ENTRY).apply { time = manifest.createdAt })
            BufferedInputStream(FileInputStream(database)).use { it.copyTo(zip) }
            zip.closeEntry()
        }
''',
    '''            zip.putNextEntry(ZipEntry(DATABASE_ENTRY).apply { time = manifest.createdAt })
            BufferedInputStream(FileInputStream(database)).use { it.copyTo(zip) }
            zip.closeEntry()
            BackupContentBundleV136.writeTo(appContext, zip, manifest.createdAt)
        }
''',
    'write content bundle',
)

# Startup restore: content is required for new bundles, applied only after DB final verification,
# and rolled back together with the DB if any later step fails.
p = Path('app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt')
text = p.read_text()
old = '''        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        val database = context.getDatabasePath(REGISTER_DATABASE_NAME_V086)
'''
new = '''        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        val pendingContent = File(restoreDir, "pending-content-v136")
        val database = context.getDatabasePath(REGISTER_DATABASE_NAME_V086)
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap pending paths anchor mismatch')
text = text.replace(old, new, 1)
old = '''        val actualHash = runCatching { DataProtectionManager.sha256(pending) }.getOrElse { error ->
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBを読み取れません: ${error.message}")
        }
        if (actualHash != expectedHash) {
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBのSHA-256が一致しません")
        }

        database.parentFile?.mkdirs()
'''
new = '''        val actualHash = runCatching { DataProtectionManager.sha256(pending) }.getOrElse { error ->
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBを読み取れません: ${error.message}")
        }
        if (actualHash != expectedHash) {
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBのSHA-256が一致しません")
        }
        val contentMode = plan["content_bundle"] ?: "LEGACY_DB_ONLY"
        if (contentMode == BackupContentBundleV136.FORMAT && !BackupContentBundleV136.hasStagedContent(pendingContent)) {
            return failWithoutReplacement(planFile, pending, resultFile, database, "BKP-003復元contentがありません")
        }
        if (contentMode != BackupContentBundleV136.FORMAT) {
            BackupContentBundleV136.removePending(pendingContent)
        }

        database.parentFile?.mkdirs()
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap content requirement anchor mismatch')
text = text.replace(old, new, 1)
old = '''        try {
            RestoreRollbackSafetyV086.deleteWalSidecars(database)
'''
new = '''        var contentRollback: BackupContentBundleV136.Rollback? = null
        try {
            RestoreRollbackSafetyV086.deleteWalSidecars(database)
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap rollback declaration anchor mismatch')
text = text.replace(old, new, 1)
old = '''            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // ここまで成功して初めて復元成功を確定する。
            planFile.delete()
            resultFile.writeText(
'''
new = '''            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // BKP-003: DBが正本として成立した後に、設定・画像を適用する。適用前状態は
            // rollbackとして保持し、以降で失敗した場合はDBと同じ境界で元へ戻す。
            contentRollback = BackupContentBundleV136.applyStagedWithRollback(context, pendingContent, restoreDir)

            // ここまで成功して初めて復元成功を確定する。
            planFile.delete()
            BackupContentBundleV136.removePending(pendingContent)
            contentRollback?.discard()
            contentRollback = null
            resultFile.writeText(
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap apply content anchor mismatch')
text = text.replace(old, new, 1)
old = '''        } catch (error: Throwable) {
            val rollbackResult = if (rollback != null) {
'''
new = '''        } catch (error: Throwable) {
            val contentRollbackResult = runCatching {
                contentRollback?.restore()
                if (contentRollback != null) " / 設定・画像ロールバック完了" else ""
            }.getOrElse { rollbackError -> " / 設定・画像ロールバック失敗: ${rollbackError.message}" }
            val rollbackResult = if (rollback != null) {
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap catch content rollback anchor mismatch')
text = text.replace(old, new, 1)
old = '''            planFile.delete()
            pending.delete()
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult",
'''
new = '''            planFile.delete()
            pending.delete()
            BackupContentBundleV136.removePending(pendingContent)
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult$contentRollbackResult",
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap catch cleanup anchor mismatch')
text = text.replace(old, new, 1)
old = '''    private fun failWithoutReplacement(plan: File, pending: File, result: File, database: File, message: String) {
        PendingRestoreWriteFenceV116.remove(database)
        plan.delete()
        pending.delete()
        result.writeText("復元予約を破棄: $message", Charsets.UTF_8)
    }
'''
new = '''    private fun failWithoutReplacement(plan: File, pending: File, result: File, database: File, message: String) {
        PendingRestoreWriteFenceV116.remove(database)
        plan.delete()
        pending.delete()
        BackupContentBundleV136.removePending(File(plan.parentFile, "pending-content-v136"))
        result.writeText("復元予約を破棄: $message", Charsets.UTF_8)
    }
'''
if text.count(old) != 1:
    raise SystemExit('bootstrap fail cleanup anchor mismatch')
text = text.replace(old, new, 1)
p.write_text(text)
