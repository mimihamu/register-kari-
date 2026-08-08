from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OLD = 'versionName = \\"0.65.0-dev.1\\"'
NEW = 'versionName = \\"0.66.0-dev.1\\"'

changed = []
for base in [ROOT / 'app/src/test', ROOT / 'management-app/src/test']:
    for path in base.rglob('*.kt'):
        text = path.read_text(encoding='utf-8')
        updated = text.replace(OLD, NEW)
        if updated != text:
            path.write_text(updated, encoding='utf-8')
            changed.append(str(path.relative_to(ROOT)))

# Restore the full v0.66 CI definition that existed before the temporary runner commit.
workflow = ROOT / '.github/workflows/build-apk.yml'
workflow.write_text(
    subprocess.check_output(['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'], cwd=ROOT, text=True),
    encoding='utf-8',
)

# The helper is temporary and must not remain in the final branch.
Path(__file__).unlink()
print('\n'.join(changed))
