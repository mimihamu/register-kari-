package jp.co.tenposinfo.register.plus

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

enum class DriveConnectionStatus {
    NOT_REGISTERED,
    CHECKING,
    READY,
    PERMISSION_MISSING,
    PROVIDER_UNAVAILABLE,
    READ_FAILED,
}

data class DriveConnectionUiState(
    val status: DriveConnectionStatus = DriveConnectionStatus.NOT_REGISTERED,
    val providerName: String? = null,
    val providerPackage: String? = null,
    val isGoogleDrive: Boolean = false,
    val persistedReadPermission: Boolean = false,
    val checkedAt: Long? = null,
    val detail: String = "取込フォルダを登録してください",
    val autoImportOnLaunch: Boolean = true,
)

object DriveConnectionPolicy {
    const val AUTO_IMPORT_COOLDOWN_MS = 10 * 60 * 1_000L

    fun shouldAutoImport(
        enabled: Boolean,
        status: DriveConnectionStatus,
        lastStartedAt: Long,
        now: Long,
    ): Boolean = enabled &&
        status == DriveConnectionStatus.READY &&
        (lastStartedAt <= 0L || now - lastStartedAt >= AUTO_IMPORT_COOLDOWN_MS)
}

class DriveSyncPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun autoImportOnLaunch(): Boolean = preferences.getBoolean(KEY_AUTO_IMPORT_ON_LAUNCH, true)

    fun setAutoImportOnLaunch(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_IMPORT_ON_LAUNCH, enabled).apply()
    }

    fun lastAutoImportStartedAt(): Long = preferences.getLong(KEY_LAST_AUTO_IMPORT_STARTED_AT, 0L)

    fun markAutoImportStartedAt(value: Long) {
        preferences.edit().putLong(KEY_LAST_AUTO_IMPORT_STARTED_AT, value).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "tsuguregi_plus_drive_sync"
        private const val KEY_AUTO_IMPORT_ON_LAUNCH = "auto_import_on_launch"
        private const val KEY_LAST_AUTO_IMPORT_STARTED_AT = "last_auto_import_started_at"
    }
}

class DriveConnectionInspector(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun inspect(registration: ImportFolderRegistration?): DriveConnectionUiState {
        if (registration == null) {
            return DriveConnectionUiState(
                autoImportOnLaunch = true,
            )
        }

        val treeUri = runCatching { Uri.parse(registration.treeUri) }.getOrNull()
            ?: return failed(
                status = DriveConnectionStatus.READ_FAILED,
                detail = "登録URIを解析できません",
            )
        val authority = treeUri.authority
        val providerInfo = authority?.let {
            context.packageManager.resolveContentProvider(it, PackageManager.MATCH_ALL)
        }
        if (providerInfo == null) {
            return failed(
                status = DriveConnectionStatus.PROVIDER_UNAVAILABLE,
                detail = "登録したファイル提供元が端末で利用できません",
            )
        }

        val packageName = providerInfo.packageName
        val providerName = runCatching {
            providerInfo.applicationInfo.loadLabel(context.packageManager).toString()
        }.getOrNull()?.takeIf(String::isNotBlank) ?: packageName
        val persisted = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission
        }
        val isGoogleDrive = packageName.contains("google.android.apps.docs", ignoreCase = true) ||
            providerName.contains("Google Drive", ignoreCase = true) ||
            providerName == "Drive"

        if (!persisted) {
            return DriveConnectionUiState(
                status = DriveConnectionStatus.PERMISSION_MISSING,
                providerName = providerName,
                providerPackage = packageName,
                isGoogleDrive = isGoogleDrive,
                persistedReadPermission = false,
                checkedAt = nowMillis(),
                detail = "フォルダの永続読取権限がありません。再接続してください",
            )
        }

        val readable = runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            contentResolver.query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } == true
        }

        return readable.fold(
            onSuccess = { canRead ->
                if (canRead) {
                    DriveConnectionUiState(
                        status = DriveConnectionStatus.READY,
                        providerName = providerName,
                        providerPackage = packageName,
                        isGoogleDrive = isGoogleDrive,
                        persistedReadPermission = true,
                        checkedAt = nowMillis(),
                        detail = if (isGoogleDrive) {
                            "Google Driveの登録フォルダを読み取れます"
                        } else {
                            "登録フォルダを読み取れます"
                        },
                    )
                } else {
                    DriveConnectionUiState(
                        status = DriveConnectionStatus.READ_FAILED,
                        providerName = providerName,
                        providerPackage = packageName,
                        isGoogleDrive = isGoogleDrive,
                        persistedReadPermission = true,
                        checkedAt = nowMillis(),
                        detail = "登録フォルダの情報を取得できません",
                    )
                }
            },
            onFailure = { error ->
                DriveConnectionUiState(
                    status = DriveConnectionStatus.READ_FAILED,
                    providerName = providerName,
                    providerPackage = packageName,
                    isGoogleDrive = isGoogleDrive,
                    persistedReadPermission = true,
                    checkedAt = nowMillis(),
                    detail = error.message ?: "登録フォルダを読み取れません",
                )
            },
        )
    }

    private fun failed(
        status: DriveConnectionStatus,
        detail: String,
    ): DriveConnectionUiState = DriveConnectionUiState(
        status = status,
        checkedAt = nowMillis(),
        detail = detail,
    )
}
