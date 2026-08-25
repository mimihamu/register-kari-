from pathlib import Path

root = Path('.')
foundation_path = root / 'app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt'
activity_path = root / 'app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt'
recovery_path = root / 'app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchRecoveryV135.kt'


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}: {old[:80]!r}')
    path.write_text(text.replace(old, new, 1))


helper = root / 'app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchFixedAmountV136.kt'
helper.write_text('''package jp.co.tenposinfo.register

/**
 * v2.5 RCP-BATCH-SET-001 representative preview policy.
 * A large batch must never expand all tickets in the confirmation UI.
 */
internal data class ReceiptVoucherBatchPreviewRepresentativeV136(
    val sequenceNo: Int,
    val label: String,
)

internal object ReceiptVoucherBatchPreviewPolicyV136 {
    fun representatives(
        copies: Int,
        remainderDifferenceSequence: Int? = null,
    ): List<ReceiptVoucherBatchPreviewRepresentativeV136> {
        require(copies in ReceiptVoucherBatchSettingsV135.MIN_BATCH_COPIES..ReceiptVoucherBatchSettingsV135.MAX_BATCH_COPIES) {
            "一括領収書の枚数は1～999枚で指定してください"
        }
        val labels = linkedMapOf<Int, String>()
        labels[1] = if (copies == 1) "1枚目・最終票" else "1枚目"
        if (remainderDifferenceSequence != null && remainderDifferenceSequence in 2 until copies) {
            labels[remainderDifferenceSequence] = "端数差票"
        }
        if (copies > 1) labels[copies] = "最終票"
        return labels.map { (sequenceNo, label) ->
            ReceiptVoucherBatchPreviewRepresentativeV136(sequenceNo, label)
        }
    }
}
''')

replace_once(
    foundation_path,
    '''                        put("operator_name", plan.operatorName)\n                        put("created_at", now)\n                    },\n                )\n                val issuanceIds = mutableListOf<Long>()''',
    '''                        put("operator_name", plan.operatorName)\n                        put("status", "DRAFT")\n                        putNull("committed_at")\n                        put("created_at", now)\n                    },\n                )\n                val issuanceIds = mutableListOf<Long>()''',
)
replace_once(
    foundation_path,
    '''                result = ReceiptVoucherIssueResult(\n                    batchId = batchId,''',
    '''                db.update(\n                    "receipt_voucher_batches",\n                    ContentValues().apply {\n                        put("status", "COMMITTED")\n                        put("committed_at", now)\n                    },\n                    "id = ? AND status = ?",\n                    arrayOf(batchId.toString(), "DRAFT"),\n                ).also { updated ->\n                    check(updated == 1) { "領収書発行グループ RG-$batchId を確定できませんでした" }\n                }\n                db.insertOrThrow(\n                    "operation_audit",\n                    null,\n                    ContentValues().apply {\n                        put("event_type", "RECEIPT_VOUCHER_BATCH_COMMIT")\n                        put("reference_id", batchId)\n                        put(\n                            "detail",\n                            "sale=${plan.saleId} / ${plan.unitAmount}円×${plan.copies}枚 / total=${plan.totalAmount}円",\n                        )\n                        put("operator_name", plan.operatorName)\n                        put("created_at", now)\n                    },\n                )\n                result = ReceiptVoucherIssueResult(\n                    batchId = batchId,''',
)
replace_once(
    foundation_path,
    '''                operator_name TEXT NOT NULL,\n                created_at INTEGER NOT NULL\n            )\n            """.trimIndent(),\n        )\n        db.execSQL(\n            """\n            CREATE TABLE IF NOT EXISTS receipt_voucher_issuances''',
    '''                operator_name TEXT NOT NULL,\n                status TEXT NOT NULL DEFAULT 'COMMITTED',\n                committed_at INTEGER,\n                created_at INTEGER NOT NULL\n            )\n            """.trimIndent(),\n        )\n        db.execSQL(\n            """\n            CREATE TABLE IF NOT EXISTS receipt_voucher_issuances''',
)
replace_once(
    foundation_path,
    '''        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_voucher_sale ON receipt_voucher_issuances(sale_id, created_at)")''',
    '''        ensureBatchLifecycleSchema()\n        OperationAuditSchemaV136.ensure(db)\n        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_voucher_sale ON receipt_voucher_issuances(sale_id, created_at)")''',
)
replace_once(
    foundation_path,
    '''    private companion object {''',
    '''    private fun ensureBatchLifecycleSchema() {\n        val columns = db.rawQuery("PRAGMA table_info(receipt_voucher_batches)", null).use { cursor ->\n            buildSet {\n                val nameIndex = cursor.getColumnIndexOrThrow("name")\n                while (cursor.moveToNext()) add(cursor.getString(nameIndex))\n            }\n        }\n        if ("status" !in columns) {\n            db.execSQL("ALTER TABLE receipt_voucher_batches ADD COLUMN status TEXT NOT NULL DEFAULT 'COMMITTED'")\n        }\n        if ("committed_at" !in columns) {\n            db.execSQL("ALTER TABLE receipt_voucher_batches ADD COLUMN committed_at INTEGER")\n        }\n        db.execSQL(\n            "UPDATE receipt_voucher_batches SET status = 'COMMITTED' WHERE status IS NULL OR status = ''",\n        )\n        db.execSQL(\n            "UPDATE receipt_voucher_batches SET committed_at = created_at WHERE status = 'COMMITTED' AND committed_at IS NULL",\n        )\n    }\n\n    private companion object {''',
)

