from pathlib import Path

source = Path('.github/workflows/build-apk.yml').read_text(encoding='utf-8')
for line in (
    "assert not Path('.github/workflows/v052-apply-temp.yml').exists()",
    "assert not Path('tools/v052_apply.py').exists()",
    "assert not Path('tools/build-apk-v052.generated.yml').exists()",
):
    source = source.replace(f"\n{line}\n", f"\n          {line}\n")
Path('tools/build-apk-v052-fixed.yml').write_text(source, encoding='utf-8')
