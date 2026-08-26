from pathlib import Path

path = Path('app/src/main/java/jp/co/tenposinfo/register/RegisterApplication.kt')
text = path.read_text()
old = '''    override fun onCreate() {
        super.onCreate()
        PrinterConfigurationRegistry.reload(this)'''
new = '''    override fun onCreate() {
        super.onCreate()
        CrashReportRuntimeV138.install(this)
        PrinterConfigurationRegistry.reload(this)'''
if old not in text:
    raise SystemExit('PATCH_MISS: RegisterApplication.onCreate')
if text.count(old) != 1:
    raise SystemExit(f'PATCH_AMBIGUOUS: count={text.count(old)}')
path.write_text(text.replace(old, new, 1))
print('PATCH_OK')
