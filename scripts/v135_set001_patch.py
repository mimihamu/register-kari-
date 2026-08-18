from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"expected patch anchor not found: {path}: {old[:80]!r}")
    text = text.replace(old, new, 1)
    p.write_text(text)


# Keep persisted keys aligned with the v2.5 setting key names.
replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/InitialReleaseSettingsV135.kt",
    'private const val KEY_CHECK_MAX = "check.numberMax"',
    'private const val KEY_CHECK_MAX = "check.numberRange"',
)

# Register the settings activity.
manifest = "app/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '''        <activity\n            android:name=".AdminSettingsActivity"\n            android:exported="false"\n            android:screenOrientation="landscape" />''',
    '''        <activity\n            android:name=".AdminSettingsActivity"\n            android:exported="false"\n            android:screenOrientation="landscape" />\n\n        <activity\n            android:name=".InitialReleaseSettingsActivityV135"\n            android:exported="false"\n            android:screenOrientation="landscape" />''',
)

# Wire SCR-690 to SCR-691..695 without compressing the existing right-hand tile grid.
admin = "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt"
replace_once(
    admin,
    '''                    actorName = actorName,\n                    onOperators = { screen = AdminScreen.OPERATORS },''',
    '''                    actorName = actorName,\n                    onInitialReleaseSettings = {\n                        context.startActivity(\n                            Intent(context, InitialReleaseSettingsActivityV135::class.java)\n                                .putExtra(InitialReleaseSettingsActivityV135.EXTRA_ACTOR, actorName),\n                        )\n                    },\n                    onOperators = { screen = AdminScreen.OPERATORS },''',
)
replace_once(
    admin,
    '''    actorName: String,\n    onOperators: () -> Unit,''',
    '''    actorName: String,\n    onInitialReleaseSettings: () -> Unit,\n    onOperators: () -> Unit,''',
)
replace_once(
    admin,
    'AsHeader("SCR-760", "各種設定", "認証：$actorName")',
    'AsHeader("SCR-690", "各種設定", "認証：$actorName")',
)
replace_once(
    admin,
    '''                Spacer(Modifier.weight(1f))\n                Text(\n                    "担当者・権限、責任者PIN、プリンター機種、ドロア、監査ログを端末内SQLiteで管理します。",''',
    '''                Spacer(Modifier.weight(1f))\n                Button(\n                    onClick = onInitialReleaseSettings,\n                    modifier = Modifier.fillMaxWidth().height(58.dp),\n                    colors = ButtonDefaults.buttonColors(containerColor = AsBlue),\n                ) {\n                    Text("店舗・レジ設定  SCR-691～695", fontWeight = FontWeight.Bold)\n                }\n                Spacer(Modifier.height(10.dp))\n                Text(\n                    "店舗基本、販売操作、営業日・精算、端末・アプリ、初期設定に加え、担当者・プリンター等を管理します。",''',
)

# Apply the device display policy and the row-merge setting to actual register behavior.
main = "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
replace_once(
    main,
    '''        configureRegisterSystemBars(window)\n        setContent {''',
    '''        configureRegisterSystemBars(window)\n        DeviceAppRuntimeV135.applyWindowPolicy(\n            window,\n            InitialReleaseSettingsStoreV135(applicationContext).loadDevice(),\n        )\n        setContent {''',
)
replace_once(
    main,
    '''    val database = remember { RegisterDatabase(context.applicationContext) }\n    val authStore = remember { OperatorAuthenticationStore(context.applicationContext) }''',
    '''    val database = remember { RegisterDatabase(context.applicationContext) }\n    val initialReleaseSettingsStore = remember { InitialReleaseSettingsStoreV135(context.applicationContext) }\n    val authStore = remember { OperatorAuthenticationStore(context.applicationContext) }''',
)
replace_once(
    main,
    '''                onAddProduct = { product ->\n                    val index = cart.indexOfFirst {''',
    '''                onAddProduct = { product ->\n                    val mergeSameItem = initialReleaseSettingsStore.loadSales().mergeSameItem\n                    val index = if (mergeSameItem) cart.indexOfFirst {''',
)
replace_once(
    main,
    '''                            it.note.isEmpty()\n                    }\n                    if (index >= 0) {''',
    '''                            it.note.isEmpty()\n                    } else -1\n                    if (index >= 0) {''',
)

print("SET-001 integration patch applied")
