package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V039ManagementImportIntegrationTest {
    @Test
    fun moduleDatabaseUiWorkflowAndDocsStayConnected() {
        val root = File("..")
        val settings = File(root, "settings.gradle.kts").readText()
        val appBuild = File(root, "app/build.gradle.kts").readText()
        val plusBuild = File("build.gradle.kts").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val contract = File(sourceRoot, "SalesJournalImportContract.kt").readText()
        val database = File(sourceRoot, "ManagementDatabase.kt").readText()
        val repository = File(sourceRoot, "SalesJournalImportRepository.kt").readText()
        val activity = File(sourceRoot, "MainActivity.kt").readText()
        val mobileScreen = File(sourceRoot, "ManagementMobileScreen.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.39_TSUGUREGI_PLUS_IMPORT.md").readText()
        val notes = File(root, "docs/V0.39_RELEASE_NOTES.md").readText()

        assertTrue(settings.contains("include(\":management-app\")"))
        assertTrue(appBuild.contains("versionCode = 73"))
        assertTrue(appBuild.contains("versionName = \"0.43.0-dev.1\""))
        assertTrue(plusBuild.contains("applicationId = \"jp.co.tenposinfo.register.plus\""))
        assertTrue(plusBuild.contains("versionName = \"0.5.0-dev.1\""))
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))

        for (token in listOf(
            SalesJournalImportContract.SCHEMA,
            "duplicateImportKey",
            "supportedEventTypes",
            "supportedPayloadSchemas",
            "PAYLOAD_SCHEMA_MISMATCH",
        )) assertTrue(contract.contains(token))

        assertTrue(database.contains("CREATE TABLE import_runs"))
        assertTrue(database.contains("CREATE TABLE imported_journal"))
        assertTrue(database.contains("CREATE TABLE import_rejections"))
        assertTrue(repository.contains("SQLiteDatabase.CONFLICT_IGNORE"))
        assertTrue(repository.contains("fun importDocuments"))
        assertTrue(repository.contains("fun recentRejections"))
        assertTrue(activity.contains("TsuguRegiPlusFolderSyncScreen"))
        assertTrue(mobileScreen.contains("ActivityResultContracts.OpenMultipleDocuments"))
        assertTrue(mobileScreen.contains("JSON取込"))
        assertTrue(mobileScreen.contains("重複"))
        assertTrue(mobileScreen.contains("隔離データ"))

        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:assembleDebug"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.5.0_dev1"))
        assertTrue(docs.contains("重複取込"))
        assertTrue(docs.contains("不正データ隔離"))
        assertTrue(notes.contains("つぐレジ＋"))
        assertFalse(File(root, "tools/v039_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v039-apply.yml").exists())
    }
}
