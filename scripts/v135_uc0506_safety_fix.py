from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/CatalogMasterStore.kt",
    '''            insertWithOnConflict(\n                "product_meta",\n                null,\n                ContentValues().apply {\n                    put("product_id", cleanId)\n                    if (departmentId == null) putNull("department_id") else put("department_id", departmentId)\n                    if (groupId == null) putNull("group_id") else put("group_id", groupId)\n                    put("enabled", if (enabled) 1 else 0)\n                    put("button_color", cleanColor)\n                    put("page_no", pageNo)\n                    put("slot_no", slotNo)\n                    put("kana", cleanKana)\n                    put("barcode", cleanBarcode)\n                    put("updated_at", System.currentTimeMillis())\n                },\n                SQLiteDatabase.CONFLICT_REPLACE,\n            )''',
    '''            val metaValues = ContentValues().apply {\n                if (departmentId == null) putNull("department_id") else put("department_id", departmentId)\n                if (groupId == null) putNull("group_id") else put("group_id", groupId)\n                put("enabled", if (enabled) 1 else 0)\n                put("button_color", cleanColor)\n                put("page_no", pageNo)\n                put("slot_no", slotNo)\n                put("kana", cleanKana)\n                put("barcode", cleanBarcode)\n                put("updated_at", System.currentTimeMillis())\n            }\n            val updatedMeta = update("product_meta", metaValues, "product_id = ?", arrayOf(cleanId))\n            if (updatedMeta == 0) {\n                metaValues.put("product_id", cleanId)\n                insertOrThrow("product_meta", null, metaValues)\n            }''',
    "product_meta non-destructive upsert",
)

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/SalesProductSearchDialogV135.kt",
    '''    val results = remember(products, query) { ProductLookupPolicyV135.search(products, query).take(50) }\n    val submitExact = { ProductLookupPolicyV135.findExact(products, query)?.let(onRegister) }''',
    '''    val results = remember(products, query) { ProductLookupPolicyV135.search(products, query).take(50) }\n    val submitExact: () -> Unit = {\n        ProductLookupPolicyV135.findExact(products, query)?.let { onRegister(it) }\n    }''',
    "search dialog submit lambda",
)

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/SalesProductSearchDialogV135.kt",
    'import androidx.compose.material3.HorizontalDivider\n',
    '',
    "remove divider import",
)
replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/SalesProductSearchDialogV135.kt",
    '                        HorizontalDivider()\n',
    '',
    "remove divider usage",
)

print("UC-05/06 safety fix prepared")
