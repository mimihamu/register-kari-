package jp.co.tenposinfo.register

internal enum class OutboxExistingDestinationDecisionV106 {
    ALREADY_SENT,
    COLLISION,
}

internal object OutboxDestinationCollisionSafetyV106 {
    const val COLLISION_MESSAGE =
        "送信先に同名JSONが存在し、内容が一致しません。既存JSONは保護したため自動置換しません。"

    fun decide(
        existingIsDirectory: Boolean,
        sameSize: Boolean,
        sameSha256: Boolean,
    ): OutboxExistingDestinationDecisionV106 =
        if (!existingIsDirectory && sameSize && sameSha256) {
            OutboxExistingDestinationDecisionV106.ALREADY_SENT
        } else {
            OutboxExistingDestinationDecisionV106.COLLISION
        }
}

internal open class OutboxDestinationCollisionException(
    fileName: String,
    detail: String = OutboxDestinationCollisionSafetyV106.COLLISION_MESSAGE,
) : IllegalStateException(
    "$detail 対象: $fileName",
)
