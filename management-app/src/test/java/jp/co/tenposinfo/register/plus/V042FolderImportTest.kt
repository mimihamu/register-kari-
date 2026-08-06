package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V042FolderImportTest {
    @Test
    fun folderPolicyRecognizesJsonAndContentChanges() {
        assertTrue(ImportFolderPolicy.isJsonDocument("sale-1.json", "application/octet-stream"))
        assertTrue(ImportFolderPolicy.isJsonDocument("SALE-2.JSON", null))
        assertTrue(ImportFolderPolicy.isJsonDocument("payload", "application/json"))
        assertFalse(ImportFolderPolicy.isJsonDocument("receipt.pdf", "application/pdf"))

        assertFalse(
            ImportFolderPolicy.shouldProcess(
                knownContentSha256 = "same",
                currentContentSha256 = "same",
                forceRescan = false,
            ),
        )
        assertTrue(
            ImportFolderPolicy.shouldProcess(
                knownContentSha256 = "old",
                currentContentSha256 = "new",
                forceRescan = false,
            ),
        )
        assertTrue(
            ImportFolderPolicy.shouldProcess(
                knownContentSha256 = "same",
                currentContentSha256 = "same",
                forceRescan = true,
            ),
        )

        val first = ImportFolderPolicy.sha256("{}".toByteArray())
        val second = ImportFolderPolicy.sha256("{}".toByteArray())
        val changed = ImportFolderPolicy.sha256("{\"a\":1}".toByteArray())
        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun folderSourceDatabaseUiDocsAndWorkflowStayConnected() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val source = File(sourceRoot, "ImportFolderSource.kt").readText()
        val folderRepository = File(sourceRoot, "FolderImportRepository.kt").readText()
        val database = File(sourceRoot, "ManagementDatabase.kt").readText()
        val activity = File(sourceRoot, "MainActivity.kt").readText()
        val folderScreen = File(sourceRoot, "ManagementFolderSyncScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val plusBuild = File("build.gradle.kts").readText()
        val registerBuild = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.42_TSUGUREGI_PLUS_FOLDER_IMPORT.md").readText()
        val notes = File(root, "docs/V0.42_RELEASE_NOTES.md").readText()

        for (token in listOf(
            "takePersistableUriPermission",
            "releasePersistableUriPermission",
            "DocumentsContract.buildChildDocumentsUriUsingTree",
            "MAX_RECURSION_DEPTH = 12",
            "MAX_JSON_DOCUMENTS = 5_000",
            "contentSha256",
            "forceRescan",
        )) assertTrue(source.contains(token))

        assertTrue(folderRepository.contains("fun knownFingerprints"))
        assertTrue(folderRepository.contains("fun recordProcessedFiles"))
        assertTrue(folderRepository.contains("SQLiteDatabase.CONFLICT_REPLACE"))
        assertTrue(database.contains("CREATE TABLE IF NOT EXISTS folder_import_files"))
        assertTrue(database.contains("DATABASE_VERSION = 4"))
        assertTrue(database.contains("idx_folder_import_files_tree"))

        assertTrue(activity.contains("TsuguRegiPlusFolderSyncScreen"))
        assertTrue(activity.contains("importRegisteredFolder"))
        assertTrue(activity.contains("差分はありませんでした"))
        assertTrue(folderScreen.contains("差分取込"))
        assertTrue(folderScreen.contains("全件再確認"))
        assertTrue(folderScreen.contains("取込フォルダ未登録"))
        assertTrue(folderScreen.contains("ActivityResultContracts.OpenDocumentTree"))
        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))

        assertTrue(registerBuild.contains("versionCode = 78"))
        assertTrue(registerBuild.contains("versionName = \"0.48.0-dev.1\""))
        assertTrue(plusBuild.contains("versionCode = 7"))
        assertTrue(plusBuild.contains("versionName = \"0.7.0-dev.1\""))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:assembleDebug"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.8.0_dev1_drive_setup_diagnostics_debug.apk"))
        assertTrue(docs.contains("内容SHA-256"))
        assertTrue(docs.contains("全件再確認"))
        assertTrue(notes.contains("取込フォルダ"))
        assertFalse(File(root, "tools/v042_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v042-apply.yml").exists())
    }
}
