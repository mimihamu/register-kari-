from pathlib import Path

path = Path('customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt')
text = path.read_text(encoding='utf-8')
old = 'import androidx.compose.ui.platform.LocalContext\n'
new = 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalDensity\n'
if 'import androidx.compose.ui.platform.LocalDensity' not in text:
    if old not in text:
        raise SystemExit('LocalContext import marker not found')
    text = text.replace(old, new, 1)
text = text.replace(
    'LogoBadge(snapshot.presentation, compact = layoutMode.compact.not())',
    'LogoBadge(snapshot.presentation, compact = layoutMode.compact)',
)
path.write_text(text, encoding='utf-8')
print('v0.37 UI import and logo sizing fixed')
