from pathlib import Path

p = Path('app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt')
text = p.read_text()

def swap(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, found {count}')
    text = text.replace(old, new, 1)

swap(
'''        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        val database = context.getDatabasePath(REGISTER_DATABASE_NAME_V086)
''',
'''        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        val pendingContent = File(restoreDir, "pending-content-v136")
        val database = context.getDatabasePath(REGISTER_DATABASE_NAME_V086)
''',
'pending paths')

swap(
'''        val actualHash = runCatching { DataProtectionManager.sha256(pending) }.getOrElse { error ->
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBを読み取れません: ${error.message}")
        }
        if (actualHash != expectedHash) {
            return failWithoutReplacement(planFile, pending, resultFile, database, "復元予約DBのSHA-256が一致しません")
        }

        database.parentFile?.mkdirs()
''',
'''        val actualHash = runCatching { DataProtectionManager.sha256(pending) }.getOrElse { error ->
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
''',
'content requirement')

swap(
'''        try {
            RestoreRollbackSafetyV086.deleteWalSidecars(database)
''',
'''        var contentRollback: BackupContentBundleV136.Rollback? = null
        try {
            RestoreRollbackSafetyV086.deleteWalSidecars(database)
''',
'rollback declaration')

swap(
'''            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // ここまで成功して初めて復元成功を確定する。
            planFile.delete()
            resultFile.writeText(
''',
'''            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // BKP-003: DBが正本として成立した後に設定・画像を適用する。
            // 適用前状態を保持し、後続失敗時はDBと同じ境界で元へ戻す。
            contentRollback = BackupContentBundleV136.applyStagedWithRollback(context, pendingContent, restoreDir)

            // ここまで成功して初めて復元成功を確定する。
            planFile.delete()
            BackupContentBundleV136.removePending(pendingContent)
            contentRollback?.discard()
            contentRollback = null
            resultFile.writeText(
''',
'apply content')

swap(
'''        } catch (error: Throwable) {
            val rollbackResult = if (rollback != null) {
''',
'''        } catch (error: Throwable) {
            val contentRollbackResult = runCatching {
                contentRollback?.restore()
                if (contentRollback != null) " / 設定・画像ロールバック完了" else ""
            }.getOrElse { rollbackError -> " / 設定・画像ロールバック失敗: ${rollbackError.message}" }
            val rollbackResult = if (rollback != null) {
''',
'catch rollback')

swap(
'''            planFile.delete()
            pending.delete()
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult",
''',
'''            planFile.delete()
            pending.delete()
            BackupContentBundleV136.removePending(pendingContent)
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult$contentRollbackResult",
''',
'failure cleanup')

swap(
'''    private fun failWithoutReplacement(
        plan: File,
        pending: File,
        result: File,
        database: File,
        message: String,
    ) {
        plan.delete()
        pending.delete()
        val fenceCleanup = runCatching { PendingRestoreWriteFenceV116.remove(database) }
            .exceptionOrNull()
            ?.let { " / フェンス解除失敗: ${it.message}" }
            .orEmpty()
        result.writeText("復元予約を破棄・元DB保持: $message$fenceCleanup", Charsets.UTF_8)
    }
''',
'''    private fun failWithoutReplacement(
        plan: File,
        pending: File,
        result: File,
        database: File,
        message: String,
    ) {
        plan.delete()
        pending.delete()
        BackupContentBundleV136.removePending(File(plan.parentFile, "pending-content-v136"))
        val fenceCleanup = runCatching { PendingRestoreWriteFenceV116.remove(database) }
            .exceptionOrNull()
            ?.let { " / フェンス解除失敗: ${it.message}" }
            .orEmpty()
        result.writeText("復元予約を破棄・元DB保持: $message$fenceCleanup", Charsets.UTF_8)
    }
''',
'fail without replacement cleanup')

p.write_text(text)
