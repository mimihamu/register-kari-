from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

# 1) Route all normal receipt operations through the audited standalone screen.
main = ROOT / "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
text = main.read_text(encoding="utf-8")
old = """                onReceipt = {\n                    selectedSaleId = lastSaleId\n                    screen = AppScreen.RECEIPT_PREVIEW\n                },\n"""
new = """                onReceipt = {\n                    lastSaleId?.let { saleId ->\n                        context.startActivity(SaleReceiptNavigation.intent(context, saleId))\n                    }\n                },\n"""
assert old in text
text = text.replace(old, new, 1)
old = """                        onReceipt = { screen = AppScreen.RECEIPT_PREVIEW },\n"""
new = """                        onReceipt = {\n                            context.startActivity(SaleReceiptNavigation.intent(context, detail.summary.id))\n                        },\n"""
assert old in text
text = text.replace(old, new, 1)
main.write_text(text, encoding="utf-8")

# 2) Bump POS version only.
build = ROOT / "app/build.gradle.kts"
text = build.read_text(encoding="utf-8")
text = text.replace("versionCode = 97", "versionCode = 98", 1)
text = text.replace('versionName = "0.67.0-dev.1"', 'versionName = "0.68.0-dev.1"', 1)
build.write_text(text, encoding="utf-8")

# 3) Advance current-version/artifact expectations without removing historical checks.
OLD_CODE = "versionCode = 97"
NEW_CODE = "versionCode = 98"
OLD_ESCAPED_NAME = 'versionName = \\"0.67.0-dev.1\\"'
NEW_ESCAPED_NAME = 'versionName = \\"0.68.0-dev.1\\"'
OLD_APK = "TSUGUREGI_v0.67.0_dev1_sale_receipt_reprint_navigation_debug.apk"
NEW_APK = "TSUGUREGI_v0.68.0_dev1_sale_receipt_reprint_audit_debug.apk"
OLD_ARTIFACT = "TSUGUREGI-v0.67.0-dev1-sale-receipt-reprint-navigation-apks"
NEW_ARTIFACT = "TSUGUREGI-v0.68.0-dev1-sale-receipt-reprint-audit-apks"

for base in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in base.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        updated = (source
            .replace(OLD_CODE, NEW_CODE)
            .replace(OLD_ESCAPED_NAME, NEW_ESCAPED_NAME)
            .replace(OLD_APK, NEW_APK)
            .replace(OLD_ARTIFACT, NEW_ARTIFACT))
        if updated != source:
            path.write_text(updated, encoding="utf-8")

# 4) Rebuild full cumulative workflow from v0.67, then advance to v0.68.
workflow = ROOT / ".github/workflows/build-apk.yml"
text = subprocess.check_output(
    ["git", "show", "origin/develop/v0.67:.github/workflows/build-apk.yml"],
    cwd=ROOT,
    text=True,
)
text = text.replace("Verify cumulative v0.14-v0.67 sources", "Verify cumulative v0.14-v0.68 sources")
text = text.replace("versionCode = 97", "versionCode = 98")
text = text.replace('versionName = "0.67.0-dev.1"', 'versionName = "0.68.0-dev.1"')
text = text.replace(OLD_APK, NEW_APK)
text = text.replace(OLD_ARTIFACT, NEW_ARTIFACT)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V067SaleReceiptReprintNavigationTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V067SaleReceiptReprintNavigationTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V068SaleReceiptReprintAuditTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.67_RELEASE_NOTES.md\n",
    "          test -s docs/V0.67_RELEASE_NOTES.md\n"
    "          test -s docs/V0.68_SALE_RECEIPT_REPRINT_AUDIT.md\n"
    "          test -s docs/V0.68_RELEASE_NOTES.md\n",
)
needle = "          grep -q '再印字を確定' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n"
assert needle in text
text = text.replace(
    needle,
    needle
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt\n"
    + "          grep -q 'sale_receipt_reprint_requests' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt\n"
    + "          grep -q 'request_id TEXT NOT NULL UNIQUE' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt\n"
    + "          grep -q '再印字要求履歴（追記専用）' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n"
    + "          grep -q 'SaleReceiptNavigation.intent(context, detail.summary.id)' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    + "          grep -q 'SaleReceiptNavigation.intent(context, saleId)' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n",
)
text = text.replace(
    "          REGISTER_VERSION_NAME=0.67.0-dev.1\n          REGISTER_VERSION_CODE=97\n",
    "          REGISTER_VERSION_NAME=0.68.0-dev.1\n          REGISTER_VERSION_CODE=98\n",
)
text = text.replace(
    "          SALE_RECEIPT_REPRINT_USES_EXISTING_QUEUE=true\n",
    "          SALE_RECEIPT_REPRINT_USES_EXISTING_QUEUE=true\n"
    "          SALE_RECEIPT_REPRINT_AUDIT=true\n"
    "          SALE_RECEIPT_REPRINT_REQUEST_IDEMPOTENCY=true\n"
    "          SALE_RECEIPT_REPRINT_ATOMIC_JOB_AUDIT=true\n"
    "          SALE_RECEIPT_REPRINT_AUDIT_APPEND_ONLY=true\n"
    "          SALE_RECEIPT_REPRINT_ALL_UI_ROUTES_AUDITED=true\n",
)
text = text.replace(
    "          REAL_DEVICE_SALE_RECEIPT_REPRINT_NAVIGATION_VERIFICATION=required\n",
    "          REAL_DEVICE_SALE_RECEIPT_REPRINT_AUDIT_VERIFICATION=required\n"
    "          REAL_DEVICE_SALE_RECEIPT_REPRINT_NAVIGATION_VERIFICATION=required\n",
)
workflow.write_text(text, encoding="utf-8")

# Temporary helper/workflow must not remain in final branch.
(ROOT / ".github/workflows/v068-finalize-temp.yml").unlink(missing_ok=True)
Path(__file__).unlink()
