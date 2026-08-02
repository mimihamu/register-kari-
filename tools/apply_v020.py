from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/jp/co/tenposinfo/register/RegisterApplication.kt"
LEGACY = ROOT / "app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsActivity.kt"
POLICY = ROOT / "app/src/main/java/jp/co/tenposinfo/register/OperationsRoutingPolicy.kt"
TEST = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V020OperationsRoutingTest.kt"
DOC = ROOT / "docs/V0.20_OPERATIONS_ROUTING.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


text = APP.read_text(encoding="utf-8")
text = replace_once(
    text,
    "            is OperationsActivity, is AdvancedOperationsActivity -> guardManagementActivity(activity)\n",
    "            is OperationsActivity -> guardManagementActivity(activity)\n",
    "management activity route",
)
text = replace_once(
    text,
    "        val open = runCatching { AdvancedOperationsStore.isBusinessOpen(activity.applicationContext) }.getOrDefault(false)\n",
    "        val open = runCatching { isCanonicalBusinessSessionOpen(activity) }.getOrDefault(false)\n",
    "business day gate store",
)
marker = "    private fun guardManagementActivity(activity: Activity) {\n"
helper = """    private fun isCanonicalBusinessSessionOpen(activity: Activity): Boolean {
        val store = OperationsStore(activity.applicationContext)
        return try {
            store.activeBusinessSession()?.status == BusinessSessionStatus.OPEN
        } finally {
            store.close()
        }
    }

"""
text = replace_once(text, marker, helper + marker, "canonical business session helper")
APP.write_text(text, encoding="utf-8")

legacy = LEGACY.read_text(encoding="utf-8")
legacy = replace_once(
    legacy,
    "class AdvancedOperationsActivity : ComponentActivity() {\n",
    "@Deprecated(\"旧管理画面。正式導線はOperationsActivityを使用してください\")\nclass AdvancedOperationsActivity : ComponentActivity() {\n",
    "legacy activity deprecation",
)
LEGACY.write_text(legacy, encoding="utf-8")

POLICY.write_text(
    '''package jp.co.tenposinfo.register

object OperationsRoutingPolicy {
    const val CANONICAL_ACTIVITY = "jp.co.tenposinfo.register.OperationsActivity"
    const val LEGACY_ACTIVITY = "jp.co.tenposinfo.register.AdvancedOperationsActivity"

    fun isCanonical(className: String): Boolean = className == CANONICAL_ACTIVITY

    fun manifestUsesCanonicalRoute(manifest: String): Boolean =
        manifest.contains("android:name=\".OperationsActivity\"") &&
            !manifest.contains("<activity-alias") &&
            !manifest.contains("android:name=\".AdvancedOperationsActivity\"") &&
            !manifest.contains("android:targetActivity=\".AdvancedOperationsActivity\"")
}
''',
    encoding="utf-8",
)

TEST.write_text(
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V020OperationsRoutingTest {
    private val canonicalManifest = """
        <application>
            <activity
                android:name=".OperationsActivity"
                android:exported="false"
                android:screenOrientation="landscape" />
        </application>
    """.trimIndent()

    @Test
    fun canonicalActivityIsTheOnlyAcceptedManagementTarget() {
        assertTrue(OperationsRoutingPolicy.isCanonical("jp.co.tenposinfo.register.OperationsActivity"))
        assertFalse(OperationsRoutingPolicy.isCanonical("jp.co.tenposinfo.register.AdvancedOperationsActivity"))
    }

    @Test
    fun directActivityRegistrationIsAccepted() {
        assertTrue(OperationsRoutingPolicy.manifestUsesCanonicalRoute(canonicalManifest))
    }

    @Test
    fun legacyAliasIsRejected() {
        val legacy = """
            <application>
                <activity android:name=".AdvancedOperationsActivity" />
                <activity-alias
                    android:name=".OperationsActivity"
                    android:targetActivity=".AdvancedOperationsActivity" />
            </application>
        """.trimIndent()
        assertFalse(OperationsRoutingPolicy.manifestUsesCanonicalRoute(legacy))
    }
}
''',
    encoding="utf-8",
)

DOC.write_text(
    '''# つぐレジ v0.20 正式管理画面ルーティング

## 発見した回帰

AndroidManifestで`.OperationsActivity`が実Activityではなく、旧`.AdvancedOperationsActivity`への`activity-alias`として登録されていた。
そのため販売画面や権限ゲートから`OperationsActivity`を起動しても、v0.16以降の正式管理画面ではなく旧管理画面へ遷移する構成だった。

## 修正

- `.OperationsActivity`を通常の`<activity>`として直接登録する。
- `.AdvancedOperationsActivity`と旧aliasをManifestから削除する。
- 販売画面、権限ゲート、営業日ゲートはすべて正式`OperationsActivity`へ遷移する。
- 営業中判定を旧`AdvancedOperationsStore.isBusinessOpen()`から正式`OperationsStore.activeBusinessSession()`へ変更する。
- 旧Activityはソース互換性のため残すが`@Deprecated`とし、Manifest非登録にする。

## 有効になる累積機能

正式導線へ切り替わることで、以下が実際の管理画面で利用される。

- v0.16：個別権限、動的責任者PIN、監査整合
- v0.17：返品取消・Z精算の原子性と多重実行防止
- v0.18：営業日セッション、開始釣銭、Z精算後の書込禁止
- v0.19：商品単位の部分返品、販売時税スナップショット、返品票

## 回帰防止

CIで次を確認する。

- `.OperationsActivity`の通常Activity登録が1件だけ存在する。
- `activity-alias`が存在しない。
- `.AdvancedOperationsActivity`がManifestに存在しない。
- MainActivityとRegisterApplicationの管理導線が`OperationsActivity::class.java`を使用する。
- 販売画面の営業中判定が正式`OperationsStore`を使用する。
''',
    encoding="utf-8",
)
