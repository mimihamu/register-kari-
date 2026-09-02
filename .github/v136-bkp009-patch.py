from pathlib import Path

root = Path('.')
activity = root / 'app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt'
main_dir = root / 'app/src/main/java/jp/co/tenposinfo/register'
test_dir = root / 'app/src/test/java/jp/co/tenposinfo/register'
docs_dir = root / 'docs'

s = activity.read_text(encoding='utf-8')
old = '''    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val fileName = pendingExport
        pendingExport = null
        if (uri != null && fileName != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.exportBackup(fileName, output, actor, chars)
                    } ?: error("保存先を開けません")
                }
                withContext(Dispatchers.IO) { metadataStore.registerExport(result) }
                "外部保存完了: portable暗号化済み / ${result.fileName} / ${result.bytesWritten} bytes"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val imported = withContext(Dispatchers.IO) {
                    val record = context.contentResolver.openInputStream(uri)?.use { input ->
                        manager.importBackup(input, actor, chars)
                    } ?: error("取込ファイルを開けません")
                    metadataStore.registerManualBackup(manager.verifyBackup(record.fileName))
                    record
                }
                selected = imported.fileName
                "外部バックアップ取込完了: パスフレーズ復号検証済み / ${imported.fileName}"
            }
        }
    }
'''
new = '''    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val fileName = pendingExport
        pendingExport = null
        if (uri == null || fileName == null) {
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            message = "外部保存をキャンセルしました"
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val result = withContext(Dispatchers.IO) {
                    BackupSafAccessV147.guard("外部バックアップ保存") {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            manager.exportBackup(fileName, output, actor, chars)
                        } ?: error("保存先を開けません")
                    }
                }
                withContext(Dispatchers.IO) { metadataStore.registerExport(result) }
                "外部保存完了: portable暗号化済み / ${result.fileName} / ${result.bytesWritten} bytes"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            message = "外部バックアップ取込をキャンセルしました"
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val chars = backupPassphrase.toCharArray()
            backupPassphrase = ""
            backupPassphraseConfirm = ""
            runTask {
                val actor = OperatorSessionRegistry.current(appContext)?.name ?: "責任者"
                val imported = withContext(Dispatchers.IO) {
                    BackupSafAccessV147.guard("外部バックアップ取込") {
                        val record = context.contentResolver.openInputStream(uri)?.use { input ->
                            manager.importBackup(input, actor, chars)
                        } ?: error("取込ファイルを開けません")
                        metadataStore.registerManualBackup(manager.verifyBackup(record.fileName))
                        record
                    }
                }
                selected = imported.fileName
                "外部バックアップ取込完了: パスフレーズ復号検証済み / ${imported.fileName}"
            }
        }
    }
'''
if old not in s:
    raise SystemExit('BKP-009 launcher block not found')
s = s.replace(old, new, 1)
activity.write_text(s, encoding='utf-8')

(main_dir / 'BackupSafAccessV147.kt').write_text(r'''package jp.co.tenposinfo.register

import java.io.FileNotFoundException
import java.io.IOException

enum class BackupSafFailureCategoryV147 {
    PERMISSION_DENIED,
    MEDIA_OR_DOCUMENT_UNAVAILABLE,
    IO_FAILURE,
}

/** BKP-009: turn SAF provider failures into user-visible errors instead of app crashes. */
object BackupSafAccessV147 {
    fun classify(error: Throwable): BackupSafFailureCategoryV147? = when (error) {
        is SecurityException -> BackupSafFailureCategoryV147.PERMISSION_DENIED
        is FileNotFoundException -> BackupSafFailureCategoryV147.MEDIA_OR_DOCUMENT_UNAVAILABLE
        is IOException -> BackupSafFailureCategoryV147.IO_FAILURE
        else -> null
    }

    fun userMessage(operation: String, error: Throwable): String? = when (classify(error)) {
        BackupSafFailureCategoryV147.PERMISSION_DENIED ->
            "$operation: 保存先または取込元へのアクセスが拒否されました"
        BackupSafFailureCategoryV147.MEDIA_OR_DOCUMENT_UNAVAILABLE ->
            "$operation: USB等の媒体が取り外されたか、ファイルを開けません"
        BackupSafFailureCategoryV147.IO_FAILURE ->
            "$operation: 外部媒体との入出力に失敗しました。接続状態と空き容量を確認してください"
        null -> null
    }

    fun <T> guard(operation: String, block: () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        throw IllegalStateException(requireNotNull(userMessage(operation, error)), error)
    } catch (error: FileNotFoundException) {
        throw IllegalStateException(requireNotNull(userMessage(operation, error)), error)
    } catch (error: IOException) {
        throw IllegalStateException(requireNotNull(userMessage(operation, error)), error)
    }
}
''', encoding='utf-8')

