package jp.co.tenposinfo.register

/**
 * COR-001: 商品登録前の置数訂正。
 *
 * 販売画面で置数が残っている間は「訂正」を明細訂正として扱わず、
 * 置数だけを消去する。空のときだけ従来の直前／選択行訂正へ進む。
 */
internal object NumericCorrectionPolicyV135 {
    fun shouldClearInput(rawInput: String): Boolean = rawInput.isNotBlank()
}
