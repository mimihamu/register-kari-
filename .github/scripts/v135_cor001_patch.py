from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
text = path.read_text(encoding="utf-8")
needle = "                                    onClick = onRemove,"
lines = text.splitlines()
indexes = [index for index, line in enumerate(lines) if line == needle]
if len(indexes) != 1:
    raise SystemExit(f"Expected exactly one onClick = onRemove line, found {len(indexes)}")

index = indexes[0]
replacement = [
    "                                    onClick = {",
    "                                        if (NumericCorrectionPolicyV135.shouldClearInput(numericInput)) {",
    "                                            numericInput = \"\"",
    "                                        } else {",
    "                                            onRemove()",
    "                                        }",
    "                                    },",
]
lines[index:index + 1] = replacement
trailing_newline = "\n" if text.endswith("\n") else ""
path.write_text("\n".join(lines) + trailing_newline, encoding="utf-8")
