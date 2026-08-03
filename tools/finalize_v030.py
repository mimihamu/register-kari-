from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"{label} was not found in {path}")
    path.write_text(text.replace(old, new, 1))


main_path = ROOT / "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
replace_once(
    main_path,
    """                canOpenManagement = currentOperator?.permissions?.any {
                    it == RegisterPermission.VIEW_SALES ||
                        it == RegisterPermission.CASH_MOVEMENT ||
                        it == RegisterPermission.SETTLEMENT ||
                        it == RegisterPermission.REVERSAL
                } == true,
""",
    """                canOpenManagement = currentOperator?.permissions?.let(
                    ManagementNavigationPolicyV030::canOpenManagement,
                ) == true,
""",
    "MainActivity legacy management permission block",
)
replace_once(
    main_path,
    "onOpenManagement = { context.startActivity(Intent(context, OperationsActivity::class.java)) },",
    "onOpenManagement = { context.startActivity(Intent(context, OperationsHubActivityV030::class.java)) },",
    "MainActivity legacy management destination",
)

coordinator_path = ROOT / "app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt"
replace_once(
    coordinator_path,
    "    SETTLEMENT(RegisterPermission.SETTLEMENT, false),\n",
    "",
    "SecureOperationsCoordinator legacy action",
)

v016_path = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V016OperationsAuthorizationTest.kt"
v016 = v016_path.read_text()
v016 = v016.replace(
    "        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.SETTLEMENT))\n",
    "        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.X_INSPECTION))\n"
    "        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.Z_SETTLEMENT))\n",
)
v016 = v016.replace(
    "                OperationsAction.SETTLEMENT,\n                SettlementReportType.X_INSPECTION,\n",
    "                OperationsAction.X_INSPECTION,\n                SettlementReportType.X_INSPECTION,\n",
)
v016 = v016.replace(
    "                OperationsAction.SETTLEMENT,\n                SettlementReportType.Z_SETTLEMENT,\n",
    "                OperationsAction.Z_SETTLEMENT,\n                SettlementReportType.Z_SETTLEMENT,\n",
)
if "OperationsAction.SETTLEMENT" in v016:
    raise SystemExit("V016 still references OperationsAction.SETTLEMENT")
v016_path.write_text(v016)

v030_path = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V030BusinessStartNavigationTest.kt"
v030 = v030_path.read_text()
if "legacySettlementIsOnlyReferencedByCompatibilityMigration" not in v030:
    addition = '''
    @Test
    fun legacySettlementIsOnlyReferencedByCompatibilityMigration() {
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register")
        val references = sourceRoot.listFiles()
            .orEmpty()
            .filter { it.extension == "kt" && it.readText().contains("RegisterPermission.SETTLEMENT") }
            .map(File::getName)
            .sorted()

        assertEquals(listOf("SettlementPreflightV026.kt"), references)
        assertFalse(source("SecureOperationsCoordinator.kt").contains("OperationsAction.SETTLEMENT"))
    }

    @Test
    fun salesScreenManagementEntryUsesSharedPolicyAndResponsiveHub() {
        val main = source("MainActivity.kt")

        assertTrue(main.contains("ManagementNavigationPolicyV030::canOpenManagement"))
        assertTrue(main.contains("OperationsHubActivityV030::class.java"))
        assertFalse(main.contains("RegisterPermission.SETTLEMENT"))
    }
'''
    closing = v030.rfind("\n}")
    if closing < 0:
        raise SystemExit("V030BusinessStartNavigationTest closing brace was not found")
    v030 = v030[:closing] + addition + v030[closing:]
v030_path.write_text(v030)

responsive_path = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V030ResponsiveManagementTest.kt"
responsive = responsive_path.read_text()
old_responsive = '''    fun registerManagementEntryRoutesToResponsiveHub() {
        val application = source("RegisterApplication.kt")

        assertTrue(application.contains("OperationsHubActivityV030::class.java"))
'''
new_responsive = '''    fun registerManagementEntryRoutesToResponsiveHub() {
        val application = source("RegisterApplication.kt")
        val main = source("MainActivity.kt")

        assertTrue(application.contains("OperationsHubActivityV030::class.java"))
        assertTrue(main.contains("OperationsHubActivityV030::class.java"))
        assertTrue(main.contains("ManagementNavigationPolicyV030::canOpenManagement"))
'''
if old_responsive not in responsive:
    raise SystemExit("V030 responsive management test block was not found")
responsive_path.write_text(responsive.replace(old_responsive, new_responsive, 1))

final_workflow = ROOT / ".github/v030-final-build.yml"
workflow_path = ROOT / ".github/workflows/build-apk.yml"
if not final_workflow.is_file():
    raise SystemExit("Final workflow payload is missing")
workflow_path.write_text(final_workflow.read_text())
final_workflow.unlink()

source_root = ROOT / "app/src/main/java/jp/co/tenposinfo/register"
legacy_sources = sorted(
    path.name
    for path in source_root.glob("*.kt")
    if "RegisterPermission.SETTLEMENT" in path.read_text()
)
if legacy_sources != ["SettlementPreflightV026.kt"]:
    raise SystemExit(f"Unexpected legacy permission references: {legacy_sources}")
if "OperationsAction.SETTLEMENT" in coordinator_path.read_text():
    raise SystemExit("Legacy operations action remains")

Path(__file__).unlink()
