from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt")
text = path.read_text(encoding="utf-8")
old = '''            val fallbackMethod = if (sale.second.contains("現金")) PaymentMethod.CASH.name else "OTHER"
            val refundPayments = PartialReturnPolicy.allocateRefundPayments(refundTotal, originalPayments, fallbackMethod)
'''
new = '''            val refundedPayments = mutableListOf<PaymentTotal>()
            rawQuery(
                """
                SELECT rp.payment_method, COALESCE(SUM(rp.amount), 0)
                FROM reversal_payments rp
                INNER JOIN reversal_transactions r ON r.id = rp.reversal_id
                WHERE r.original_sale_id = ?
                GROUP BY rp.payment_method
                ORDER BY rp.payment_method
                """.trimIndent(),
                arrayOf(originalSaleId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    refundedPayments += PaymentTotal(cursor.getString(0), cursor.getLong(1))
                }
            }
            val fallbackMethod = if (sale.second.contains("現金")) PaymentMethod.CASH.name else "OTHER"
            val refundPayments = PartialReturnPolicy.allocateRefundPayments(
                refundTotal = refundTotal,
                originalPayments = originalPayments,
                fallbackMethod = fallbackMethod,
                refundedPayments = refundedPayments,
            )
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one allocator block, found {count}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("COR-006 OperationsStore exact patch applied")
