from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
workflow = ROOT / '.github/workflows/build-apk.yml'
full = subprocess.check_output(
    ['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'],
    cwd=ROOT,
    text=True,
)
old_name = '          REGISTER_VERSION_NAME=0.66.0-dev.1\n'
old_code = '          REGISTER_VERSION_CODE=96\n'
assert old_name in full
assert old_code in full
full = full.replace(old_name, '          REGISTER_VERSION_NAME=0.67.0-dev.1\n', 1)
full = full.replace(old_code, '          REGISTER_VERSION_CODE=97\n', 1)
workflow.write_text(full, encoding='utf-8')
Path(__file__).unlink()