replace_once(
    recovery_path,
    '''    val resumable: Boolean get() = status != ReceiptVoucherBatchPrintStatus.PRINTED &&\n        items.none { it.status == PrintJobStatus.DISCARDED || it.jobId == null }''',
    '''    val resumable: Boolean get() = status != ReceiptVoucherBatchPrintStatus.PRINTED &&\n        items.none { it.status == PrintJobStatus.DISCARDED || it.jobId == null } &&\n        items.none { it.status == PrintJobStatus.SENDING || it.status == PrintJobStatus.PRINTING }''',
)
replace_once(
    recovery_path,
    '''            it.attemptCount > 0 || it.status in setOf(PrintJobStatus.PRINTING, PrintJobStatus.RETRY)''',
    '''            it.attemptCount > 0 || it.status in setOf(PrintJobStatus.SENDING, PrintJobStatus.PRINTING, PrintJobStatus.RETRY)''',
)
replace_once(
    recovery_path,
    '''        require(before.items.none { it.status == PrintJobStatus.PRINTING }) {\n            "印刷中の票があります。完了または失敗を確認してから再開してください"\n        }''',
    '''        require(before.items.none { it.status == PrintJobStatus.SENDING || it.status == PrintJobStatus.PRINTING }) {\n            "送信中または印刷済みの可能性がある票があります。統合印刷キューで完了扱い／再印刷を判断してください"\n        }''',
)

replace_once(
    activity_path,
    '''                                    if ((calculation?.copies ?: 0) > 1) {\n                                        Text("複数枚は補助領収書として発行し、各票に『${ReceiptVoucherRenderer.NOT_QUALIFIED_LABEL}』を印字します。", fontSize = 12.sp)\n                                    }\n                                    Spacer(Modifier.height(8.dp))''',
    '''                                    if ((calculation?.copies ?: 0) > 1) {\n                                        Text("複数枚は補助領収書として発行し、各票に『${ReceiptVoucherRenderer.NOT_QUALIFIED_LABEL}』を印字します。", fontSize = 12.sp)\n                                    }\n                                    calculation?.let { calc ->\n                                        Spacer(Modifier.height(8.dp))\n                                        Text("代表票プレビュー（全票は描画しません）", fontWeight = FontWeight.Bold, color = VoucherNavy)\n                                        ReceiptVoucherBatchPreviewPolicyV136.representatives(calc.copies).forEach { representative ->\n                                            Card(\n                                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),\n                                                colors = CardDefaults.cardColors(containerColor = Color.White),\n                                                border = BorderStroke(1.dp, VoucherBorder),\n                                            ) {\n                                                Row(\n                                                    Modifier.fillMaxWidth().padding(8.dp),\n                                                    horizontalArrangement = Arrangement.SpaceBetween,\n                                                    verticalAlignment = Alignment.CenterVertically,\n                                                ) {\n                                                    Column {\n                                                        Text("${representative.label}  ${representative.sequenceNo}/${calc.copies}", fontWeight = FontWeight.Bold)\n                                                        Text("${addressee.ifBlank { "宛名空欄" }} / $purpose", fontSize = 12.sp, color = Color.DarkGray)\n                                                    }\n                                                    Text(voucherYen(calc.unitAmount), fontWeight = FontWeight.Bold, color = VoucherNavy)\n                                                }\n                                            }\n                                        }\n                                    }\n                                    Spacer(Modifier.height(8.dp))''',
)

