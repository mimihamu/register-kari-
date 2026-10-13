package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V149Bkp011PortableKeyDecryptionTest {
    private val appRoot = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    private fun source(name: String): String =
        File(appRoot, "src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun exportProvesSavedPortableWrapBeforeWritingExternalStream() {
        val envelope = source("BackupEnvelopeV136.kt")
        val export = envelope.substringAfter("fun exportPortable(").substringBefore("fun importPortable(")
        val packageWrite = export.indexOf("copyPayloadToEnvelope(localEnvelope, portableFile, manifest)")
        val keyProof = export.indexOf("verifyPortableWrappedDekForExport(manifest, passphrase, dek)")
        val payloadProof = export.indexOf("selfTestPortable(context, portableFile, passphrase)")
        val externalWrite = export.indexOf("FileInputStream(portableFile).buffered()")
        assertTrue(packageWrite >= 0)
        assertTrue(keyProof > packageWrite)
        assertTrue(payloadProof > keyProof)
        assertTrue(externalWrite > payloadProof)
    }

    @Test
    fun portableKeyProofDoesNotUseDeviceKeystore() {
        val envelope = source("BackupEnvelopeV136.kt")
        val proof = envelope.substringAfter("internal fun verifyPortableWrappedDekForExport(")
            .substringBefore("private fun selfTestLocal")
        assertTrue(proof.contains("unwrapPortable(manifest, passphrase)"))
        assertTrue(proof.contains("MessageDigest.isEqual(recoveredDek, expectedDek)"))
        assertTrue(proof.contains("recoveredDek.fill(0)"))
        assertFalse(proof.contains("unwrapWithDeviceKey"))
        assertFalse(proof.contains("AndroidKeyStore"))
    }

    @Test
    fun payloadSelfTestAlsoUsesPortableWrapOnly() {
        val envelope = source("BackupEnvelopeV136.kt")
        val portableTest = envelope.substringAfter("fun portableSelfTest(")
            .substringBefore("private fun selfTestLocal")
        assertTrue(portableTest.contains("unwrapPortable(manifest, passphrase)"))
        assertTrue(portableTest.contains("decryptPayload(envelope, manifest, dek"))
        assertFalse(portableTest.contains("unwrapWithDeviceKey"))
    }

    @Test
    fun spareTerminalImportDecryptsPortableWrapBeforeCreatingNewDeviceWrap() {
        val envelope = source("BackupEnvelopeV136.kt")
        val import = envelope.substringAfter("fun importPortable(")
            .substringBefore("fun portableSelfTest(")
        val portableUnwrap = import.indexOf("unwrapPortable(manifest, passphrase)")
        val payloadDecrypt = import.indexOf("decryptPayload(incoming, manifest, dek, testPlain)")
        val newDeviceWrap = import.indexOf("wrapWithDeviceKey(dek)")
        assertTrue(portableUnwrap >= 0)
        assertTrue(payloadDecrypt > portableUnwrap)
        assertTrue(newDeviceWrap > payloadDecrypt)
    }

    @Test
    fun fullRestorePathKeepsDbContentMasterAndOutboxVerification() {
        val protection = source("DataProtection.kt")
        assertTrue(protection.contains("BackupEnvelopeV136.exportPortable"))
        assertTrue(protection.contains("BackupEnvelopeV136.importPortable"))
        assertTrue(protection.contains("verifyArchive(temporary, \"external-import.tgbak\")"))
        assertTrue(protection.contains("BackupContentBundleV136.extractAndVerify"))
        assertTrue(protection.contains("sync_outbox"))
        assertTrue(protection.contains("requiredTables"))
    }
}
