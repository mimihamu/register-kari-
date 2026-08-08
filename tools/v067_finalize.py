from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

# 1) Wire exact-sale normal receipt navigation into v0.66 business-date lookup.
lookup = ROOT / "app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt"
text = lookup.read_text(encoding="utf-8")
old = """            onRefresh = { refreshEpoch++ },\n            onOpenVoucher = { saleId ->\n                context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, saleId))\n            },\n"""
new = """            onRefresh = { refreshEpoch++ },\n            onOpenReceipt = { saleId ->\n                context.startActivity(SaleReceiptNavigation.intent(context, saleId))\n            },\n            onOpenVoucher = { saleId ->\n                context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, saleId))\n            },\n"""
assert old in text
text = text.replace(old, new, 1)
old = """    onRefresh: () -> Unit,\n    onOpenVoucher: (Long) -> Unit,\n"""
new = """    onRefresh: () -> Unit,\n    onOpenReceipt: (Long) -> Unit,\n    onOpenVoucher: (Long) -> Unit,\n"""
assert old in text
text = text.replace(old, new, 1)
old = """                        Spacer(Modifier.height(8.dp))\n                        OutlinedButton(\n                            onClick = { onOpenVoucher(sale.id) },\n                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),\n                        ) { Text(\"この売上で領収書発行\") }\n"""
new = """                        Spacer(Modifier.height(8.dp))\n                        OutlinedButton(\n                            onClick = { onOpenReceipt(sale.id) },\n                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),\n                        ) { Text(\"通常レシート確認・再印字\") }\n                        Spacer(Modifier.height(6.dp))\n                        OutlinedButton(\n                            onClick = { onOpenVoucher(sale.id) },\n                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),\n                        ) { Text(\"この売上で領収書発行\") }\n"""
assert old in text
text = text.replace(old, new, 1)
lookup.write_text(text, encoding="utf-8")

# 2) Bump POS version only.
build = ROOT / "app/build.gradle.kts"
text = build.read_text(encoding="utf-8")
text = text.replace("versionCode = 96", "versionCode = 97", 1)
text = text.replace('versionName = "0.66.0-dev.1"', 'versionName = "0.67.0-dev.1"', 1)
build.write_text(text, encoding="utf-8")

# 3) Keep historical feature checks, advancing only current-version/artifact expectations.
OLD_CODE = "versionCode = 96"
NEW_CODE = "versionCode = 97"
OLD_ESCAPED_NAME = 'versionName = \\"0.66.0-dev.1\\"'
NEW_ESCAPED_NAME = 'versionName = \\"0.67.0-dev.1\\"'
OLD_APK = "TSUGUREGI_v0.66.0_dev1_business_date_database_search_debug.apk"
NEW_APK = "TSUGUREGI_v0.67.0_dev1_sale_receipt_reprint_navigation_debug.apk"
OLD_ARTIFACT = "TSUGUREGI-v0.66.0-dev1-business-date-database-search-apks"
NEW_ARTIFACT = "TSUGUREGI-v0.67.0-dev1-sale-receipt-reprint-navigation-apks"

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

# 4) Restore full v0.66 workflow, then advance it cumulatively to v0.67.
workflow = ROOT / ".github/workflows/build-apk.yml"
base_workflow = subprocess.check_output(
    ["git", "show", "origin/develop/v0.66:.github/workflows/build-apk.yml"],
    cwd=ROOT,
    text=True,
)
text = base_workflow
text = text.replace("Verify cumulative v0.14-v0.66 sources", "Verify cumulative v0.14-v0.67 sources")
text = text.replace("versionCode = 96", "versionCode = 97")
text = text.replace('versionName = "0.66.0-dev.1"', 'versionName = "0.67.0-dev.1"')
text = text.replace(OLD_APK, NEW_APK)
text = text.replace(OLD_ARTIFACT, NEW_ARTIFACT)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V066BusinessDateDatabaseSearchTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V066BusinessDateDatabaseSearchTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V067SaleReceiptReprintNavigationTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.66_RELEASE_NOTES.md\n",
    "          test -s docs/V0.66_RELEASE_NOTES.md\n"
    "          test -s docs/V0.67_SALE_RECEIPT_REPRINT_NAVIGATION.md\n"
    "          test -s docs/V0.67_RELEASE_NOTES.md\n",
)
needle = "          ! grep -q 'SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
assert needle in text
text = text.replace(
    needle,
    needle
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptNavigation.kt\n"
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n"
    + "          grep -q 'SaleReceiptReprintActivity' app/src/main/AndroidManifest.xml\n"
    + "          grep -q 'SaleReceiptNavigation.intent(context, saleId)' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    + "          grep -q '通常レシート確認・再印字' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    + "          grep -q '再印字を確定' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n",
)
text = text.replace(
    "          SALES_HISTORY_DATABASE_OLD_DATE_SEARCH=true\n",
    "          SALES_HISTORY_DATABASE_OLD_DATE_SEARCH=true\n"
    "          SALE_RECEIPT_DIRECT_NAVIGATION=true\n"
    "          SALE_RECEIPT_CONTEXT_LOCK=true\n"
    "          SALE_RECEIPT_VIEW_SALES_RECHECK=true\n"
    "          SALE_RECEIPT_REPRINT_DOUBLE_CONFIRM=true\n"
    "          SALE_RECEIPT_REPRINT_USES_EXISTING_QUEUE=true\n",
)
text = text.replace(
    "          REAL_DEVICE_BUSINESS_DATE_DATABASE_SEARCH_VERIFICATION=required\n",
    "          REAL_DEVICE_SALE_RECEIPT_REPRINT_NAVIGATION_VERIFICATION=required\n"
    "          REAL_DEVICE_BUSINESS_DATE_DATABASE_SEARCH_VERIFICATION=required\n",
)
workflow.write_text(text, encoding="utf-8")

# 5) Temporary files are not part of the final branch.
(ROOT / ".github/workflows/v067-finalize-temp.yml").unlink(missing_ok=True)
Path(__file__).unlink()
