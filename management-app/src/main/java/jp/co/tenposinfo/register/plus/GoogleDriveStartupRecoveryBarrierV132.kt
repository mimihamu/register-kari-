package jp.co.tenposinfo.register.plus

class GoogleDriveStartupRecoveryBlockedException(message: String) : IllegalStateException(message)

object GoogleDriveStartupRecoveryBarrierV132 {
    @Volatile
    private var blockedReason: String? = null

    @Synchronized
    fun resetForProcessStart() {
        blockedReason = null
    }

    @Synchronized
    fun block(stage: String, error: Throwable) {
        if (blockedReason != null) return
        val detail = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        blockedReason = "$stage：$detail".take(MAX_REASON_LENGTH)
    }

    fun isBlocked(): Boolean = blockedReason != null

    fun reason(): String? = blockedReason

    fun requireDriveSyncAllowed() {
        val reason = blockedReason ?: return
        throw GoogleDriveStartupRecoveryBlockedException(
            "Drive起動復旧が完了していないため、このprocessでは同期を開始しません：$reason",
        )
    }

    private const val MAX_REASON_LENGTH = 500
}
