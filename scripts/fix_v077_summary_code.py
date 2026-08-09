from pathlib import Path
import subprocess

workflow = Path('.github/workflows/build-apk.yml')
text = workflow.read_text(encoding='utf-8')
old = '          REGISTER_VERSION_CODE=106\n'
new = '          REGISTER_VERSION_CODE=107\n'
if text.count(old) != 1:
    raise RuntimeError(f'expected exactly one stale summary code, found {text.count(old)}')
workflow.write_text(text.replace(old, new, 1), encoding='utf-8')

Path('scripts/fix_v077_summary_code.py').unlink(missing_ok=True)
Path('.github/workflows/fix-v077-summary.yml').unlink(missing_ok=True)

subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'fix(v0.77): correct artifact summary version code'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FIX_COMMIT={sha}', flush=True)
result = subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.77'])
raise SystemExit(result.returncode)
