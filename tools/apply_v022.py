from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt"
ACTIVITY = ROOT / "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt"
TEST = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V022BackupPortabilityTest.kt"
DOC = ROOT / "docs/V0.22_BACKUP_PORTABILITY.md"
BUILD = ROOT / "app/build.gradle.kts"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    DATA,
    "import java.io.FileOutputStream\n",
    "import java.io.FileOutputStream\nimport java.io.InputStream\nimport java.io.OutputStream\n",
    "stream imports",
)
replace_once(
    DATA,
    "private const val MAX_BACKUP_DATABASE_BYTES = 512L * 1024L * 1024L\n",
    "private const val MAX_BACKUP_DATABASE_BYTES = 512L * 1024L * 1024L\nprivate const val MAX_BACKUP_ARCHIVE_BYTES = 600L * 1024L * 1024L\n",
    "archive size limit",
)
replace_once(
    DATA,
    '''object BackupFilePolicy {
    private val safeName = Regex("[A-Za-z0-9._-]+\\\\.tgbak")

    fun requireSafe(fileName: String): String {
        require(fileName.matches(safeName) && !fileName.contains("..")) { "バックアップ名が不正です" }
        return fileName
    }
}
''',
    '''object BackupFilePolicy {
    private val safeName = Regex("[A-Za-z0-9._-]+\\\\.tgbak")

    fun requireSafe(fileName: String): String {
        require(fileName.matches(safeName) && !fileName.contains("..")) { "バックアップ名が不正です" }
        return fileName
    }
}

data class BackupExportResult(
    val fileName: String,
    val bytesWritten: Long,
    val manifest: BackupManifest,
)

object BackupImportNamePolicy {
    fun canonical(manifest: BackupManifest): String = BackupFilePolicy.requireSafe(
        "TSUGUREGI_import_${manifest.createdAt}_${manifest.databaseSha256.take(16)}.tgbak",
    )
}

object BackupTransferPolicy {
    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes > 0) { "最大サイズが不正です" }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "バックアップファイルが上限サイズを超えています" }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }
}
''',
    "backup transfer policies",
)

old_verify = '''    fun verifyBackup(fileName: String): BackupVerification {
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(archive.isFile) { "バックアップが見つかりません" }
        val extractionDir = File(appContext.cacheDir, "verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = readManifest(archive)
            val database = extractDatabase(archive, extractionDir)
            require(database.length() in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
            require(sha256(database) == manifest.databaseSha256) { "バックアップDBのSHA-256が一致しません" }
            val currentUserVersion = RegisterDatabase(appContext).use { helper ->
                helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            require(manifest.databaseUserVersion <= currentUserVersion) { "このアプリより新しいDB版のバックアップは復元できません" }
            val report = inspectDatabaseFile(database)
            require(report.healthy) { "バックアップDBの整合性検査に失敗しました" }
            return BackupVerification(archive.name, manifest, database.length(), report)
        } finally {
            extractionDir.deleteRecursively()
        }
    }
'''
new_verify = '''    fun exportBackup(fileName: String, output: OutputStream, actorName: String): BackupExportResult {
        val verification = verifyBackup(fileName)
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        val written = archive.inputStream().buffered().use { input ->
            BackupTransferPolicy.copyWithLimit(input, output, MAX_BACKUP_ARCHIVE_BYTES)
        }
        require(written == archive.length()) { "バックアップの外部出力サイズが一致しません" }
        recordAudit("DATA_BACKUP_EXPORTED", "${verification.fileName} / $written bytes", actorName)
        return BackupExportResult(verification.fileName, written, verification.manifest)
    }

    fun importBackup(input: InputStream, actorName: String): BackupRecord {
        val temporary = File(appContext.cacheDir, "backup-import-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().buffered().use { output ->
                val copied = BackupTransferPolicy.copyWithLimit(input, output, MAX_BACKUP_ARCHIVE_BYTES)
                require(copied > 0L) { "取込ファイルが空です" }
            }
            val verification = verifyArchive(temporary, "external-import.tgbak")
            val targetName = BackupImportNamePolicy.canonical(verification.manifest)
            val target = File(backupDir, targetName)
            if (target.exists()) {
                val existing = verifyArchive(target, target.name)
                require(existing.manifest == verification.manifest) { "同名の異なるバックアップが存在します" }
                recordAudit("DATA_BACKUP_IMPORTED", "$targetName / 既存バックアップと同一", actorName)
                return BackupRecord(target.name, target.length(), existing.manifest.createdAt, true, existing.manifest.appVersion, existing.manifest.databaseUserVersion)
            }
            atomicReplace(temporary, target)
            val committed = verifyBackup(target.name)
            recordAudit("DATA_BACKUP_IMPORTED", "${target.name} / ${committed.manifest.databaseSha256}", actorName)
            return BackupRecord(target.name, target.length(), committed.manifest.createdAt, true, committed.manifest.appVersion, committed.manifest.databaseUserVersion)
        } finally {
            temporary.delete()
        }
    }

    fun verifyBackup(fileName: String): BackupVerification {
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(archive.isFile) { "バックアップが見つかりません" }
        return verifyArchive(archive, archive.name)
    }

    private fun verifyArchive(archive: File, displayName: String): BackupVerification {
        require(archive.isFile && archive.length() in 1..MAX_BACKUP_ARCHIVE_BYTES) { "バックアップアーカイブのサイズが不正です" }
        val extractionDir = File(appContext.cacheDir, "verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = readManifest(archive)
            val database = extractDatabase(archive, extractionDir)
            require(database.length() in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
            require(sha256(database) == manifest.databaseSha256) { "バックアップDBのSHA-256が一致しません" }
            val currentUserVersion = RegisterDatabase(appContext).use { helper ->
                helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            require(manifest.databaseUserVersion <= currentUserVersion) { "このアプリより新しいDB版のバックアップは復元できません" }
            val report = inspectDatabaseFile(database)
            require(report.healthy) { "バックアップDBの整合性検査に失敗しました" }
            return BackupVerification(displayName, manifest, database.length(), report)
        } finally {
            extractionDir.deleteRecursively()
        }
    }
'''
replace_once(DATA, old_verify, new_verify, "export import and verify")

