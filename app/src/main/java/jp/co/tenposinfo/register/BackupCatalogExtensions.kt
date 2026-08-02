package jp.co.tenposinfo.register

fun AutoBackupMetadataStore.registerManualBackup(
    verification: BackupVerification,
    exportedExternally: Boolean = false,
) {
    val existing = find(verification.fileName)
    write(
        AutoBackupMetadata(
            fileName = verification.fileName,
            reason = existing?.reason ?: BackupCreationReason.MANUAL,
            businessDate = existing?.businessDate,
            businessSessionId = existing?.businessSessionId,
            settlementId = existing?.settlementId,
            createdAt = verification.manifest.createdAt,
            appVersion = verification.manifest.appVersion,
            databaseSha256 = verification.manifest.databaseSha256,
            exportedExternally = existing?.exportedExternally == true || exportedExternally,
            lastVerifiedAt = System.currentTimeMillis(),
            state = AutoBackupFileState.READY,
        ),
    )
}

fun AutoBackupMetadataStore.registerExport(result: BackupExportResult) {
    val existing = find(result.fileName)
    write(
        AutoBackupMetadata(
            fileName = result.fileName,
            reason = existing?.reason ?: BackupCreationReason.MANUAL,
            businessDate = existing?.businessDate,
            businessSessionId = existing?.businessSessionId,
            settlementId = existing?.settlementId,
            createdAt = result.manifest.createdAt,
            appVersion = result.manifest.appVersion,
            databaseSha256 = result.manifest.databaseSha256,
            exportedExternally = true,
            lastVerifiedAt = System.currentTimeMillis(),
            state = AutoBackupFileState.READY,
        ),
    )
}
