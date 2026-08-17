from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt")
text = path.read_text(encoding="utf-8")

old_screen = """    val actual = actualCash.toLongOrNull()\n    val actualCashMaySubmit = SettlementActualCashSafetyV105.maySubmit(reportType, actualCash.toLongOrNull())\n    val previewActual = if (isZ) actual else actual ?: summary.expectedCash\n"""
new_screen = """    val actual = actualCash.toLongOrNull()\n    val previewActual = if (isZ) actual else actual ?: summary.expectedCash\n"""
if old_screen not in text:
    raise SystemExit("screen-level actualCashMaySubmit block not found")
text = text.replace(old_screen, new_screen, 1)

old_panel = """    val isZ = reportType == SettlementReportType.Z_SETTLEMENT\n    SettlementPanelV030(modifier) {\n"""
new_panel = """    val isZ = reportType == SettlementReportType.Z_SETTLEMENT\n    val actualCashMaySubmit = SettlementActualCashSafetyV105.maySubmit(reportType, actualCash.toLongOrNull())\n    SettlementPanelV030(modifier) {\n"""
if old_panel not in text:
    raise SystemExit("SettlementInputPanelV030 insertion point not found")
text = text.replace(old_panel, new_panel, 1)

path.write_text(text, encoding="utf-8")
