package jp.co.tenposinfo.register

/** Keep the sales footer readable even on narrow and large-font landscape devices. */
internal object SalesActionBarLayoutPolicyV174 {
    const val MIN_TAP_DP = 48
    const val TWO_ROW_HEIGHT_DP = 122

    fun useTwoRows(widthDp: Int, fontScale: Float = 1f): Boolean {
        val thresholdDp = when {
            fontScale >= 1.3f -> 1400
            fontScale >= 1.15f -> 1200
            else -> 1060
        }
        return widthDp < thresholdDp
    }

    fun twoRowButtonHeightDp(): Int = (TWO_ROW_HEIGHT_DP - 12 - 6) / 2
}
