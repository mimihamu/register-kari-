from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'PATCH_MISS: {label}')
    if text.count(old) != 1:
        raise SystemExit(f'PATCH_AMBIGUOUS: {label} count={text.count(old)}')
    return text.replace(old, new, 1)

# The automatic external-backup subsystem intentionally mirrors the already encrypted local V2
# envelope. It must not require an interactive administrator passphrase. Manual SAF export remains
# portable/passphrase protected through exportBackup().
p = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt'
s = p.read_text()
anchor = '''    /**
     * Export is always portable. The local archive remains device-Keystore protected while the
     * exported copy receives a second DEK wrap derived from the administrator passphrase.
     */
    fun exportBackup(fileName: String, output: OutputStream, actorName: String, passphrase: CharArray): BackupExportResult {'''
replacement = '''    /**
     * Non-interactive automatic external backup compatibility path.
     * The bytes copied here are the already AES-256-GCM encrypted local V2 envelope; no plaintext
     * SQLite database leaves app-private storage. This copy is device-Keystore recoverable. Manual
     * cross-device export uses exportBackup(..., passphrase) below and adds the portable key wrap.
     */
    fun copyVerifiedBackup(fileName: String, output: OutputStream): BackupExportResult {
        val verification = verifyBackup(fileName)
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(BackupEnvelopeV136.isSecureEnvelope(archive)) { "暗号化されていないバックアップは外部自動保存できません" }
        val written = archive.inputStream().buffered().use { input ->
            BackupTransferPolicy.copyWithLimit(input, output, MAX_BACKUP_ARCHIVE_BYTES)
        }
        require(written == archive.length()) { "暗号化バックアップの外部出力サイズが一致しません" }
        return BackupExportResult(verification.fileName, written, verification.manifest)
    }

    /**
     * Manual export is always portable. The local archive remains device-Keystore protected while
     * the exported copy receives a second DEK wrap derived from the administrator passphrase.
     */
    fun exportBackup(fileName: String, output: OutputStream, actorName: String, passphrase: CharArray): BackupExportResult {'''
s = replace_once(s, anchor, replacement, 'encrypted automatic-copy compatibility')
p.write_text(s)

# SAF grants are requested persistently when the provider supports them. Failure to persist is not
# fatal because some document providers grant only the immediate activity-result access.
p = ROOT / 'app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt'
s = p.read_text()
s = replace_once(
    s,
    '''        if (uri != null && fileName != null) {
            val chars = backupPassphrase.toCharArray()''',
    '''        if (uri != null && fileName != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()''',
    'persist export SAF permission',
)
s = replace_once(
    s,
    '''        if (uri != null) {
            val chars = backupPassphrase.toCharArray()''',
    '''        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()''',
    'persist import SAF permission',
)
p.write_text(s)

print('COMPAT_PATCH_OK')
