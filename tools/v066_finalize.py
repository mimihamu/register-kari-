from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

OLD_VERSION_CODE = "versionCode = 95"
NEW_VERSION_CODE = "versionCode = 96"
OLD_VERSION_NAME = 'versionName = "0.65.0-dev.1"'
NEW_VERSION_NAME = 'versionName = "0.66.0-dev.1"'
OLD_APK = "TSUGUREGI_v0.65.0_dev1_business_date_sales_lookup_debug.apk"
NEW_APK = "TSUGUREGI_v0.66.0_dev1_business_date_database_search_debug.apk"
OLD_ARTIFACT = "TSUGUREGI-v0.65.0-dev1-business-date-sales-lookup-apks"
NEW_ARTIFACT = "TSUGUREGI-v0.66.0-dev1-business-date-database-search-apks"


def replace_all(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    updated = (
        text.replace(OLD_VERSION_CODE, NEW_VERSION_CODE)
        .replace(OLD_VERSION_NAME, NEW_VERSION_NAME)
        .replace(OLD_APK, NEW_APK)
        .replace(OLD_ARTIFACT, NEW_ARTIFACT)
    )
    if updated != text:
        path.write_text(updated, encoding="utf-8")


for base in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in base.rglob("*.kt"):
        replace_all(path)

workflow = ROOT / ".github/workflows/build-apk.yml"
replace_all(workflow)
text = workflow.read_text(encoding="utf-8")
text = text.replace(
    "Verify cumulative v0.14-v0.65 sources",
    "Verify cumulative v0.14-v0.66 sources",
)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V065BusinessDateSalesLookupTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V065BusinessDateSalesLookupTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V066BusinessDateDatabaseSearchTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.65_RELEASE_NOTES.md\n",
    "          test -s docs/V0.65_RELEASE_NOTES.md\n"
    "          test -s docs/V0.66_BUSINESS_DATE_DATABASE_SEARCH.md\n"
    "          test -s docs/V0.66_RELEASE_NOTES.md\n",
)
text = text.replace(
    "          grep -q 'BusinessDateSalesLookupActivity' app/src/main/AndroidManifest.xml\n",
    "          grep -q 'BusinessDateSalesLookupActivity' app/src/main/AndroidManifest.xml\n"
    "          grep -q 'buildDatabaseQuery' app/src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt\n"
    "          grep -q 'store.search(appliedCriteria, pageOffset)' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    "          grep -q 'LIMIT ? OFFSET ?' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    "          grep -q 'SQLite直接検索' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    "          ! grep -q 'SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n",
)
text = text.replace(
    "          REGISTER_VERSION_NAME=0.65.0-dev.1\n          REGISTER_VERSION_CODE=95\n",
    "          REGISTER_VERSION_NAME=0.66.0-dev.1\n          REGISTER_VERSION_CODE=96\n",
)
text = text.replace(
    "          SALES_HISTORY_LEGACY_DATE_INFERENCE=false\n",
    "          SALES_HISTORY_LEGACY_DATE_INFERENCE=false\n"
    "          SALES_HISTORY_DATABASE_DIRECT_SEARCH=true\n"
    "          SALES_HISTORY_DATABASE_PAGE_SIZE=200\n"
    "          SALES_HISTORY_DATABASE_BOUND_ARGS=true\n"
    "          SALES_HISTORY_DATABASE_LIKE_ESCAPE=true\n"
    "          SALES_HISTORY_DATABASE_OLD_DATE_SEARCH=true\n",
)
text = text.replace(
    "          REAL_DEVICE_BUSINESS_DATE_SALES_LOOKUP_VERIFICATION=required\n",
    "          REAL_DEVICE_BUSINESS_DATE_DATABASE_SEARCH_VERIFICATION=required\n"
    "          REAL_DEVICE_BUSINESS_DATE_SALES_LOOKUP_VERIFICATION=required\n",
)
workflow.write_text(text, encoding="utf-8")
