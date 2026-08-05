from pathlib import Path

source_path = Path('app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDiagnosticsActivity.kt')
source = source_path.read_text()
source = source.replace('import com.google.android.gms.common.ConnectionResult\n', '')
source = source.replace(
    'playServices = "${ConnectionResult.getStatusString(playServicesCode)} ($playServicesCode)",',
    'playServices = "${GoogleApiAvailability.getInstance().getErrorString(playServicesCode)} ($playServicesCode)",',
)
source_path.write_text(source)

test_path = Path('app/src/test/java/jp/co/tenposinfo/register/V047GoogleDriveDiagnosticsTest.kt')
test = test_path.read_text().replace(
    '"ConnectionResult.getStatusString",',
    '"GoogleApiAvailability.getInstance().getErrorString",',
)
test_path.write_text(test)

print('v0.47 diagnostics API fix applied')
