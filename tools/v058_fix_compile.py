from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt"
text = path.read_text(encoding="utf-8")

replacements = {
    'require(normalized.isNotBlank()) { "$labelを入力してください" }': 'require(normalized.isNotBlank()) { "${label}を入力してください" }',
    'error("売上No.$saleIdが見つかりません")': 'error("売上No.${saleId}が見つかりません")',
    'error("領収書No.R$issuanceIdが見つかりません")': 'error("領収書No.R${issuanceId}が見つかりません")',
}
for old, new in replacements.items():
    if old not in text:
        raise RuntimeError(f"missing expected text: {old}")
    text = text.replace(old, new, 1)

old_block = '''        db.beginTransaction()
        try {
            existingBatchResult(request.requestId.trim())?.let {
                result = it.copy(idempotentReplay = true)
                db.setTransactionSuccessful()
                return@try
            }
            val allocated = longQuery(
                "SELECT COALESCE(SUM(amount), 0) FROM receipt_voucher_issuances WHERE sale_id = ?",
                arrayOf(request.saleId.toString()),
            )
            val plan = ReceiptVoucherPolicy.plan(
                request,
                ReceiptVoucherAvailability(sale.summary.totalAmount, allocated),
            )
            val batchId = db.insertOrThrow(
                "receipt_voucher_batches",
                null,
                ContentValues().apply {
                    put("request_id", plan.requestId)
                    put("sale_id", plan.saleId)
                    put("unit_amount", plan.unitAmount)
                    put("copy_count", plan.copies)
                    put("total_amount", plan.totalAmount)
                    put("addressee", plan.addressee)
                    put("purpose", plan.purpose)
                    put("operator_name", plan.operatorName)
                    put("created_at", now)
                },
            )
            val issuanceIds = mutableListOf<Long>()
            val printJobIds = mutableListOf<Long>()
            repeat(plan.copies) { zeroIndex ->
                val sequence = zeroIndex + 1
                val issuanceId = db.insertOrThrow(
                    "receipt_voucher_issuances",
                    null,
                    ContentValues().apply {
                        put("batch_id", batchId)
                        put("sale_id", plan.saleId)
                        put("sequence_no", sequence)
                        put("sequence_count", plan.copies)
                        put("amount", plan.unitAmount)
                        put("addressee", plan.addressee)
                        put("purpose", plan.purpose)
                        put("operator_name", plan.operatorName)
                        put("created_at", now)
                    },
                )
                val payload = ReceiptVoucherRenderer.render(
                    ReceiptVoucherDocumentData(
                        issuanceId = issuanceId,
                        saleId = plan.saleId,
                        sequenceNo = sequence,
                        sequenceCount = plan.copies,
                        amount = plan.unitAmount,
                        addressee = plan.addressee,
                        purpose = plan.purpose,
                        operatorName = plan.operatorName,
                        issuedAt = now,
                        issuer = issuer,
                    ),
                    ReceiptPaper.fromWidth(paperWidthMm),
                )
                val printJobId = insertDocumentPrintJob(issuanceId, paperWidthMm, payload, now)
                issuanceIds += issuanceId
                printJobIds += printJobId
            }
            result = ReceiptVoucherIssueResult(
                batchId = batchId,
                issuanceIds = issuanceIds,
                printJobIds = printJobIds,
                totalAmount = plan.totalAmount,
                remainingAmount = sale.summary.totalAmount - allocated - plan.totalAmount,
                idempotentReplay = false,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
'''
new_block = '''        db.beginTransaction()
        try {
            val existingInsideTransaction = existingBatchResult(request.requestId.trim())
            if (existingInsideTransaction != null) {
                result = existingInsideTransaction.copy(idempotentReplay = true)
            } else {
                val allocated = longQuery(
                    "SELECT COALESCE(SUM(amount), 0) FROM receipt_voucher_issuances WHERE sale_id = ?",
                    arrayOf(request.saleId.toString()),
                )
                val plan = ReceiptVoucherPolicy.plan(
                    request,
                    ReceiptVoucherAvailability(sale.summary.totalAmount, allocated),
                )
                val batchId = db.insertOrThrow(
                    "receipt_voucher_batches",
                    null,
                    ContentValues().apply {
                        put("request_id", plan.requestId)
                        put("sale_id", plan.saleId)
                        put("unit_amount", plan.unitAmount)
                        put("copy_count", plan.copies)
                        put("total_amount", plan.totalAmount)
                        put("addressee", plan.addressee)
                        put("purpose", plan.purpose)
                        put("operator_name", plan.operatorName)
                        put("created_at", now)
                    },
                )
                val issuanceIds = mutableListOf<Long>()
                val printJobIds = mutableListOf<Long>()
                repeat(plan.copies) { zeroIndex ->
                    val sequence = zeroIndex + 1
                    val issuanceId = db.insertOrThrow(
                        "receipt_voucher_issuances",
                        null,
                        ContentValues().apply {
                            put("batch_id", batchId)
                            put("sale_id", plan.saleId)
                            put("sequence_no", sequence)
                            put("sequence_count", plan.copies)
                            put("amount", plan.unitAmount)
                            put("addressee", plan.addressee)
                            put("purpose", plan.purpose)
                            put("operator_name", plan.operatorName)
                            put("created_at", now)
                        },
                    )
                    val payload = ReceiptVoucherRenderer.render(
                        ReceiptVoucherDocumentData(
                            issuanceId = issuanceId,
                            saleId = plan.saleId,
                            sequenceNo = sequence,
                            sequenceCount = plan.copies,
                            amount = plan.unitAmount,
                            addressee = plan.addressee,
                            purpose = plan.purpose,
                            operatorName = plan.operatorName,
                            issuedAt = now,
                            issuer = issuer,
                        ),
                        ReceiptPaper.fromWidth(paperWidthMm),
                    )
                    val printJobId = insertDocumentPrintJob(issuanceId, paperWidthMm, payload, now)
                    issuanceIds += issuanceId
                    printJobIds += printJobId
                }
                result = ReceiptVoucherIssueResult(
                    batchId = batchId,
                    issuanceIds = issuanceIds,
                    printJobIds = printJobIds,
                    totalAmount = plan.totalAmount,
                    remainingAmount = sale.summary.totalAmount - allocated - plan.totalAmount,
                    idempotentReplay = false,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
'''
if old_block not in text:
    raise RuntimeError("issueBatch transaction block not found")
text = text.replace(old_block, new_block, 1)
path.write_text(text, encoding="utf-8")

# Restore the real cumulative workflow; the apply workflow is only a temporary transport.
workflow = subprocess.check_output(
    ["git", "show", "dd66f4eb89832e6dc553067f450ee7e4689a0067:.github/workflows/build-apk.yml"],
    text=True,
)
(ROOT / ".github/workflows/build-apk.yml").write_text(workflow, encoding="utf-8")

print("v0.58 receipt compile fix applied")
