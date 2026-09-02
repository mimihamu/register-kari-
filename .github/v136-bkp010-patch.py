from pathlib import Path
import re

root = Path('.')
main_dir = root / 'app/src/main/java/jp/co/tenposinfo/register'
test_dir = root / 'app/src/test/java/jp/co/tenposinfo/register'
docs_dir = root / 'docs'

# 1) Persist the verified backup source counts/date in the restore plan so the
# final audit record survives the database swap and can identify exactly what
# was restored.
protection = main_dir / 'DataProtection.kt'
s = protection.read_text(encoding='utf-8')
needle = '''                "sale_sequence_floor" to migrationPlan.saleSequenceFloor.toString(),
                "remote_ack_max_sale_id" to migrationPlan.remoteAckMaxSaleId.toString(),
            ))'''
replacement = '''                "sale_sequence_floor" to migrationPlan.saleSequenceFloor.toString(),
                "remote_ack_max_sale_id" to migrationPlan.remoteAckMaxSaleId.toString(),
                "backup_created_at" to verification.manifest.createdAt.toString(),
                "restore_record_count" to RestoreAuditContractV148.totalCount(verification.manifest.tableCounts).toString(),
                "restore_table_counts" to RestoreAuditContractV148.encodeTableCounts(verification.manifest.tableCounts),
            ))'''
if needle not in s:
    raise SystemExit('BKP-010 restore plan insertion point not found')
s = s.replace(needle, replacement, 1)
protection.write_text(s, encoding='utf-8')

# 2) Pure audit contract: deterministic count encoding and complete SUCCESS /
# FAILED detail contract. Keep it Android-free so focused JVM tests can cover it.
(main_dir / 'RestoreAuditContractV148.kt').write_text(r'''package jp.co.tenposinfo.register

internal object RestoreAuditContractV148 {
    fun totalCount(tableCounts: Map<String, Long>): Long {
        var total = 0L
        tableCounts.toSortedMap().forEach { (table, count) ->
            require(table.isNotBlank()) { "復元監査のテーブル名が空です" }
            require(count >= 0L) { "復元監査の件数が負数です: $table=$count" }
            total = Math.addExact(total, count)
        }
        return total
    }

    fun encodeTableCounts(tableCounts: Map<String, Long>): String = tableCounts.toSortedMap()
        .entries
        .joinToString(",") { (table, count) ->
            require(table.matches(Regex("[A-Za-z0-9_]+"))) { "復元監査のテーブル名が不正です: $table" }
            require(count >= 0L) { "復元監査の件数が負数です: $table=$count" }
            "$table:$count"
        }

    fun successDetail(plan: Map<String, String>, syncSummary: String): String =
        common(plan) + " / result=SUCCESS / " + sanitize(syncSummary, 1024)

    fun failureDetail(plan: Map<String, String>, reason: String, rollbackResult: String): String =
        common(plan) + " / result=FAILED / reason=${sanitize(reason, 768)} / rollback=${sanitize(rollbackResult, 768)}"

    private fun common(plan: Map<String, String>): String = buildString {
        append("source=").append(sanitize(plan["backup_file"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / sourceSha256=").append(sanitize(plan["database_sha256"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / sourceCreatedAt=").append(sanitize(plan["backup_created_at"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / restoredCount=").append(sanitize(plan["restore_record_count"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / tableCounts=").append(sanitize(plan["restore_table_counts"].orEmpty().ifBlank { "UNKNOWN" }, 2048))
        append(" / restoreMode=").append(sanitize(plan["restore_mode"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / storeId=").append(sanitize(plan["target_store_id"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / oldTerminalId=").append(sanitize(plan["source_terminal_id"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / newTerminalId=").append(sanitize(plan["target_terminal_id"].orEmpty().ifBlank { "UNKNOWN" }))
    }

    private fun sanitize(value: String, maxLength: Int = 512): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\u0000', ' ')
        .take(maxLength)
}
''', encoding='utf-8')

