from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def update(relative: str, old: str, new: str) -> None:
    target = ROOT / relative
    content = target.read_text(encoding="utf-8")
    if old not in content:
        raise RuntimeError(f"replacement target not found in {relative}: {old[:120]!r}")
    target.write_text(content.replace(old, new), encoding="utf-8")


recovery = "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveRecoveryActivity.kt"
update(
    recovery,
    '''                    folderPreferences.saveRegistration(registration)
                    DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)
                    GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)
                    GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(
                        applicationContext,
                        enabled = false,
                    )
                    registration to inspector.inspect(registration)''',
    '''                    folderPreferences.saveRegistration(registration)
                    val connection = inspector.inspect(registration)
                    if (connection.status == DriveConnectionStatus.READY) {
                        DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)
                        GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)
                        GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(
                            applicationContext,
                            enabled = false,
                        )
                    }
                    registration to connection''',
)
update(
    recovery,
    '''                        directAutoSyncEnabled = false,
                        folderAutoImportEnabled = true,
                        message = if (connection.status == DriveConnectionStatus.READY) {''',
    '''                        directAutoSyncEnabled = if (connection.status == DriveConnectionStatus.READY) {
                            false
                        } else {
                            GoogleDriveDirectSyncStatusStore(applicationContext).load().autoSyncOnLaunch
                        },
                        folderAutoImportEnabled = if (connection.status == DriveConnectionStatus.READY) {
                            true
                        } else {
                            DriveSyncPreferences(applicationContext).autoImportOnLaunch()
                        },
                        message = if (connection.status == DriveConnectionStatus.READY) {''',
)

plus_test = "management-app/src/test/java/jp/co/tenposinfo/register/plus/V051GoogleDriveRecoveryFallbackTest.kt"
update(
    plus_test,
    '''            "GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled",
            "同じGoogle Driveフォルダを選択",''',
    '''            "GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled",
            "if (connection.status == DriveConnectionStatus.READY)",
            "同じGoogle Driveフォルダを選択",''',
)

workflow = "tools/build-apk-v051.generated.yml"
update(workflow, "          REGISTER_VERSION_NAME=0.50.0-dev.1\n", "          REGISTER_VERSION_NAME=0.51.0-dev.1\n")
update(workflow, "          REGISTER_VERSION_CODE=80\n", "          REGISTER_VERSION_CODE=81\n")
update(workflow, "          MANAGEMENT_APP_VERSION_NAME=0.9.0-dev.1\n", "          MANAGEMENT_APP_VERSION_NAME=0.10.0-dev.1\n")
update(workflow, "          MANAGEMENT_APP_VERSION_CODE=9\n", "          MANAGEMENT_APP_VERSION_CODE=10\n")
update(
    workflow,
    "          REAL_DEVICE_CROSS_APP_DRIVE_FILE_SCOPE=required\n",
    "          REAL_DEVICE_CROSS_APP_DRIVE_FILE_SCOPE=required\n          REAL_DEVICE_FOLDER_RECOVERY=required\n",
)

print("v0.51 corrections applied")
