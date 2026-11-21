package jp.co.tenposinfo.register

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
        assertTrue(envelope.contains("derivePortableKey(passphrase, salt, PBKDF2_ITERATIONS)"))
        assertTrue(envelope.contains("wrapRaw(dek, kek)"))
        assertTrue(envelope.contains("unwrapPortable(manifest, passphrase)"))

        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
    }
}
