from pathlib import Path

root = Path('.')
envelope_path = root / 'app/src/main/java/jp/co/tenposinfo/register/BackupEnvelopeV136.kt'
text = envelope_path.read_text()
old = '''            copyPayloadToEnvelope(localEnvelope, portableFile, manifest)\n            selfTestPortable(context, portableFile, passphrase)\n            FileInputStream(portableFile).buffered().use { input -> copyLimited(input, output, MAX_ENVELOPE_BYTES) }'''
new = '''            copyPayloadToEnvelope(localEnvelope, portableFile, manifest)\n            // BKP-011: prove the passphrase-wrapped DEK stored in the portable manifest can\n            // recover the exact DEK before the package is reported/exported as usable.\n            verifyPortableWrappedDekForExport(manifest, passphrase, dek)\n            selfTestPortable(context, portableFile, passphrase)\n            FileInputStream(portableFile).buffered().use { input -> copyLimited(input, output, MAX_ENVELOPE_BYTES) }'''
if old not in text:
    raise SystemExit('exportPortable anchor not found')
text = text.replace(old, new, 1)
anchor = '''    private fun selfTestLocal(context: Context, envelope: File) {\n'''
insert = '''    /**\n     * BKP-011 export-time portable-key proof. This deliberately uses only the passphrase wrap;\n     * Android Keystore is not consulted. Payload self-test follows separately and proves that the\n     * recovered DEK also decrypts the encrypted backup bytes.\n     */\n    internal fun verifyPortableWrappedDekForExport(\n        manifest: Manifest,\n        passphrase: CharArray,\n        expectedDek: ByteArray,\n    ) {\n        requirePassphrase(passphrase)\n        require(expectedDek.size == KEY_BITS / 8) { "可搬鍵検証用DEKサイズが不正です" }\n        val recoveredDek = unwrapPortable(manifest, passphrase)\n        try {\n            require(MessageDigest.isEqual(recoveredDek, expectedDek)) {\n                "保存した可搬鍵で元のバックアップ鍵を復号できません"\n            }\n        } finally {\n            recoveredDek.fill(0)\n        }\n    }\n\n'''
if anchor not in text:
    raise SystemExit('selfTestLocal anchor not found')
text = text.replace(anchor, insert + anchor, 1)
envelope_path.write_text(text)

test_path = root / 'app/src/test/java/jp/co/tenposinfo/register/V149Bkp011PortableKeyDecryptionTest.kt'
test_path.write_text(r'''package jp.co.tenposinfo.register

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
''')

doc_path = root / 'docs/V1.36_BKP_011_PORTABLE_KEY_DECRYPTION_TEST.md'
doc_path.write_text('''# v1.36 BKP-011 可搬鍵の復号試験\n\n正式仕様 v2.5 `BKP-011` を正本とする。\n\n## 要件\n\n外部バックアップ作成時に、packageへ保存した管理者パスフレーズラップ鍵からDEKを実際に復号できることを検証する。同一端末のAndroid Keystoreを利用できない予備端末でも、DB・設定・マスター・未送信キューを復元できる経路を維持する。\n\n## v1.36実装\n\n- `BackupEnvelopeV136.exportPortable()` はportable manifestを書き出した直後、`verifyPortableWrappedDekForExport()` を実行する。\n- 検証は `unwrapPortable()` のみを用い、保存済みportable wrapから回収したDEKと元DEKを `MessageDigest.isEqual()` で比較する。Android Keystoreは参照しない。\n- 続けて既存 `selfTestPortable()` が同じportable wrapからDEKを復号し、暗号化payloadをAES-GCM復号してplain bundle SHA-256一致まで検証する。\n- 両検証に成功した後だけportable packageを外部 `OutputStream` へ出力する。\n- `importPortable()` はportable wrapで復号→payload検証を済ませた後、移行先端末の新しいKeystore wrapを生成する。元端末Keystoreは不要。\n- `DataProtectionManager.importBackup()` はportable import後にarchive/DB/content整合性を再検証し、DB内マスター・`sync_outbox`、BKP-003 content bundleの復元経路へ接続する。\n\n## 自動検証\n\n`V149Bkp011PortableKeyDecryptionTest` で、portable package生成→保存ラップ鍵DEK復号証明→payload復号証明→外部出力の順序、Keystore非依存、予備端末側の再ラップ順序、完全復元経路を回帰固定する。\n\n## 実機未確認\n\n- 実際の別Android端末（元端末Keystoreを持たない端末）へportable packageを移し、同じパスフレーズでSAF取込→preflight→DB・設定・マスター・未送信キュー復元まで完走すること。\n- USB/共有ストレージprovider上で作成したpackageを実媒体から読み戻した場合の復号。\n''')
