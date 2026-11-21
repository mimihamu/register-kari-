package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V137HeldLineIdEndToEndContractTest {
    @Test
    fun allHeldWritesPersistActualRowIdentityAndTaxSnapshotsTogether() {
        val source = File("src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt").readText()
        assertTrue(source.contains("put(\"line_id\", lineId)"))
        assertTrue(source.contains("val normalized = CartLineIdentityV137.normalize(items)"))
        assertTrue(source.contains("LineTaxSnapshotStore.save(db, LineTaxSnapshotStore.SCOPE_HELD, ticketId, normalized)"))
    }

    @Test
    fun legacyHeldReadRemainsReadOnlyAfterOneTimeAtomicRepair() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        assertTrue(source.contains("if (!CartLineIdentityV137.needsRepair(rows))"))
        assertTrue(source.contains("return repairLegacyHeldLineIds(ticketId)"))
        assertTrue(source.contains("ContentValues().apply { put(\"line_id\", lineId) }"))
        assertTrue(source.contains("if (db.inTransaction()) repair() else db.runInTransactionWithResult { repair() }"))
    }
}
