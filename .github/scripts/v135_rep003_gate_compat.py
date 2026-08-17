from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt")
text = path.read_text()
needle = "    val actual = actualCash.toLongOrNull()\n    val previewActual = if (isZ) actual else actual ?: summary.expectedCash\n"
replacement = "    val actual = actualCash.toLongOrNull()\n    val actualCashMaySubmit = SettlementActualCashSafetyV105.maySubmit(reportType, actualCash.toLongOrNull())\n    val previewActual = if (isZ) actual else actual ?: summary.expectedCash\n"
if replacement in text:
    raise SystemExit(0)
if needle not in text:
    raise SystemExit("expected SettlementActivityV030 actual-cash block not found")
text = text.replace(needle, replacement, 1)
needle_enabled = "            enabled = session != null && (!isZ || (!summary.settled && preflight.mayProceed)),\n"
replacement_enabled = "            enabled = session != null && actualCashMaySubmit && (!isZ || (!summary.settled && preflight.mayProceed)),\n"
if needle_enabled not in text:
    raise SystemExit("expected SettlementActivityV030 submit gate not found")
text = text.replace(needle_enabled, replacement_enabled, 1)
path.write_text(text)
