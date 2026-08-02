from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def path(name: str) -> Path:
    return ROOT / name


def read(name: str) -> str:
    return path(name).read_text(encoding="utf-8")


def write(name: str, text: str) -> None:
    path(name).parent.mkdir(parents=True, exist_ok=True)
    path(name).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0:
        if new in text:
            return text
        raise RuntimeError(f"{label}: target not found")
    if count != 1:
        raise RuntimeError(f"{label}: target count={count}")
    return text.replace(old, new, 1)


# Version
build_file = "app/build.gradle.kts"
text = read(build_file)
text = replace_once(text, 'versionCode = 57', 'versionCode = 58', 'versionCode')
text = replace_once(text, 'versionName = "0.27.0-dev.1"', 'versionName = "0.28.0-dev.1"', 'versionName')
write(build_file, text)

# Fixed system chrome shared by every activity.
write(
    "app/src/main/java/jp/co/tenposinfo/register/RegisterUiChromeV028.kt",
    '''package jp.co.tenposinfo.register

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowCompat

object RegisterUiChromeV028 {
    const val TOP_BAR_ARGB: Long = 0xFF173F6B
    const val TOP_BAR_HEIGHT_DP: Int = 62
}

@Suppress("DEPRECATION")
fun configureRegisterSystemBars(window: Window) {
    window.statusBarColor = Color.rgb(23, 63, 107)
    window.navigationBarColor = Color.WHITE
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = true
    }
}
''',
)

# Remove the Android View overlays that caused the buttons to cover Compose cards.
provider_file = "app/src/main/java/jp/co/tenposinfo/register/CatalogBootstrapProvider.kt"
text = read(provider_file)
for line in (
    "import android.content.Intent\n",
    "import android.graphics.Color\n",
    "import android.view.Gravity\n",
    "import android.view.View\n",
    "import android.view.ViewGroup\n",
    "import android.widget.Button\n",
    "import android.widget.FrameLayout\n",
):
    text = text.replace(line, "")
text = replace_once(
    text,
    '''    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is MainActivity -> {
                synchronizeAndRefresh(activity)
            }
            is CatalogSettingsActivity -> {
                guardSettingsActivity(activity)
                installDynamicCatalogButton(activity)
            }
            is DynamicCatalogSettingsActivity -> {
                guardSettingsActivity(activity)
                installRevisionEditorButton(activity)
                installTaxInvoiceButton(activity)
                installSyncButton(activity)
            }
            is MenuRevisionEditorActivity, is SyncSettingsActivity, is TaxInvoiceSettingsActivity -> guardSettingsActivity(activity)
        }
    }
''',
    '''    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is MainActivity -> synchronizeAndRefresh(activity)
            is CatalogSettingsActivity,
            is DynamicCatalogSettingsActivity,
            is MenuRevisionEditorActivity,
            is SyncSettingsActivity,
            is TaxInvoiceSettingsActivity,
            -> guardSettingsActivity(activity)
        }
    }
''',
    "lifecycle dispatch",
)
text, removed = re.subn(
    r"\n    private fun installCatalogButton\(activity: MainActivity\) \{.*?\n    private fun guardSettingsActivity",
    "\n    private fun guardSettingsActivity",
    text,
    flags=re.S,
)
if removed != 1 and "installDynamicCatalogButton" in text:
    raise RuntimeError(f"overlay removal count={removed}")
text = re.sub(
    r"\n    private fun dp\(activity: Activity, value: Int\): Int =.*?\n    companion object \{.*?\n    \}\n",
    "\n",
    text,
    flags=re.S,
)
write(provider_file, text)

# Catalog navigation in normal Compose flow.
catalog_file = "app/src/main/java/jp/co/tenposinfo/register/CatalogSettingsActivity.kt"
text = read(catalog_file)
if "import android.content.Intent" not in text:
    text = text.replace("import android.os.Bundle\n", "import android.content.Intent\nimport android.os.Bundle\n", 1)
if "configureRegisterSystemBars(window)" not in text:
    text = text.replace("        super.onCreate(savedInstanceState)\n", "        super.onCreate(savedInstanceState)\n        configureRegisterSystemBars(window)\n", 1)