# 3) Make the final audit correspond to an actually completed restore. A
# failure is written after rollback to the surviving database. Early failures
# also write to the current DB; a blank terminal gets a normal RegisterDatabase
# created solely so the failure remains traceable instead of living only in a
# transient result text file.
bootstrap = main_dir / 'DataRestoreBootstrapV086.kt'
s = bootstrap.read_text(encoding='utf-8')
old_early_audit = '''            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan, syncRebuild)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)
'''
new_early_audit = '''            // migration後の正本DBを検証する。BKP-010のSUCCESS監査は設定・outboxを含む
            // 全復元処理が完了した後に記録する。
            DatabaseRecoveryIntegrityV116.verifyFinal(context)
'''
if old_early_audit not in s:
    raise SystemExit('BKP-010 early success audit block not found')
s = s.replace(old_early_audit, new_early_audit, 1)

final_verify = '''            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // 成功記録の書込みまでrollback snapshotを保持する。'''
final_verify_replacement = '''            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // BKP-010: SUCCESSはDB・設定・画像・outbox復旧まで完了した時点で初めて監査する。
            // 監査書込み自体も復元成功条件に含め、その後にもう一度DBを最終検証する。
            insertRestoreAudit(database, plan, syncRebuild)
            DatabaseRecoveryIntegrityV116.verifyFinal(context)

            // 成功記録の書込みまでrollback snapshotを保持する。'''
if final_verify not in s:
    raise SystemExit('BKP-010 final success audit insertion point not found')
s = s.replace(final_verify, final_verify_replacement, 1)

# Every pre-replacement failure path receives Context so it can persist a
# failure audit even when the terminal had no existing register.db.
s = s.replace('return failWithoutReplacement(planFile,', 'return failWithoutReplacement(context, planFile,')

catch_old = '''            planFile.delete()
            pending.delete()
            BackupContentBundleV136.removePending(pendingContent)
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult$contentRollbackResult",
                Charsets.UTF_8,
            )'''
catch_new = '''            val failureAuditResult = insertRestoreFailureAudit(
                context = context,
                file = database,
                plan = plan,
                reason = error.message ?: error.javaClass.simpleName,
                rollbackResult = rollbackResult + contentRollbackResult,
            )
            planFile.delete()
            pending.delete()
            BackupContentBundleV136.removePending(pendingContent)
            resultFile.writeText(
                "復元失敗: ${error.message} / $rollbackResult$contentRollbackResult$failureAuditResult",
                Charsets.UTF_8,
            )'''
if catch_old not in s:
    raise SystemExit('BKP-010 catch audit insertion point not found')
s = s.replace(catch_old, catch_new, 1)

start = s.find('    private fun insertRestoreAudit(')
end = s.find('    private fun failWithoutReplacement(', start)
if start < 0 or end < 0:
    raise SystemExit('BKP-010 audit helper replacement markers not found')
helpers = r'''    private fun insertRestoreAudit(
        file: File,
        plan: Map<String, String>,
        syncRebuild: RestoreSyncRebuildResultV136,
    ) {
        val syncSummary =
            "BKP-006=rebuild:${syncRebuild.rebuiltCount}," +
                "missing:${syncRebuild.remainingMissingCount}," +
                "sent-preserved:${syncRebuild.preservedSentCount}," +
                "ack-preserved:${syncRebuild.preservedAckCount}," +
                "documentId-preserved:${syncRebuild.preservedDocumentIdCount}"
        insertRestoreAuditRecord(
            file = file,
            eventType = "DATA_RESTORE_APPLIED",
            plan = plan,
            detail = RestoreAuditContractV148.successDetail(plan, syncSummary),
        )
    }

    private fun insertRestoreFailureAudit(
        context: Context,
        file: File,
        plan: Map<String, String>,
        reason: String,
        rollbackResult: String,
    ): String = runCatching {
        if (!file.isFile) {
            RegisterDatabase(context).use { helper -> helper.writableDatabase }
        }
        insertRestoreAuditRecord(
            file = file,
            eventType = "DATA_RESTORE_FAILED",
            plan = plan,
            detail = RestoreAuditContractV148.failureDetail(plan, reason, rollbackResult),
        )
        " / 復元失敗監査=記録済み"
    }.getOrElse { auditError ->
        " / 復元失敗監査=記録失敗:${auditError.message ?: auditError.javaClass.simpleName}"
    }

    private fun insertRestoreAuditRecord(
        file: File,
        eventType: String,
        plan: Map<String, String>,
        detail: String,
    ) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS operation_audit (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "event_type TEXT NOT NULL,reference_id INTEGER NOT NULL,detail TEXT NOT NULL," +
                    "operator_name TEXT NOT NULL,created_at INTEGER NOT NULL)",
            )
            database.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", eventType)
                    put("reference_id", 0)
                    put("detail", detail)
                    put("operator_name", plan["actor_name"].orEmpty().ifBlank { "責任者不明" })
                    put("created_at", System.currentTimeMillis())
                },
            )
        } finally {
            database.close()
        }
    }

'''
s = s[:start] + helpers + s[end:]

