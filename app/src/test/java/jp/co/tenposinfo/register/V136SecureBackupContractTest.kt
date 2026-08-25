package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136SecureBackupContractTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")

    @Test
    fun envelopeUsesFormalCryptoPrimitivesAndMinimumKdfCost() {
        val source = File(root, "BackupEnvelopeV136.kt").readText()
        assertTrue(source.contains("TSUGUREGI_BACKUP_V2"))
        assertTrue(source.contains("AES/GCM/NoPadding"))
        assertTrue(source.contains("PBKDF2WithHmacSHA256"))
        assertTrue(source.contains("PBKDF2_ITERATIONS = 210_000"))
        assertTrue(source.contains("KEY_BITS = 256"))
        assertTrue(source.contains("SALT_BYTES = 16"))
        assertTrue(source.contains("AndroidKeyStore"))
        assertTrue(source.contains("ByteArray(KEY_BITS / 8).also(random::nextBytes)"))
        assertTrue(source.contains("backup_manifest.json"))
        assertTrue(source.contains("payload.bin"))
    }

    @Test
    fun portableExportAddsSecondWrapAndSelfTestsBeforeSafWrite() {
        val source = File(root, "BackupEnvelopeV136.kt").readText()
        val selfTestAt = source.indexOf("selfTestPortable(context, portableFile, passphrase)")
        val safCopyAt = source.indexOf("FileInputStream(portableFile).buffered().use")
        assertTrue(selfTestAt >= 0)
        assertTrue(safCopyAt > selfTestAt)
        assertTrue(source.contains("portableSalt = salt"))
        assertTrue(source.contains("portableIterations = PBKDF2_ITERATIONS"))
        assertTrue(source.contains("portableWrappedDek"))
        assertTrue(source.contains("importPortable"))
        assertTrue(source.contains("deviceWrapNonce = deviceWrap.first"))
        assertTrue(source.contains("deviceWrappedDek = deviceWrap.second"))
    }

    @Test
    fun dataProtectionNeverExportsLegacyPlainDatabaseBundle() {
        val source = File(root, "DataProtection.kt").readText()
        assertTrue(source.contains("privatePlainBundle"))
        assertTrue(source.contains("BackupEnvelopeV136.createLocalEnvelope"))
        assertTrue(source.contains("BackupEnvelopeV136.exportPortable"))
        assertTrue(source.contains("BackupEnvelopeV136.importPortable"))
        assertTrue(source.contains("BackupEnvelopeV136.decryptLocalTo"))
        assertTrue(source.contains("旧式の平文バックアップは安全要件を満たさないため復元できません"))
        assertTrue(source.contains("cacheDir.usableSpace"))
        assertTrue(source.contains("report.tableCounts == outerManifest.tableCounts"))
        assertFalse(source.contains("fun exportBackup(fileName: String, output: OutputStream, actorName: String):"))
    }

    @Test
    fun externalSafFlowRequiresPassphraseAndDoesNotPersistIt() {
        val source = File(root, "DataProtectionActivity.kt").readText()
        assertTrue(source.contains("外部バックアップ用パスフレーズ"))
        assertTrue(source.contains("パスフレーズ確認（外部保存時）"))
        assertTrue(source.contains("manager.exportBackup(fileName, output, actor, chars)"))
        assertTrue(source.contains("manager.importBackup(input, actor, chars)"))
        assertTrue(source.contains("backupPassphrase = \"\""))
        assertTrue(source.contains("端末内には保存しません"))
        assertTrue(source.contains("ActivityResultContracts.CreateDocument"))
        assertTrue(source.contains("ActivityResultContracts.OpenDocument"))
    }
}