text = replace_once(
    text,
    "                onProfiles = { message = null; screen = CatalogScreen.PROFILES },\n                onClose = onClose,",
    "                onProfiles = { message = null; screen = CatalogScreen.PROFILES },\n                onDynamic = { context.startActivity(Intent(context, DynamicCatalogSettingsActivity::class.java)) },\n                onClose = onClose,",
    "catalog callback",
)
text = replace_once(
    text,
    "    onProfiles: () -> Unit,\n    onClose: () -> Unit,",
    "    onProfiles: () -> Unit,\n    onDynamic: () -> Unit,\n    onClose: () -> Unit,",
    "catalog parameter",
)
marker = '                SummaryCard("現在の販売プロファイル", activeProfile?.name ?: "未設定", Modifier.weight(1.4f))\n            }\n'
text = replace_once(
    text,
    marker,
    marker
    + '''            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = onDynamic,
                    modifier = Modifier.width(240.dp).height(46.dp),
                ) { Text("任意税率・メニュー改定", fontWeight = FontWeight.Bold) }
            }
''',
    "catalog navigation",
)
text = text.replace('Text("REGISTER"', 'Text("つぐレジ"')
write(catalog_file, text)

# Dynamic catalog shortcuts in normal Compose flow.
dynamic_file = "app/src/main/java/jp/co/tenposinfo/register/DynamicCatalogSettingsActivity.kt"
text = read(dynamic_file)
if "import android.content.Intent" not in text:
    text = text.replace("import android.os.Bundle\n", "import android.content.Intent\nimport android.os.Bundle\n", 1)
if "configureRegisterSystemBars(window)" not in text:
    text = text.replace("        super.onCreate(savedInstanceState)\n", "        super.onCreate(savedInstanceState)\n        configureRegisterSystemBars(window)\n", 1)
text = replace_once(
    text,
    "                onRevisions = { message = null; screen = DynamicCatalogScreen.REVISIONS },\n                onClose = onClose,",
    "                onRevisions = { message = null; screen = DynamicCatalogScreen.REVISIONS },\n                onTaxInvoice = { context.startActivity(Intent(context, TaxInvoiceSettingsActivity::class.java)) },\n                onRevisionEditor = { context.startActivity(Intent(context, MenuRevisionEditorActivity::class.java)) },\n                onSync = { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) },\n                onClose = onClose,",
    "dynamic callbacks",
)
text = replace_once(
    text,
    "    onRevisions: () -> Unit,\n    onClose: () -> Unit,",
    "    onRevisions: () -> Unit,\n    onTaxInvoice: () -> Unit,\n    onRevisionEditor: () -> Unit,\n    onSync: () -> Unit,\n    onClose: () -> Unit,",
    "dynamic parameters",
)
marker = "        Column(Modifier.fillMaxSize().padding(26.dp)) {\n"
text = replace_once(
    text,
    marker,
    marker
    + '''            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onTaxInvoice, modifier = Modifier.weight(1f).height(46.dp)) {
                    Text("税・インボイス", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onRevisionEditor, modifier = Modifier.weight(1f).height(46.dp)) {
                    Text("改定内容編集", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onSync, modifier = Modifier.weight(1f).height(46.dp)) {
                    Text("同期基盤", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
''',
    "dynamic navigation",
)
text = text.replace('Text("REGISTER"', 'Text("つぐレジ"')
write(dynamic_file, text)

# Larger, evenly-sized keypad buttons.
main_file = "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
text = read(main_file)
text = text.replace("const val COMPACT_VALUE_HEIGHT_DP = 40", "const val COMPACT_VALUE_HEIGHT_DP = 46")
text = text.replace("const val COMPACT_KEY_HEIGHT_DP = 36", "const val COMPACT_KEY_HEIGHT_DP = 42")
text = text.replace("const val COMPACT_KEY_GAP_DP = 2", "const val COMPACT_KEY_GAP_DP = 5")
text = text.replace("const val COMPACT_FUNCTION_HEIGHT_DP = 34", "const val COMPACT_FUNCTION_HEIGHT_DP = 40")
start = text.index("private fun NumberPad(")
end = text.index("@Composable\nprivate fun ValueBox", start)
segment = text[start:end]
segment = segment.replace("else 44.dp", "else 48.dp")
segment = segment.replace(
    "    val rowGap = if (compact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP.dp else 6.dp\n",
    "    val rowGap = if (compact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP.dp else 6.dp\n    val columnGap = if (compact) 6.dp else 8.dp\n",
)
segment = segment.replace("Arrangement.spacedBy(8.dp)", "Arrangement.spacedBy(columnGap)")
segment = segment.replace(
    "Text(digit.toString())",
    "Text(digit.toString(), fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold)",
)
segment = segment.replace("Modifier.weight(1.4f).height(buttonHeight)", "Modifier.weight(1f).height(buttonHeight)")
text = text[:start] + segment + text[end:]
write(main_file, text)