# Replace the final helper (it is the last function in PendingRestoreApplierV086).
pattern = re.compile(r'''    private fun failWithoutReplacement\([\s\S]*?\n    \}\n\}''')
match = pattern.search(s)
if not match:
    raise SystemExit('BKP-010 failWithoutReplacement function not found')
new_fail = r'''    private fun failWithoutReplacement(
        context: Context,
        plan: File,
        pending: File,
        result: File,
        database: File,
        message: String,
    ) {
        val auditPlan = runCatching {
            if (plan.isFile) DataProtectionManager.readSimpleProperties(plan.readText(Charsets.UTF_8)) else emptyMap()
        }.getOrDefault(emptyMap())
        val failureAuditResult = insertRestoreFailureAudit(
            context = context,
            file = database,
            plan = auditPlan,
            reason = message,
            rollbackResult = "元DB保持",
        )
        plan.delete()
        pending.delete()
        BackupContentBundleV136.removePending(File(plan.parentFile, "pending-content-v136"))
        val fenceCleanup = runCatching { PendingRestoreWriteFenceV116.remove(database) }
            .exceptionOrNull()
            ?.let { " / フェンス解除失敗: ${it.message}" }
            .orEmpty()
        result.writeText("復元予約を破棄・元DB保持: $message$fenceCleanup$failureAuditResult", Charsets.UTF_8)
    }
}'''
s = s[:match.start()] + new_fail + s[match.end():]
bootstrap.write_text(s, encoding='utf-8')

# 4) Focused regression tests include the pure detail/count contract and source
# integration ordering, so a future refactor cannot silently move SUCCESS audit
# ahead of content/outbox completion or drop FAILED audit paths.
(test_dir / 'V148Bkp010RestoreAuditTest.kt').write_text(r'''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V148Bkp010RestoreAuditTest {
    private val appRoot = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    @Test
    fun countEncodingIsDeterministicAndTotalIsExplicit() {
        val counts = linkedMapOf("sales" to 7L, "products" to 3L, "sync_outbox" to 2L)
        assertEquals(12L, RestoreAuditContractV148.totalCount(counts))
        assertEquals("products:3,sales:7,sync_outbox:2", RestoreAuditContractV148.encodeTableCounts(counts))
    }

    @Test
    fun successAndFailureDetailsContainFormalBkp010Fields() {
        val plan = mapOf(
            "backup_file" to "TSUGUREGI_backup_20260902.tgbak",
            "database_sha256" to "abc123",
            "backup_created_at" to "1788360000000",
            "restore_record_count" to "42",
            "restore_table_counts" to "products:10,sales:32",
            "restore_mode" to "SPARE_TERMINAL",
            "target_store_id" to "store-1",
            "source_terminal_id" to "old-terminal",
            "target_terminal_id" to "new-terminal",
        )
        val success = RestoreAuditContractV148.successDetail(plan, "BKP-006=rebuild:1")
        assertTrue(success.contains("source=TSUGUREGI_backup_20260902.tgbak"))
        assertTrue(success.contains("sourceCreatedAt=1788360000000"))
        assertTrue(success.contains("restoredCount=42"))
        assertTrue(success.contains("tableCounts=products:10,sales:32"))
        assertTrue(success.contains("oldTerminalId=old-terminal"))
        assertTrue(success.contains("newTerminalId=new-terminal"))
        assertTrue(success.contains("result=SUCCESS"))

        val failed = RestoreAuditContractV148.failureDetail(plan, "integrity failed\nunsafe", "元DBへロールバック完了")
        assertTrue(failed.contains("result=FAILED"))
        assertTrue(failed.contains("reason=integrity failed unsafe"))
        assertTrue(failed.contains("rollback=元DBへロールバック完了"))
    }

    @Test
    fun restorePipelinePersistsCountsActorResultAndFailureAudit() {
        fun source(name: String) = File(appRoot, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val protection = source("DataProtection.kt")
        val bootstrap = source("DataRestoreBootstrapV086.kt")

        assertTrue(protection.contains("\"backup_created_at\" to verification.manifest.createdAt.toString()"))
        assertTrue(protection.contains("\"restore_record_count\" to RestoreAuditContractV148.totalCount"))
        assertTrue(protection.contains("\"restore_table_counts\" to RestoreAuditContractV148.encodeTableCounts"))

        val outboxRecovery = bootstrap.indexOf("JournalOutboxStore(context).use")
        val successAudit = bootstrap.indexOf("insertRestoreAudit(database, plan, syncRebuild)", outboxRecovery)
        assertTrue("SUCCESS audit must happen after restored outbox recovery", outboxRecovery >= 0 && successAudit > outboxRecovery)
        assertTrue(bootstrap.contains("eventType = \"DATA_RESTORE_APPLIED\""))
        assertTrue(bootstrap.contains("eventType = \"DATA_RESTORE_FAILED\""))
        assertTrue(bootstrap.contains("put(\"operator_name\", plan[\"actor_name\"]"))
        assertTrue(bootstrap.contains("put(\"created_at\", System.currentTimeMillis())"))
        assertTrue(bootstrap.contains("insertRestoreFailureAudit(\n                context = context"))
        assertTrue(bootstrap.contains("return failWithoutReplacement(context, planFile,"))
    }
}
''', encoding='utf-8')