test_path = root / 'app/src/test/java/jp/co/tenposinfo/register/V136ReceiptVoucherBatchFixedAmountTest.kt'
test_path.write_text('''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class V136ReceiptVoucherBatchFixedAmountTest {
    @Test
    fun fourThousandTimesThirtyPassesFormalLimitAndRemainingAmount() {
        val request = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 33,
            unitAmount = 4_000,
            copies = 30,
            addressee = "",
            purpose = "お食事代",
            operatorName = "担当A",
        )
        val plan = ReceiptVoucherPolicy.plan(
            request,
            ReceiptVoucherAvailability(saleTotal = 120_000, allocatedAmount = 0),
            maxCopies = 30,
        )
        assertEquals(120_000L, plan.totalAmount)
        assertEquals(30, plan.copies)
    }

    @Test
    fun configuredLimitAndRemainingAmountAreBothHardGates() {
        val base = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 33,
            unitAmount = 4_000,
            copies = 30,
            addressee = "",
            purpose = "お食事代",
            operatorName = "担当A",
        )
        assertTrue(
            runCatching {
                ReceiptVoucherPolicy.plan(base, ReceiptVoucherAvailability(120_000, 0), maxCopies = 29)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ReceiptVoucherPolicy.plan(base, ReceiptVoucherAvailability(119_999, 0), maxCopies = 30)
            }.isFailure,
        )
    }

    @Test
    fun representativePreviewNeverExpandsLargeBatch() {
        val preview = ReceiptVoucherBatchPreviewPolicyV136.representatives(999)
        assertEquals(listOf(1, 999), preview.map { it.sequenceNo })
        assertEquals(listOf("1枚目", "最終票"), preview.map { it.label })
        assertTrue(preview.size <= 3)
    }

    @Test
    fun remainderDifferenceTicketCanBeRepresentedWithoutRenderingEverything() {
        val preview = ReceiptVoucherBatchPreviewPolicyV136.representatives(
            copies = 30,
            remainderDifferenceSequence = 17,
        )
        assertEquals(listOf(1, 17, 30), preview.map { it.sequenceNo })
        assertEquals("端数差票", preview[1].label)
    }

    @Test
    fun singleCopyPreviewIsNotDuplicated() {
        val preview = ReceiptVoucherBatchPreviewPolicyV136.representatives(1)
        assertEquals(1, preview.size)
        assertEquals(1, preview.single().sequenceNo)
        assertEquals("1枚目・最終票", preview.single().label)
    }

    @Test
    fun issuanceCommitIsAuditedAndPhysicalDispatchStartsAfterDbTransaction() {
        val foundation = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val auditIndex = foundation.indexOf("RECEIPT_VOUCHER_BATCH_COMMIT")
        val endTransactionIndex = foundation.indexOf("db.endTransaction()")
        val schedulerIndex = foundation.indexOf("AutomaticPrintScheduler.enqueueNow(appContext)")

        assertTrue(foundation.contains("put(\"status\", \"DRAFT\")"))
        assertTrue(foundation.contains("put(\"status\", \"COMMITTED\")"))
        assertTrue(foundation.contains("committed_at"))
        assertTrue(auditIndex >= 0)
        assertTrue(endTransactionIndex > auditIndex)
        assertTrue(schedulerIndex > endTransactionIndex)
    }

    @Test
    fun batchRecoveryDoesNotAutoResumeUncertainSendingJobs() {
        val recovery = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchRecoveryV135.kt").readText()
        assertTrue(recovery.contains("PrintJobStatus.SENDING"))
        assertTrue(recovery.contains("印刷済みの可能性"))

        val items = listOf(
            ReceiptVoucherBatchPrintItemV135(
                sequenceNo = 1,
                issuanceId = 1,
                jobId = 10,
                status = PrintJobStatus.SENDING,
                attemptCount = 1,
                lastError = null,
                updatedAt = 1,
            ),
        )
        val progress = ReceiptVoucherBatchRecoveryPolicyV135.summarize(1, 33, 1, items)
        assertFalse(progress.resumable)
        assertEquals(ReceiptVoucherBatchPrintStatus.PRINTING, progress.status)
    }

    @Test
    fun confirmationUiUsesRepresentativePreviewAndFormalConfiguredLimit() {
        val activity = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val recovery = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchRecoveryV135.kt").readText()
        assertTrue(activity.contains("代表票プレビュー（全票は描画しません）"))
        assertTrue(activity.contains("ReceiptVoucherBatchPreviewPolicyV136.representatives(calc.copies)"))
        assertTrue(activity.contains("voucherStore.maxBatchCopies()"))
        assertEquals(100, ReceiptVoucherBatchSettingsV135.DEFAULT_MAX_BATCH_COPIES)
        assertEquals(999, ReceiptVoucherBatchSettingsV135.MAX_BATCH_COPIES)
        assertTrue(recovery.contains("receipt.maxBatchReceiptCopies"))
    }
}
''')

doc = root / 'docs/V1.36_RCP_BATCH_SET_001_FIXED_AMOUNT_BATCH.md'
doc.write_text('''# v1.36 RCP-BATCH-SET-001 領収書固定額一括発行

正式仕様 v2.5 の「指定額 × 枚数」一括領収書を既存の v1.35 領収書基盤へ累積実装する。

- 枚数は 1～999、かつ店舗設定 `receipt.maxBatchReceiptCopies` 以下（初期値100）。
- 発行予定総額は入力中に即時計算し、売上の領収書発行可能残額を超える確定を禁止する。
- 確認画面は大量枚数を全展開せず、1枚目・端数差票（該当時）・最終票の代表票だけを表示する。
- バッチは `DRAFT` から `COMMITTED` へ確定し、`RECEIPT_VOUCHER_BATCH_COMMIT` を `operation_audit` へ記録する。
- 印刷スケジューラの起動は領収書DBトランザクション完了後。プリンター送信失敗で領収書発行履歴をロールバックしない。
- `SENDING` / 旧 `PRINTING` は印刷済みの可能性があるため、一括再開から自動再送しない。統合印刷キューの「完了扱い」「再印刷」で担当者が解決する。
- 複数枚は補助領収書であり「適格簡易請求書ではありません」を各票へ印字する既存仕様を維持する。

## 実機確認

物理プリンターでの連続30枚発行、紙切れ・通信断からの復旧、カット位置、印字内容は実機未確認。
''')
