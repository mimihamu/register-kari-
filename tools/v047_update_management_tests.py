from pathlib import Path

changed = []
for path in Path('management-app/src/test').rglob('*.kt'):
    text = path.read_text()
    updated = text.replace('versionCode = 76', 'versionCode = 77')
    updated = updated.replace('0.46.0-dev.1', '0.47.0-dev.1')
    if updated != text:
        path.write_text(updated)
        changed.append(str(path))

if not changed:
    raise SystemExit('no stale management test versions found')

print('\n'.join(changed))
