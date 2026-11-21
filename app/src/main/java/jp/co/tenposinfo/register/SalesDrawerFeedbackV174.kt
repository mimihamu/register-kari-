package jp.co.tenposinfo.register

/** A failed drawer pulse can be uncertain: the hardware may have opened already. */
internal data class SalesDrawerFeedbackV174(
    val title: String,
    val detail: String,
    val uncertain: Boolean,
)

internal object SalesDrawerFeedbackPolicyV174 {
    fun fromResult(result: Result<Boolean>): SalesDrawerFeedbackV174? =
        result.fold(
            onSuccess = { accepted ->
                if (accepted) null else SalesDrawerFeedbackV174(
                    title = "ドロア開放を実行できませんでした",
                    detail = "ドロア設定とプリンター接続を確認してください。",
                    uncertain = false,
                )
            },
            onFailure = { error ->
                if (error is SalesExchangePreflightExceptionV174) {
                    SalesDrawerFeedbackV174(
                        title = "ドロア開放を実行できませんでした",
                        detail = error.message ?: "設定または担当者権限を確認してください。",
                        uncertain = false,
                    )
                } else {
                    SalesDrawerFeedbackV174(
                        title = "ドロアの開放結果を確認できません",
                        detail = "信号が届き、既にドロアが開いている可能性があります。" +
                            "ドロアの状態を確認してから、必要な場合のみ再操作してください。" +
                            "（" + (error.message ?: error.javaClass.simpleName) + "）",
                        uncertain = true,
                    )
                }
            },
        )
}
