package jp.co.tenposinfo.register.plus

import android.content.Context

enum class GoogleDriveOperatingMode {
    AUTOMATIC,
    DRIVE_API,
    COMPATIBILITY_FOLDER,
}

enum class GoogleDriveResolvedMode {
    DRIVE_API,
    COMPATIBILITY_FOLDER,
    UNDECIDED,
}

data class GoogleDriveOperationsSnapshot(
    val accountConnected: Boolean,
    val connectionTestStatus: GoogleDriveConnectionTestStatus,
    val folderStatus: DriveConnectionStatus,
    val selectedMode: GoogleDriveOperatingMode,
    val directAutoSyncEnabled: Boolean,
    val folderAutoImportEnabled: Boolean,
)

object GoogleDriveOperationsPolicy {
    fun resolve(snapshot: GoogleDriveOperationsSnapshot): GoogleDriveResolvedMode =
        when (snapshot.selectedMode) {
            GoogleDriveOperatingMode.DRIVE_API ->
                if (
                    snapshot.accountConnected &&
                    snapshot.connectionTestStatus == GoogleDriveConnectionTestStatus.SUCCEEDED
                ) {
                    GoogleDriveResolvedMode.DRIVE_API
                } else {
                    GoogleDriveResolvedMode.UNDECIDED
                }

            GoogleDriveOperatingMode.COMPATIBILITY_FOLDER ->
                if (snapshot.folderStatus == DriveConnectionStatus.READY) {
                    GoogleDriveResolvedMode.COMPATIBILITY_FOLDER
                } else {
                    GoogleDriveResolvedMode.UNDECIDED
                }

            GoogleDriveOperatingMode.AUTOMATIC -> when {
                snapshot.accountConnected &&
                    snapshot.connectionTestStatus == GoogleDriveConnectionTestStatus.SUCCEEDED ->
                    GoogleDriveResolvedMode.DRIVE_API

                snapshot.folderStatus == DriveConnectionStatus.READY ->
                    GoogleDriveResolvedMode.COMPATIBILITY_FOLDER

                else -> GoogleDriveResolvedMode.UNDECIDED
            }
        }

    fun nextAction(snapshot: GoogleDriveOperationsSnapshot): String {
        val resolved = resolve(snapshot)
        if (resolved != GoogleDriveResolvedMode.UNDECIDED) {
            return when (resolved) {
                GoogleDriveResolvedMode.DRIVE_API ->
                    "Drive API方式を適用できます。推奨設定を一括適用してください。"
                GoogleDriveResolvedMode.COMPATIBILITY_FOLDER ->
                    "互換フォルダ方式を適用できます。推奨設定を一括適用してください。"
                GoogleDriveResolvedMode.UNDECIDED -> error("unreachable")
            }
        }
        return when {
            !snapshot.accountConnected &&
                snapshot.selectedMode != GoogleDriveOperatingMode.COMPATIBILITY_FOLDER ->
                "Googleアカウントを登録し、Drive API接続確認を実行してください。"

            snapshot.selectedMode == GoogleDriveOperatingMode.DRIVE_API &&
                snapshot.connectionTestStatus != GoogleDriveConnectionTestStatus.SUCCEEDED ->
                "つぐレジで接続テストJSONを作成し、つぐレジ＋で検索してください。"

            snapshot.selectedMode == GoogleDriveOperatingMode.COMPATIBILITY_FOLDER &&
                snapshot.folderStatus != DriveConnectionStatus.READY ->
                "つぐレジ側と同じGoogle Driveフォルダを選択してください。"

            snapshot.connectionTestStatus == GoogleDriveConnectionTestStatus.NOT_RUN ->
                "接続テストを実行してください。見つからない場合は互換フォルダを登録します。"

            snapshot.connectionTestStatus == GoogleDriveConnectionTestStatus.RUNNING ->
                "接続テストの完了を待ってから再確認してください。"

            snapshot.connectionTestStatus == GoogleDriveConnectionTestStatus.NOT_FOUND &&
                snapshot.folderStatus != DriveConnectionStatus.READY ->
                "アプリ間でファイルが見えないため、同じGoogle Driveフォルダを登録してください。"

            snapshot.connectionTestStatus == GoogleDriveConnectionTestStatus.FAILED &&
                snapshot.folderStatus != DriveConnectionStatus.READY ->
                "診断・ログでOAuthとDrive APIを確認するか、互換フォルダを登録してください。"

            else -> "Google Drive設定を確認してください。"
        }
    }

