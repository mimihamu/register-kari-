package jp.co.tenposinfo.register

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
