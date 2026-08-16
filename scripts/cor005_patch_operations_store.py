from pathlib import Path

path = Path('app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt')
source = path.read_text(encoding='utf-8')

old_totals = '''        val reversalGross = longQuery(
            "SELECT COALESCE(SUM(gross_amount), 0) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        )
        val reversalCount = longQuery(
            "SELECT COUNT(*) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).toInt()
'''
new_totals = '''        val linkedReversalGross = longQuery(
            "SELECT COALESCE(SUM(gross_amount), 0) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        )
        val linkedReversalCount = longQuery(
            "SELECT COUNT(*) FROM reversal_transactions WHERE business_session_id = ?",
            arrayOf(sessionId.toString()),
        ).toInt()
        val manualReturnGross = if (SchemaMigration.tableExists(db, "manual_return_transactions")) {
            -longQuery(
                "SELECT COALESCE(SUM(gross_amount), 0) FROM manual_return_transactions WHERE business_session_id = ?",
                arrayOf(sessionId.toString()),
            )
        } else 0L
        val manualReturnCount = if (SchemaMigration.tableExists(db, "manual_return_transactions")) {
            longQuery(
                "SELECT COUNT(*) FROM manual_return_transactions WHERE business_session_id = ?",
                arrayOf(sessionId.toString()),
            ).toInt()
        } else 0
        val reversalGross = linkedReversalGross + manualReturnGross
        val reversalCount = linkedReversalCount + manualReturnCount
'''
if source.count(old_totals) != 1:
    raise SystemExit(f'expected one reversal totals block, found {source.count(old_totals)}')
source = source.replace(old_totals, new_totals)

old_payments = '''        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
            FROM reversal_payments p
            INNER JOIN reversal_transactions r ON r.id = p.reversal_id
            WHERE r.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val method = cursor.getString(0)
                paymentMap[method] = (paymentMap[method] ?: 0L) - cursor.getLong(1)
            }
        }

        val cashIn = movementTotal(CashMovementType.IN, sessionId)
'''
new_payments = '''        db.rawQuery(
            """
            SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
            FROM reversal_payments p
            INNER JOIN reversal_transactions r ON r.id = p.reversal_id
            WHERE r.business_session_id = ?
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val method = cursor.getString(0)
                paymentMap[method] = (paymentMap[method] ?: 0L) - cursor.getLong(1)
            }
        }
        if (SchemaMigration.tableExists(db, "manual_return_payments")) {
            db.rawQuery(
                """
                SELECT p.payment_method, COALESCE(SUM(p.amount), 0)
                FROM manual_return_payments p
                INNER JOIN manual_return_transactions r ON r.id = p.manual_return_id
                WHERE r.business_session_id = ?
                GROUP BY p.payment_method
                ORDER BY p.payment_method
                """.trimIndent(),
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val method = cursor.getString(0)
                    paymentMap[method] = (paymentMap[method] ?: 0L) + cursor.getLong(1)
                }
            }
        }

        val cashIn = movementTotal(CashMovementType.IN, sessionId)
'''
if source.count(old_payments) != 1:
    raise SystemExit(f'expected one reversal payment block, found {source.count(old_payments)}')
source = source.replace(old_payments, new_payments)

path.write_text(source, encoding='utf-8')
print('COR-005 OperationsStore accounting patch applied')