replace_once(
    ACTIVITY,
    "import android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent\n",
    "import android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.compose.setContent\nimport androidx.activity.result.contract.ActivityResultContracts\n",
    "activity result imports",
)
replace_once(
    ACTIVITY,
    '''    fun runTask(task: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = runCatching { task() }.getOrElse { "エラー: ${it.message}" }
            backups = withContext(Dispatchers.IO) { manager.listBackups() }
            pending = manager.pendingRestoreStatus()
            busy = false
        }
    }

    LaunchedEffect(Unit) {''',
    '''    fun runTask(task: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = runCatching { task() }.getOrElse { "エラー: ${it.message}" }
            backups = withContext(Dispatchers.IO) { manager.listBackups() }
            pending = manager.pendingRestoreStatus()
            busy = false
        }
    }

    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val fileName = pendingExport
        pendingExport = null
        if (uri != null && fileName != null) {
            runTask {
                val actor = OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者"
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.exportBackup(fileName, output, actor)
                    } ?: error("保存先を開けません")
                }
                "外部保存完了: ${result.fileName} / ${result.bytesWritten} bytes"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runTask {
                val actor = OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者"
                val imported = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        manager.importBackup(input, actor)
                    } ?: error("取込ファイルを開けません")
                }
                selected = imported.fileName
                "外部バックアップ取込完了: ${imported.fileName}"
            }
        }
    }

    LaunchedEffect(Unit) {''',
    "storage access launchers",
)
replace_once(
    ACTIVITY,
    '''                        if (pending.staged) Text("復元予約済み: ${pending.backupFileName}\\nアプリを完全終了して再起動すると適用します。", color = DpDanger, fontWeight = FontWeight.Bold)
                        pending.lastResult?.let { Text(it, color = Color.DarkGray, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(pin,''',
    '''                        if (pending.staged) Text("復元予約済み: ${pending.backupFileName}\\nアプリを完全終了して再起動すると適用します。", color = DpDanger, fontWeight = FontWeight.Bold)
                        pending.lastResult?.let { Text(it, color = Color.DarkGray, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val file = selected ?: return@Button
                                    pendingExport = file
                                    exportLauncher.launch(file)
                                },
                                enabled = !busy && selected != null,
                                colors = ButtonDefaults.buttonColors(containerColor = DpBlue),
                            ) { Text("外部へ保存") }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed")) },
                                enabled = !busy && !pending.staged,
                            ) { Text("外部から取込") }
                            Text("Google Drive・USB・端末フォルダを選択できます", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(pin,''',
    "external backup controls",
)

TEST.write_text(
    '''package jp.co.tenposinfo.register

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
''',
    encoding="utf-8",
)

DOC.write_text(
    '''# つぐレジ v0.22 バックアップ外部保存・取込

## 目的

v0.21の内部バックアップだけでは、端末故障やアプリ削除時にバックアップも失われる。Android Storage Access Frameworkを使用し、Google Drive、USBストレージ、端末フォルダへ`.tgbak`を保存・取込できるようにする。

## 外部保存

- 内部バックアップを再検証してから出力する。
- Android標準の保存先選択画面を使用する。
- 専用の外部ストレージ権限は要求しない。
- 出力バイト数を元ファイルサイズと照合する。
- `DATA_BACKUP_EXPORTED`を監査記録する。

## 外部取込

- ContentResolverから一時ファイルへストリームコピーする。
- 最大600MiBを超える入力を拒否する。
- ZIP内は`manifest.properties`と`register.db`の2ファイルだけを許可する。
- DBサイズ、DB SHA-256、DB版、SQLite整合性、業務整合性を検査する。
- 検証後に内部バックアップ名へ原子的に確定する。
- 外部の表示名やパスを内部ファイル名に使用せず、作成日時とDB SHA-256から安全な名称を生成する。
- `DATA_BACKUP_IMPORTED`を監査記録する。

## 復元との関係

取込は内部バックアップライブラリへの登録だけを行う。DB復元はv0.21と同様、責任者PIN・営業終了・未処理データなしの条件を満たし、次回起動時にロールバック付きで実行する。
''',
    encoding="utf-8",
)

replace_once(BUILD, '        versionCode = 51\n', '        versionCode = 52\n', "version code")
replace_once(BUILD, '        versionName = "0.21.0-dev.1"\n', '        versionName = "0.22.0-dev.1"\n', "version name")