(test_dir / 'V147Bkp009ManualExportTest.kt').write_text(r'''package jp.co.tenposinfo.register

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V147Bkp009ManualExportTest {
    private val appRoot = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    @Test
    fun safFailuresAreClassifiedWithoutBroadStorageFallback() {
        assertEquals(
            BackupSafFailureCategoryV147.PERMISSION_DENIED,
            BackupSafAccessV147.classify(SecurityException("denied")),
        )
        assertEquals(
            BackupSafFailureCategoryV147.MEDIA_OR_DOCUMENT_UNAVAILABLE,
            BackupSafAccessV147.classify(FileNotFoundException("removed")),
        )
        assertEquals(
            BackupSafFailureCategoryV147.IO_FAILURE,
            BackupSafAccessV147.classify(IOException("io")),
        )
        assertNull(BackupSafAccessV147.classify(IllegalArgumentException("bad package")))
        assertTrue(
            BackupSafAccessV147.userMessage("保存", FileNotFoundException("removed"))!!
                .contains("媒体が取り外された"),
        )
    }

    @Test
    fun guardConvertsExpectedSafFailureToUserSafeError() {
        val error = runCatching {
            BackupSafAccessV147.guard("外部バックアップ保存") {
                throw SecurityException("provider denied")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("アクセスが拒否"))
    }

    @Test
    fun sourceUsesSafStreamsPortableKeyAndNoBroadStoragePermission() {
        fun mainSource(name: String) = File(appRoot, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val activity = mainSource("DataProtectionActivity.kt")
        val protection = mainSource("DataProtection.kt")
        val envelope = mainSource("BackupEnvelopeV136.kt")
        val manifest = File(appRoot, "src/main/AndroidManifest.xml").readText()

        assertTrue(activity.contains("ActivityResultContracts.CreateDocument"))
        assertTrue(activity.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(activity.contains("contentResolver.openOutputStream(uri"))
        assertTrue(activity.contains("contentResolver.openInputStream(uri"))
        assertTrue(activity.contains("takePersistableUriPermission"))
        assertTrue(activity.contains("BackupSafAccessV147.guard(\"外部バックアップ保存\")"))
        assertTrue(activity.contains("BackupSafAccessV147.guard(\"外部バックアップ取込\")"))
        assertTrue(activity.contains("外部保存をキャンセルしました"))
        assertTrue(activity.contains("外部バックアップ取込をキャンセルしました"))
        assertFalse(activity.contains("File(uri.path"))
        assertFalse(activity.contains("Environment.getExternalStorageDirectory"))

        assertTrue(protection.contains("fun exportBackup(fileName: String, output: OutputStream"))
        assertTrue(protection.contains("fun importBackup(input: InputStream"))
        assertTrue(protection.contains("BackupEnvelopeV136.exportPortable"))
        assertTrue(protection.contains("BackupEnvelopeV136.importPortable"))
        assertTrue(envelope.contains("PortableKeyV136.create(passphrase, dek)"))
        assertTrue(envelope.contains("PortableKeyV136.unwrap"))

        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
    }
}
''', encoding='utf-8')

(docs_dir / 'V1.36_BKP_009_MANUAL_EXPORT.md').write_text(r'''# v1.36 BKP-009 手動エクスポート

正式仕様 v2.5 `BKP-009` を正本とする。

## 正式要件

端末内バックアップをAndroid Storage Access Framework (SAF) で利用者が選択したUSB・共有ストレージ等へ手動出力する。外部絶対パスや全ファイルアクセス権限へ依存せず、`content://` URIを `ContentResolver` のstreamとして扱う。予備端末でも復号できるよう、portable packageには管理者パスフレーズ由来鍵でラップしたDEKを含める。別端末ではSAFで選択してパスフレーズを入力し、復号・検証後に復元へ進めること。権限拒否や媒体取り外しではクラッシュしないこと。

## 既存実装との突合

- `CreateDocument` / `OpenDocument` によりSAFを使用する。
- `ContentResolver.openOutputStream()` / `openInputStream()` を通して `DataProtectionManager.exportBackup()` / `importBackup()` へstreamを渡す。
- `ArchiveCipherV136.exportPortable()` は端末Keystoreで保護されたlocal DEKを一時展開し、`PortableKeyV136.create()` によりPBKDF2-HMAC-SHA256由来KEK + AES-GCMでDEKを再ラップする。
- `ArchiveCipherV136.importPortable()` はportable wrapped key + パスフレーズからDEKを復元するため、元端末のAndroid Keystoreを必要としない。
- `AndroidManifest.xml` は `MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` を要求しない。
- 平文SQLiteを外部へ直接書き出さない。

## v1.36追加安全化

`BackupSafAccessV147` を追加し、SAF providerの `SecurityException`、`FileNotFoundException`、`IOException` を利用者向けエラーへ変換する。USB媒体取り外し・content permission拒否等を通常エラーとして処理し、アプリクラッシュへ波及させない。ファイルpickerキャンセル時はパスフレーズ入力を破棄し、キャンセルを画面へ表示する。

## 自動検証

`V147Bkp009ManualExportTest` でSAF stream契約、portable key経路、権限拒否/媒体取り外し分類、pickerキャンセル、broad storage permission不使用、外部絶対パスfallback不使用を固定する。

## 実機未確認

- USBメモリをSAF保存先に選び、保存中に媒体を取り外して非クラッシュかつ明示エラーとなること。
- providerの書込/読込権限を拒否した場合の表示。
- 実際の別Android端末へportable packageを移し、同じパスフレーズでSAF取込→復号検証→復元preflight→復元完了まで通ること。
- USB/Google Drive/端末ファイルproviderごとの実機互換性。
''', encoding='utf-8')
