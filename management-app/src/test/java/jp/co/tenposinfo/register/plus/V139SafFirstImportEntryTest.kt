package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V139SafFirstImportEntryTest {
    @Test fun folderFirstApiAdvancedAndImportSourceRetained() {
        val screen = File("src/main/java/jp/co/tenposinfo/register/plus/ManagementFolderSyncScreen.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/plus/MainActivity.kt").readText()
        val source = File("src/main/java/jp/co/tenposinfo/register/plus/ImportFolderSource.kt").readText()
        assertTrue(screen.contains("Text(\"フォルダ登録\")"))
        assertTrue(screen.contains("Text(if (folderState.scanning) \"確認中…\" else \"差分取込\")"))
        assertTrue(screen.contains("if (expanded) {"))
        assertTrue(screen.contains("Googleかんたん接続（API方式・詳細）"))
        assertTrue(screen.contains("ActivityResultContracts.OpenDocumentTree()"))
        assertTrue(activity.contains("documentSource.persistFolderPermission(uri)"))
        assertTrue(source.contains("takePersistableUriPermission"))
        assertTrue(source.contains("MAX_JSON_DOCUMENTS"))
    }
}
