from pathlib import Path

script_path = Path(__file__).with_name("apply_dev22.py")
source = script_path.read_text(encoding="utf-8")
marker = '\npath(".github/workflows/build-apk.yml").write_text('
cut = source.find(marker)
if cut < 0:
    raise SystemExit("workflow replacement block was not found")
exec(compile(source[:cut], str(script_path), "exec"))
