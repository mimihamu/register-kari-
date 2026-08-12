package jp.co.tenposinfo.register

import java.io.IOException

internal class OutboxFinalCommitVisibilityUnavailableException(
    fileName: String,
) : IOException(
    "送信JSONの確定直後に同名ファイルを再確認できません。確定結果を成功扱いせず再試行します。対象: $fileName",
)

internal class OutboxFinalCommitIdentityMismatchException(
    fileName: String,
) : OutboxDestinationCollisionException(
    fileName,
    "送信JSONの確定直後に別の同名ファイルが確認されました。アプリが作成した確定ファイルだけを破棄し、既存ファイルは保護します。",
)

internal object OutboxFinalCommitRaceSafetyV112 {
    fun requireSameDocument(
        fileName: String,
        committedDocumentId: String,
        visibleDocumentId: String,
    ) {
        if (committedDocumentId != visibleDocumentId) {
            throw OutboxFinalCommitIdentityMismatchException(fileName)
        }
    }
}
