from pathlib import Path

path = Path('customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt')
text = path.read_text(encoding='utf-8')
replacements = {
    'color = Accent,\n                fontSize = if (layoutMode.compact) 31.sp else 44.sp,': 'color = snapshot.presentation.standbyAccentColor(),\n                fontSize = if (layoutMode.compact) 31.sp else 44.sp,',
    'color = TextPrimary,\n                fontSize = if (layoutMode.compact) 25.sp else 34.sp,': 'color = snapshot.presentation.standbyPrimaryColor(),\n                fontSize = if (layoutMode.compact) 25.sp else 34.sp,',
    'color = TextSecondary,\n                fontSize = if (layoutMode.compact) 14.sp else 18.sp,': 'color = snapshot.presentation.standbySecondaryColor(),\n                fontSize = if (layoutMode.compact) 14.sp else 18.sp,',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f'marker not found: {old!r}')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
print('theme-aware standby contrast applied')