# Fixed status bar and formal product name on related screens.
targets = [
    main_file,
    "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt",
    "app/src/main/java/jp/co/tenposinfo/register/MenuRevisionEditorActivity.kt",
    "app/src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt",
    "app/src/main/java/jp/co/tenposinfo/register/TaxInvoiceSettingsActivity.kt",
    "app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueueActivity.kt",
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
]
for target in targets:
    text = read(target)
    if "configureRegisterSystemBars(window)" not in text:
        text = text.replace("        super.onCreate(savedInstanceState)\n", "        super.onCreate(savedInstanceState)\n        configureRegisterSystemBars(window)\n", 1)
    text = text.replace('Text("REGISTER"', 'Text("つぐレジ"')
    text = text.replace('Text("つぐレジ 開発版"', 'Text("つぐレジ"')
    write(target, text)

# Remove duplicated direct chrome setup from the two original activities.
for target in (main_file, "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt"):
    text = read(target)
    text = re.sub(
        r"\n        window\.statusBarColor = android\.graphics\.Color\.rgb\(23, 63, 107\).*?isAppearanceLightNavigationBars = true\n        \}\n",
        "\n",
        text,
        count=1,
        flags=re.S,
    )
    write(target, text)

write(
    "app/src/test/java/jp/co/tenposinfo/register/V028UiStabilityTest.kt",
    '''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V028UiStabilityTest {
    private fun source(name: String) = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun lifecycleProviderDoesNotInjectOverlayButtons() {
        val provider = source("CatalogBootstrapProvider.kt")
        assertFalse(provider.contains("FrameLayout.LayoutParams"))
        assertFalse(provider.contains("installDynamicCatalogButton"))
        assertFalse(provider.contains("installRevisionEditorButton"))
        assertFalse(provider.contains("installTaxInvoiceButton"))
        assertFalse(provider.contains("installSyncButton"))
    }

    @Test
    fun navigationControlsArePartOfComposeLayout() {
        val catalog = source("CatalogSettingsActivity.kt")
        val dynamic = source("DynamicCatalogSettingsActivity.kt")
        assertTrue(catalog.contains("onDynamic"))
        assertTrue(catalog.contains("任意税率・メニュー改定"))
        assertTrue(dynamic.contains("onTaxInvoice"))
        assertTrue(dynamic.contains("onRevisionEditor"))
        assertTrue(dynamic.contains("onSync"))
    }

    @Test
    fun compactKeypadUsesLargerEqualWidthKeys() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("COMPACT_KEY_HEIGHT_DP = 42"))
        assertTrue(main.contains("COMPACT_KEY_GAP_DP = 5"))
        assertTrue(main.contains("COMPACT_FUNCTION_HEIGHT_DP = 40"))
        assertTrue(main.contains("BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1f).height(buttonHeight))"))
        assertFalse(main.contains("Modifier.weight(1.4f).height(buttonHeight)"))
    }

    @Test
    fun formalBrandAndFixedChromeAreUsed() {
        val sources = listOf(
            "MainActivity.kt",
            "CatalogSettingsActivity.kt",
            "DynamicCatalogSettingsActivity.kt",
            "MenuRevisionEditorActivity.kt",
            "SyncSettingsActivity.kt",
            "UnifiedPrintQueueActivity.kt",
        ).map(::source)
        assertTrue(sources.all { !it.contains("Text(\\\"REGISTER\\\"") })
        assertTrue(sources.all { it.contains("configureRegisterSystemBars(window)") })
    }
}
''',
)

write(
    "docs/V0.28_UI_STABILITY.md",
    '''# v0.28 UI安定化

## 対象

- SCR-100 販売画面
- SCR-300 / SCR-310 会計・支払追加
- SCR-200 商品・分類・税・販売プロファイル
- SCR-270 任意税率・メニュー改定
- 関連する設定・印刷管理画面

## 修正

- 上部バーとAndroidステータスバーをつぐレジの紺色で固定
- 画面上の `REGISTER` 表示を `つぐレジ` に統一
- コンパクトテンキーを42dpへ拡大し、行間と機能ボタンも調整
- 数量・現金ボタンを数字キーと同じ列幅へ統一
- `CatalogBootstrapProvider`による後付けViewボタンを廃止
- 任意税率、税・インボイス、改定編集、同期基盤の導線をComposeレイアウト内へ移動
- 画面サイズや表示密度が変わってもカードへ重ならない構造へ変更
''',
)

write(
    "docs/V0.28_RELEASE_NOTES.md",
    '''# v0.28.0-dev.1 リリースノート

実機スクリーンショットで確認された上部ボタンの重なり、テンキーの小ささ、ヘッダー表記と色の不統一を修正しました。後付けAndroid Viewによるナビゲーションを廃止し、Composeの通常レイアウトへ統合しています。
''',
)
