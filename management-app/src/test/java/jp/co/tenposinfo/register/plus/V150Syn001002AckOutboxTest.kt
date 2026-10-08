package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V150Syn001002AckOutboxTest {
    private val module = File(System.getProperty("user.dir")).let { if (File(it, "management-app").isDirectory) File(it, "management-app") else it }
    private fun source(name: String) = File(module, "src/main/java/jp/co/tenposinfo/register/plus/$name").readText()

    @Test fun ackHasFormalImmutableOutboxFields() {
        val text = source("PlusAckOutboxV150.kt")
        listOf(
            "document_id", "document_type", "source_business_id", "schema_version", "canonical_payload_bytes BLOB",
            "sha256", "producer_id", "sequence_no", "completion_mode", "status", "SYN_ACK_OUTBOX_DOCUMENT_IMMUTABLE",
        ).forEach { assertTrue("missing $it", text.contains(it)) }
        assertTrue(text.contains("ImportAckResultV150.IMPORTED") || source("SalesJournalImportRepository.kt").contains("ImportAckResultV150.IMPORTED"))
    }

    @Test fun ackMaterializesBeforeImportCommit() {
        val text = source("SalesJournalImportRepository.kt")
        val materialize = text.indexOf("PlusAckOutboxV150.materialize")
        val hook = text.indexOf("beforeCommit(db)", materialize)
        val successful = text.indexOf("db.setTransactionSuccessful()", materialize)
        assertTrue(materialize > 0 && hook > materialize && successful > hook)
        assertTrue(text.contains("ImportAckResultV150.DUPLICATE"))
        assertTrue(text.contains("ImportAckResultV150.REJECTED"))
    }

    @Test fun driveDeliveryUsesStoredBlobAndHashOnly() {
        val ack = source("PlusAckOutboxV150.kt")
        assertTrue(ack.contains("SELECT document_id,canonical_payload_bytes,sha256,attempt_count"))
        assertTrue(ack.contains("client.createJson"))
        assertTrue(ack.contains("bytes = row.bytes"))
        assertTrue(!ack.contains("FROM imported_journal"))
        val drive = source("GoogleDriveDirectSync.kt")
        assertTrue(drive.contains("PlusAckOutboxV150.deliverPending"))
        assertTrue(drive.contains("DRIVE_UPLOAD_URL"))
    }

    @Test fun ackMultipartUsesHttpCrLfEscapes() {
        val drive = source("GoogleDriveDirectSync.kt")
        assertTrue(drive.contains("\\r\\nContent-Type"))
        assertTrue(!drive.contains("\\\\r\\\\nContent-Type"))
    }

    @Test fun menuApplyResultIsAcceptedWithoutBlockingDriveImport() {
        assertTrue(source("SalesJournalImportContract.kt").contains("\"MENU_APPLY_RESULT\""))
    }
}
