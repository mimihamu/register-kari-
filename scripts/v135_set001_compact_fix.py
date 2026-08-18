from pathlib import Path

p = Path('app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt')
text = p.read_text()

old_start = '''            AsPanel(Modifier.width(360.dp).fillMaxHeight()) {\n                Text("設定状態", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AsNavy)'''
new_start = '''            AsPanel(Modifier.width(360.dp).fillMaxHeight()) {\n                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {\n                    Text("設定状態", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AsNavy)'''
if new_start not in text:
    if old_start not in text:
        raise SystemExit('left settings panel start anchor not found')
    text = text.replace(old_start, new_start, 1)

old_spacer = '''                Spacer(Modifier.weight(1f))\n                Button(\n                    onClick = onInitialReleaseSettings,'''
new_spacer = '''                    Spacer(Modifier.height(10.dp))\n                    Button(\n                    onClick = onInitialReleaseSettings,'''
if new_spacer not in text:
    if old_spacer not in text:
        raise SystemExit('left settings panel spacer anchor not found')
    text = text.replace(old_spacer, new_spacer, 1)

old_end = '''                    lineHeight = 23.sp,\n                )\n            }\n            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {'''
new_end = '''                    lineHeight = 23.sp,\n                )\n                }\n            }\n            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {'''
if new_end not in text:
    if old_end not in text:
        raise SystemExit('left settings panel end anchor not found')
    text = text.replace(old_end, new_end, 1)

p.write_text(text)
print('SCR-690 compact scroll fallback applied')
