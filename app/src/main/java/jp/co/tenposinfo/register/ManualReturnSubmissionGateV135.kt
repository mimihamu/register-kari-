package jp.co.tenposinfo.register

import java.util.concurrent.atomic.AtomicBoolean

/**
 * COR-005: 元取引なし返品の確定操作を単一実行にするためのUI送信ゲート。
 *
 * DB側の approval_request_id UNIQUE 制約は監査IDの重複を防ぐが、
 * 画面から毎回新しいUUIDを発行するため、連打そのものの二重返品防止にはならない。
 * そのため確定開始から成功ダイアログの確認、または失敗終了までを1回の送信としてロックする。
 */
internal class ManualReturnSubmissionGateV135 {
    private val submitting = AtomicBoolean(false)

    fun tryStart(): Boolean = submitting.compareAndSet(false, true)

    fun release() {
        submitting.set(false)
    }

    fun isSubmitting(): Boolean = submitting.get()
}
