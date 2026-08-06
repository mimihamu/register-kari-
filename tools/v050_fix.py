from pathlib import Path

for path in Path("management-app/src/test").rglob("*.kt"):
    content = path.read_text(encoding="utf-8")
    corrected = content.replace("versionCode = 90", "versionCode = 80")
    if corrected != content:
        path.write_text(corrected, encoding="utf-8")

workflow_path = Path("tools/build-apk-v050.generated.yml")
workflow = workflow_path.read_text(encoding="utf-8")
workflow_path.write_text(
    workflow.replace("versionCode = 90", "versionCode = 80"),
    encoding="utf-8",
)
