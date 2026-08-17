from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt")
text = path.read_text()
needle = "                    zPreflight.message?.let { Text(it, color = OpDanger, fontWeight = FontWeight.Bold) }\n"
replacement = "                    if (summary.heldTickets > 0) {\n                        Text(\"未会計伝票があるためZ精算は禁止されています\", color = OpDanger, fontWeight = FontWeight.Bold)\n                    }\n                    zPreflight.message?.let { Text(it, color = OpDanger, fontWeight = FontWeight.Bold) }\n"
if replacement in text:
    raise SystemExit(0)
if needle not in text:
    raise SystemExit("expected REP-003 message anchor not found")
path.write_text(text.replace(needle, replacement, 1))