    fun currentConfigurationHealthy(snapshot: GoogleDriveOperationsSnapshot): Boolean =
        when (resolve(snapshot)) {
            GoogleDriveResolvedMode.DRIVE_API ->
                snapshot.directAutoSyncEnabled && !snapshot.folderAutoImportEnabled
            GoogleDriveResolvedMode.COMPATIBILITY_FOLDER ->
                !snapshot.directAutoSyncEnabled && snapshot.folderAutoImportEnabled
            GoogleDriveResolvedMode.UNDECIDED -> false
        }
}

class GoogleDriveOperatingModeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_google_drive_operating_mode",
        Context.MODE_PRIVATE,
    )

    fun load(): GoogleDriveOperatingMode = runCatching {
        GoogleDriveOperatingMode.valueOf(
            preferences.getString("mode", GoogleDriveOperatingMode.AUTOMATIC.name).orEmpty(),
        )
    }.getOrDefault(GoogleDriveOperatingMode.AUTOMATIC)

    fun save(mode: GoogleDriveOperatingMode) {
        preferences.edit().putString("mode", mode.name).apply()
    }
}

data class GoogleDriveValidationChecklist(
    val sameGoogleAccountConfirmed: Boolean = false,
    val registerFolderWriteConfirmed: Boolean = false,
    val plusFolderReadConfirmed: Boolean = false,
    val sampleSaleUploaded: Boolean = false,
    val sampleSaleImported: Boolean = false,
    val appRestartVerified: Boolean = false,
) {
    val completedCount: Int
        get() = listOf(
            sameGoogleAccountConfirmed,
            registerFolderWriteConfirmed,
            plusFolderReadConfirmed,
            sampleSaleUploaded,
            sampleSaleImported,
            appRestartVerified,
        ).count(Boolean::not).let { 6 - it }
}

object GoogleDriveChecklistKey {
    const val SAME_ACCOUNT = "same_account"
    const val REGISTER_FOLDER_WRITE = "register_folder_write"
    const val PLUS_FOLDER_READ = "plus_folder_read"
    const val SAMPLE_SALE_UPLOADED = "sample_sale_uploaded"
    const val SAMPLE_SALE_IMPORTED = "sample_sale_imported"
    const val APP_RESTART_VERIFIED = "app_restart_verified"
}

class GoogleDriveValidationChecklistStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_google_drive_validation_checklist",
        Context.MODE_PRIVATE,
    )

    fun load(): GoogleDriveValidationChecklist = GoogleDriveValidationChecklist(
        sameGoogleAccountConfirmed = preferences.getBoolean(GoogleDriveChecklistKey.SAME_ACCOUNT, false),
        registerFolderWriteConfirmed = preferences.getBoolean(GoogleDriveChecklistKey.REGISTER_FOLDER_WRITE, false),
        plusFolderReadConfirmed = preferences.getBoolean(GoogleDriveChecklistKey.PLUS_FOLDER_READ, false),
        sampleSaleUploaded = preferences.getBoolean(GoogleDriveChecklistKey.SAMPLE_SALE_UPLOADED, false),
        sampleSaleImported = preferences.getBoolean(GoogleDriveChecklistKey.SAMPLE_SALE_IMPORTED, false),
        appRestartVerified = preferences.getBoolean(GoogleDriveChecklistKey.APP_RESTART_VERIFIED, false),
    )

    fun update(key: String, checked: Boolean): GoogleDriveValidationChecklist {
        require(
            key in setOf(
                GoogleDriveChecklistKey.SAME_ACCOUNT,
                GoogleDriveChecklistKey.REGISTER_FOLDER_WRITE,
                GoogleDriveChecklistKey.PLUS_FOLDER_READ,
                GoogleDriveChecklistKey.SAMPLE_SALE_UPLOADED,
                GoogleDriveChecklistKey.SAMPLE_SALE_IMPORTED,
                GoogleDriveChecklistKey.APP_RESTART_VERIFIED,
            ),
        ) { "未知の確認項目です" }
        preferences.edit().putBoolean(key, checked).apply()
        return load()
    }

    fun reset(): GoogleDriveValidationChecklist {
        preferences.edit().clear().apply()
        return load()
    }
}