(docs_dir / 'V1.36_BKP_010_RESTORE_AUDIT.md').write_text(r'''# v1.36 BKP-010 復元監査

正式仕様 v2.5 `BKP-010` を正本とする。

## 正式要件

復元元、日時、実行者、件数、結果、旧／新端末IDを監査ログへ記録し、復元行為を追跡可能にする。

## v1.36 / V148 実装

- 復元予約時に、検証済みbackup manifestから `backup_created_at`、全table count合計 `restore_record_count`、table別 `restore_table_counts` をrestore planへ固定する。
- SUCCESS監査はDB差替え直後ではなく、schema migration、BKP-005端末移行、BKP-003設定・画像、BKP-006 outbox再構築・再queue、最終integrity検証まで完了した後に `DATA_RESTORE_APPLIED` として記録する。
- SUCCESS監査自体の書込み失敗は復元成功にしない。rollback境界内で失敗扱いにする。
- 復元途中で失敗した場合、元DBへのrollback後に生き残った `operation_audit` へ `DATA_RESTORE_FAILED` をbest-effort記録する。
- DB差替え前のhash/content/rollback作成等で失敗した場合も、現在DBへFAILED監査を記録してから候補を破棄する。元DBが存在しない新品端末では通常の `RegisterDatabase` を作成し、その監査ログへ失敗を残す。
- `operation_audit.created_at` が実行日時、`operator_name` が責任者、detailが復元元ファイル/hash/backup作成時刻/復元件数/table別件数/result/旧・新terminalId/rollback結果を保持する。
- audit detailへ改行/NULを持ち込まず、異常理由は長さ上限を設ける。

## 自動検証

`V148Bkp010RestoreAuditTest` で件数集計・table count正規化、SUCCESS/FAILED detail、責任者/日時、旧・新terminalId、SUCCESS監査のoutbox復旧後順序、pre-replacement/rollback failureの両監査経路を固定する。

## 実機未確認

- 実バックアップを復元し、監査画面/全データ出力で復元元・日時・責任者・件数・SUCCESS・旧/新terminalIdが追跡できること。
- 復元途中の媒体/電源/DB異常を実機で発生させ、元DBへrollback後にFAILED監査が残ること。
- 新品予備端末で復元前失敗した場合もFAILED監査が次回起動後に参照できること。
- 予備端末切替後の旧/new terminalIdが実機のidentityと監査ログで一致すること。
''', encoding='utf-8')
