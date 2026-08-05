from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"replacement source not found: {path}\n{old[:180]}")
    target.write_text(text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    if old not in text:
        if new in text:
            return
        raise RuntimeError(f"replacement source not found: {path}: {old}")
    target.write_text(text.replace(old, new))


foundation = "app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt"
replace_once(
    foundation,
    """        return runCatching {
            JournalOutboxStore(applicationContext).use { it.stagePending(100) }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
""",
    """        return runCatching {
            JournalOutboxStore(applicationContext).use { it.stagePending(100) }
            OutboxExternalDeliveryCoordinator(applicationContext).process(100)
        }.fold(
            onSuccess = { delivery ->
                if (delivery.retryRecommended) Result.retry() else Result.success()
            },
            onFailure = { Result.retry() },
        )
""",
)
replace_once(
    foundation,
    """        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
""",
    """        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
""",
)
replace_once(
    foundation,
    """ * 実Google Drive OAuthとアップロードは後続版で接続し、v0.11は耐障害Outboxとファイル生成までを担当する。
""",
    """ * v0.35ではAndroidのDocumentsProviderへ安全配送する。Google Drive REST APIとOAuthは使用しない。
""",
)

sync_ui = "app/src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt"
replace_once(sync_ui, "package jp.co.tenposinfo.register\n\nimport android.os.Bundle", "package jp.co.tenposinfo.register\n\nimport android.content.Intent\nimport android.os.Bundle")
replace_once(
    sync_ui,
    """                                onClick = {
                                    val count = store.stagePending(500)
                                    refresh++
                                    message = "$count 件をローカル送信ステージへ出力しました"
                                },
""",
    """                                onClick = {
                                    val count = store.stagePending(500)
                                    DriveOutboxScheduler.enqueueNow(context.applicationContext)
                                    refresh++
                                    message = "$count 件をローカル送信ステージへ出力し、外部送信を要求しました"
                                },
""",
)
replace_once(
    sync_ui,
    """                            Card(colors = CardDefaults.cardColors(containerColor = SyPaleYellow)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("接続状態：Google Drive未認証", fontWeight = FontWeight.Bold, color = SyDanger)
                                    Text(
                                        "v0.11は売上ジャーナル、耐障害Outbox、JSON生成、WorkManager再試行までを実装しています。OAuth認証とDrive APIアップロードは未接続です。",
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("ローカル出力先", fontWeight = FontWeight.Bold, color = SyNavy)
""",
    """                            Card(colors = CardDefaults.cardColors(containerColor = SyPaleGreen)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("外部送信：Androidフォルダ連携", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                    Text(
                                        "v0.35は端末内JSONをGoogle Drive・USB・端末フォルダへ自動配送し、サイズとSHA-256確認後に送信済みへ確定します。Drive REST APIとOAuthは使用しません。",
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    context.startActivity(Intent(context, OutboxDeliverySettingsActivity::class.java))
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SyBlue),
                            ) { Text("外部自動送信設定") }
                            Spacer(Modifier.height(12.dp))
                            Text("ローカル出力先", fontWeight = FontWeight.Bold, color = SyNavy)
""",
)
replace_once(
    sync_ui,
    "SCR-760  売上ジャーナル・Google Drive同期基盤",
    "SCR-760  売上ジャーナル・外部同期基盤",
)

application = "app/src/main/java/jp/co/tenposinfo/register/RegisterApplication.kt"
replace_once(
    application,
    """            is AutoBackupSettingsActivity,
            is ExternalBackupSettingsActivity -> guardSettingsActivity(activity)
""",
    """            is AutoBackupSettingsActivity,
            is ExternalBackupSettingsActivity,
            is OutboxDeliverySettingsActivity -> guardSettingsActivity(activity)
""",
)

manifest = "app/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    """        <activity
            android:name=".ExternalBackupSettingsActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".DataProtectionActivity"
""",
    """        <activity
            android:name=".ExternalBackupSettingsActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".OutboxDeliverySettingsActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".DataProtectionActivity"
""",
)

build = "app/build.gradle.kts"
replace_all(build, 'versionCode = 64', 'versionCode = 65')
replace_all(build, 'versionName = "0.34.0-dev.1"', 'versionName = "0.35.0-dev.1"')

workflow = ".github/workflows/build-apk.yml"
replace_all(workflow, "v0.14-v0.34", "v0.14-v0.35")
replace_all(workflow, "versionCode = 64", "versionCode = 65")
replace_all(workflow, 'versionName = "0.34.0-dev.1"', 'versionName = "0.35.0-dev.1"')
replace_once(
    workflow,
    """            app/src/main/java/jp/co/tenposinfo/register/ExternalBackupSettingsActivity.kt \\
            app/src/main/java/jp/co/tenposinfo/register/AutoBackup.kt \\
""",
    """            app/src/main/java/jp/co/tenposinfo/register/ExternalBackupSettingsActivity.kt \\
            app/src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt \\
            app/src/main/java/jp/co/tenposinfo/register/OutboxDeliverySettingsActivity.kt \\
            app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt \\
            app/src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt \\
            app/src/main/java/jp/co/tenposinfo/register/AutoBackup.kt \\
""",
)
replace_once(
    workflow,
    """            app/src/test/java/jp/co/tenposinfo/register/V034ExternalBackupMirrorTest.kt \\
            app/src/test/java/jp/co/tenposinfo/register/V033PeriodicBackupSettingsTest.kt \\
""",
    """            app/src/test/java/jp/co/tenposinfo/register/V035OutboxExternalDeliveryTest.kt \\
            app/src/test/java/jp/co/tenposinfo/register/V034ExternalBackupMirrorTest.kt \\
            app/src/test/java/jp/co/tenposinfo/register/V033PeriodicBackupSettingsTest.kt \\
""",
)
replace_once(
    workflow,
    """            docs/V0.34_EXTERNAL_BACKUP_MIRROR.md \\
            docs/V0.34_RELEASE_NOTES.md; do
""",
    """            docs/V0.35_OUTBOX_EXTERNAL_DELIVERY.md \\
            docs/V0.35_RELEASE_NOTES.md \\
            docs/V0.34_EXTERNAL_BACKUP_MIRROR.md \\
            docs/V0.34_RELEASE_NOTES.md; do
""",
)
replace_once(
    workflow,
    """          mirror = (root / 'ExternalBackupSync.kt').read_text()
          external_ui = (root / 'ExternalBackupSettingsActivity.kt').read_text()
          settings = (root / 'AutoBackupSettings.kt').read_text()
""",
    """          mirror = (root / 'ExternalBackupSync.kt').read_text()
          external_ui = (root / 'ExternalBackupSettingsActivity.kt').read_text()
          delivery = (root / 'OutboxExternalDelivery.kt').read_text()
          delivery_ui = (root / 'OutboxDeliverySettingsActivity.kt').read_text()
          foundation = (root / 'BusinessSyncFoundation.kt').read_text()
          sync_ui = (root / 'SyncSettingsActivity.kt').read_text()
          settings = (root / 'AutoBackupSettings.kt').read_text()
""",
)
replace_once(
    workflow,
    """          # v0.34 external automatic mirror.
""",
    """          # v0.35 verified external Outbox delivery.
          for token in (
              'data class OutboxDeliverySettings',
              'status=\'STAGED\'',
              'SyncOutboxStatus.SENT.name',
              'OutboxDeliveryPathPolicy',
              '.partial',
              'SHA-256',
              'MAX_ATTEMPTS = 10',
              'SYNC_OUTBOX_EXTERNAL_SENT',
              'SYNC_OUTBOX_EXTERNAL_FAILED',
              'outbox_delivery_failures',
          ):
              assert token in delivery, token
          assert 'ActivityResultContracts.OpenDocumentTree' in delivery_ui
          assert 'takePersistableUriPermission' in delivery_ui
          assert '失敗を再試行' in delivery_ui
          assert 'OutboxExternalDeliveryCoordinator' in foundation
          assert 'ExistingWorkPolicy.APPEND_OR_REPLACE' in foundation
          assert 'OutboxDeliverySettingsActivity::class.java' in sync_ui
          assert 'is OutboxDeliverySettingsActivity' in application
          assert 'android:name=".OutboxDeliverySettingsActivity"' in manifest

          # v0.34 external automatic mirror.
""",
)
replace_once(
    workflow,
    """          assert not list(Path('tools').glob('v034*'))
          assert not Path('.github/workflows/v034-apply.yml').exists()
""",
    """          assert not list(Path('tools').glob('v035*'))
          assert not Path('.github/workflows/v035-apply.yml').exists()
          assert not list(Path('tools').glob('v034*'))
          assert not Path('.github/workflows/v034-apply.yml').exists()
""",
)
replace_once(
    workflow,
    """          test "$(grep -c 'android:name=".ExternalBackupSettingsActivity"' app/src/main/AndroidManifest.xml)" -eq 1
          ! grep -q '<activity-alias' app/src/main/AndroidManifest.xml
""",
    """          test "$(grep -c 'android:name=".ExternalBackupSettingsActivity"' app/src/main/AndroidManifest.xml)" -eq 1
          test "$(grep -c 'android:name=".OutboxDeliverySettingsActivity"' app/src/main/AndroidManifest.xml)" -eq 1
          ! grep -q '<activity-alias' app/src/main/AndroidManifest.xml
""",
)
replace_all(workflow, "TSUGUREGI_v0.34.0_dev1_external_backup_mirror_debug.apk", "TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk")
replace_all(workflow, "REGISTER_VERSION_NAME=0.34.0-dev.1", "REGISTER_VERSION_NAME=0.35.0-dev.1")
replace_all(workflow, "REGISTER_VERSION_CODE=64", "REGISTER_VERSION_CODE=65")
replace_once(
    workflow,
    """          EXTERNAL_BACKUP_DESTINATION=android-document-tree
""",
    """          OUTBOX_EXTERNAL_DELIVERY=android-document-tree
          OUTBOX_STAGED_MEANING=local-json-ready
          OUTBOX_SENT_MEANING=external-size-and-sha256-verified
          OUTBOX_EXTERNAL_PARTIAL_COMMIT=true
          OUTBOX_EXTERNAL_SHA256_VERIFY=true
          OUTBOX_EXTERNAL_MAX_ATTEMPTS=10
          OUTBOX_EXTERNAL_TARGETS=google-drive-usb-device-folder
          EXTERNAL_BACKUP_DESTINATION=android-document-tree
""",
)
replace_all(workflow, "REAL_DEVICE_EXTERNAL_BACKUP_VERIFICATION=required", "REAL_DEVICE_EXTERNAL_BACKUP_VERIFICATION=required\n          REAL_DEVICE_OUTBOX_DELIVERY_VERIFICATION=required")
replace_all(workflow, "TSUGUREGI-v0.34.0-dev1-external-backup-mirror-apks", "TSUGUREGI-v0.35.0-dev1-outbox-external-delivery-apks")

print("v0.35 integration applied")
