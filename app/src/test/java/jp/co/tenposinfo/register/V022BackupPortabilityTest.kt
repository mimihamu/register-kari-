package jp.co.tenposinfo.register

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V022BackupPortabilityTest {
    @Test
    fun streamCopyPreservesBytesAndReturnsExactCount() {
        val source = ByteArray(200_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(source.size.toLong(), BackupTransferPolicy.copyWithLimit(ByteArrayInputStream(source), output, source.size.toLong()))
        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun streamCopyRejectsOversizedImport() {
        val source = ByteArray(33) { 1 }
        assertTrue(runCatching {
            BackupTransferPolicy.copyWithLimit(ByteArrayInputStream(source), ByteArrayOutputStream(), 32)
        }.isFailure)
    }

    @Test
    fun importedNameIsCanonicalAndTraversalSafe() {
        val manifest = BackupManifest(
            createdAt = 1785686400000L,
            appVersion = "0.22.0-dev.1",
            databaseUserVersion = 4,
            databaseSha256 = "abcdef0123456789" + "0".repeat(48),
            tableCounts = mapOf("sales" to 1L),
        )
        assertEquals("TSUGUREGI_import_1785686400000_abcdef0123456789.tgbak", BackupImportNamePolicy.canonical(manifest))
        assertTrue(runCatching { BackupFilePolicy.requireSafe(BackupImportNamePolicy.canonical(manifest)) }.isSuccess)
    }
}
