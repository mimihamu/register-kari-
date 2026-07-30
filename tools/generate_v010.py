#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path


def replace_required(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise RuntimeError(f"v0.10 source generation failed: {label}")
    return source.replace(old, new)


def replace_inclusive(source: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = source.find(start_marker)
    end_start = source.find(end_marker, start + len(start_marker))
    if start < 0 or end_start < start:
        raise RuntimeError(f"v0.10 source generation failed: {label}")
    end = end_start + len(end_marker)
    return source[:start] + replacement + source[end:]


def replace_before(source: str, start_marker: str, next_marker: str, replacement: str, label: str) -> str:
    start = source.find(start_marker)
    end = source.find(next_marker, start + len(start_marker))
    if start < 0 or end <= start:
        raise RuntimeError(f"v0.10 source generation failed: {label}")
    return source[:start] + replacement.rstrip() + "\n\n" + source[end:]


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate_v010.py <app-dir> <generated-root>")

    app_dir = Path(sys.argv[1]).resolve()
    generated_root = Path(sys.argv[2]).resolve()
    repo_root = app_dir.parent
    source_root = app_dir / "src/main/java"
    package_rel = Path("jp/co/tenposinfo/register")
    package_source = source_root / package_rel
    package_generated = generated_root / package_rel
    fragments_dir = repo_root / "tools/v08"

    main_source = package_source / "MainActivity.kt"
    operations_source = package_source / "AdvancedOperationsActivity.kt"
    database_source = package_source / "RegisterDatabase.kt"

    if generated_root.exists():
        shutil.rmtree(generated_root)
    shutil.copytree(source_root, generated_root)

    def fragment(name: str) -> str:
        return (fragments_dir / name).read_text(encoding="utf-8").rstrip()

    main_text = main_source.read_text(encoding="utf-8")
    main_text = replace_inclusive(
        main_text,
        "    val database = remember { RegisterDatabase(context.applicationContext) }",
        "    val products = remember { database.loadProducts() }",
        fragment("main_state.ktfrag"),
        "MainActivity state",
    )
    main_text = replace_before(
        main_text,
        "            AppScreen.LOGIN -> LoginScreen(",
        "            AppScreen.SALES -> SalesScreen(",
        fragment("main_login_case.ktfrag"),
        "MainActivity login case",
    )
    main_text = replace_before(
        main_text,
        "            AppScreen.SALES -> SalesScreen(",
        "            AppScreen.LINE_EDIT -> {",
        fragment("main_sales_case.ktfrag"),
        "MainActivity sales case",
    )
    main_text = replace_before(
        main_text,
        "@Composable\nprivate fun LoginScreen(",
        "@OptIn(ExperimentalFoundationApi::class)",
        fragment("login_screen.ktfrag"),
        "LoginScreen",
    )
    main_text = replace_required(
        main_text,
        "    onPayment: () -> Unit,\n    onSalesHistory: () -> Unit,\n    onPrintQueue: () -> Unit,\n) {",
        "    onPayment: () -> Unit,\n    onSalesHistory: () -> Unit,\n    onPrintQueue: () -> Unit,\n    accessMessage: String?,\n    onLogout: () -> Unit,\n) {",
        "SalesScreen signature",
    )
    main_text = replace_before(
        main_text,
        "        Row(\n            Modifier.fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 18.dp),",
        "        Row(Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {",
        fragment("sales_header.ktfrag"),
        "SalesScreen header",
    )
    main_text = replace_before(
        main_text,
        "            CardPanel(Modifier.weight(0.40f).fillMaxHeight()) {",
        "        Row(\n            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp, vertical = 7.dp),",
        fragment("sales_product_panel.ktfrag"),
        "paged product panel",
    )
    main_text = main_text.replace(".taxCategory.symbol", ".taxSymbol")
    main_text = replace_required(
        main_text,
        "item.product.copy(taxCategory = category)",
        "item.product.withLegacyTaxCategory(category)",
        "line edit tax snapshot",
    )
    main_text = main_text.replace("正常（Schema v4）", "正常（動的税・改定対応）")
    main_text = replace_required(
        main_text,
        "                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)",
        "                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)\n                    AutomaticPrintScheduler.enqueueNow(context.applicationContext)",
        "sale immediate print",
    )
    main_text = replace_required(
        main_text,
        "                            database.enqueueReprint(detail.summary.id, receiptPaper.widthMm)",
        "                            database.enqueueReprint(detail.summary.id, receiptPaper.widthMm)\n                            AutomaticPrintScheduler.enqueueNow(context.applicationContext)",
        "reprint immediate work",
    )
    (package_generated / "MainActivity.kt").write_text(main_text, encoding="utf-8")

    database = database_source.read_text(encoding="utf-8")
    database = replace_required(
        database,
        "            while (cursor.moveToNext()) result += cursor.toCartItem()\n            return result\n        }\n    }\n\n    fun saveCart(items: List<CartItem>) {",
        "            while (cursor.moveToNext()) result += cursor.toCartItem()\n            return LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_CART, 0L, result)\n        }\n    }\n\n    fun saveCart(items: List<CartItem>) {",
        "load cart tax snapshots",
    )
    database = replace_required(
        database,
        "            items.forEachIndexed { index, item ->\n                insertOrThrow(\"cart_items\", null, item.toContentValues().apply { put(\"line_no\", index + 1) })\n            }",
        "            items.forEachIndexed { index, item ->\n                insertOrThrow(\"cart_items\", null, item.toContentValues().apply { put(\"line_no\", index + 1) })\n            }\n            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, items)",
        "save cart tax snapshots",
    )
    database = replace_required(
        database,
        "            items.forEach { item ->\n                insertOrThrow(\n                    \"held_ticket_items\",\n                    null,\n                    item.toContentValues().apply { put(\"ticket_id\", ticketId) },\n                )\n            }\n            ticketId",
        "            items.forEach { item ->\n                insertOrThrow(\n                    \"held_ticket_items\",\n                    null,\n                    item.toContentValues().apply { put(\"ticket_id\", ticketId) },\n                )\n            }\n            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_HELD, ticketId, items)\n            ticketId",
        "save held ticket tax snapshots",
    )
    database = replace_required(
        database,
        "            while (cursor.moveToNext()) result += cursor.toCartItem()\n            return result\n        }\n    }\n\n    fun deleteHeldTicket(ticketId: Long) {\n        writableDatabase.delete(\"held_tickets\", \"id = ?\", arrayOf(ticketId.toString()))\n    }",
        "            while (cursor.moveToNext()) result += cursor.toCartItem()\n            return LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_HELD, ticketId, result)\n        }\n    }\n\n    fun deleteHeldTicket(ticketId: Long) {\n        writableDatabase.runInTransaction {\n            delete(\"held_tickets\", \"id = ?\", arrayOf(ticketId.toString()))\n            delete(\"line_tax_snapshots\", \"scope = ? AND owner_id = ?\", arrayOf(LineTaxSnapshotStore.SCOPE_HELD, ticketId.toString()))\n        }\n    }",
        "load and delete held ticket tax snapshots",
    )
    database = replace_required(
        database,
        "            insertPrintJob(this, saleId, paperWidthMm, createdAt)\n            saleId",
        "            insertPrintJob(this, saleId, paperWidthMm, createdAt)\n            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)\n            saleId",
        "save sale tax snapshots",
    )
    database = replace_required(
        database,
        "        return SaleDetailRecord(summary, items, payments, TaxEngine.calculate(items))",
        "        val snapshotItems = LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)\n        return SaleDetailRecord(summary, snapshotItems, payments, TaxEngine.calculate(snapshotItems))",
        "load sale tax snapshots",
    )
    (package_generated / "RegisterDatabase.kt").write_text(database, encoding="utf-8")

    operations = operations_source.read_text(encoding="utf-8")
    operations = replace_inclusive(
        operations,
        "    val store = remember { AdvancedOperationsStore(context.applicationContext) }",
        "    var screen by remember { mutableStateOf(AdvancedScreen.MENU) }",
        fragment("advanced_state.ktfrag"),
        "AdvancedOperations state",
    )
    operations = replace_before(
        operations,
        "            AdvancedScreen.MENU -> AdvancedMenuScreen(",
        "            AdvancedScreen.BUSINESS -> BusinessDayScreen(",
        fragment("advanced_menu_case.ktfrag"),
        "AdvancedOperations menu",
    )
    fixed_pin = 'require(pin == "0000") { "責任者PINが違います（テストPIN：0000）" }'
    operations = replace_required(
        operations,
        fixed_pin,
        'require(OperatorSessionRegistry.verifyManagerPin(context.applicationContext, pin)) { "責任者PINが違います" }',
        "fixed manager PIN",
    )
    operations = operations.replace("責任者PIN（テスト：0000）", "責任者PIN")
    operations = operations.replace(".taxCategory.symbol", ".taxSymbol")
    operations = operations.replace(
        'var operator by remember { mutableStateOf("責任者") }',
        'var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: "責任者") }',
    )
    operations = replace_required(
        operations,
        "store.recordSettlement(type, actualCash, operator, paperWidth)",
        "store.recordSettlement(type, actualCash, operator, paperWidth).also { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }",
        "settlement immediate print",
    )
    operations = replace_required(
        operations,
        "store.createReversal(saleId, type, quantities, reason, operator, paperWidth)",
        "store.createReversal(saleId, type, quantities, reason, operator, paperWidth).also { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }",
        "reversal immediate print",
    )
    (package_generated / "AdvancedOperationsActivity.kt").write_text(operations, encoding="utf-8")

    for kotlin_file in generated_root.rglob("*.kt"):
        text = kotlin_file.read_text(encoding="utf-8")
        updated = text.replace(".taxCategory.symbol", ".taxSymbol")
        if updated != text:
            kotlin_file.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()
