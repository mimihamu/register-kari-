package jp.co.tenposinfo.register.cd

enum class CustomerDisplayLayoutMode(
    val compact: Boolean,
    val stacked: Boolean,
) {
    PHONE_PORTRAIT(compact = true, stacked = true),
    PHONE_LANDSCAPE(compact = true, stacked = false),
    TABLET_PORTRAIT(compact = false, stacked = true),
    TABLET_LANDSCAPE(compact = false, stacked = false),
}

object CustomerDisplayLayoutPolicy {
    const val PHONE_SMALLEST_WIDTH_DP = 600f

    fun select(widthDp: Float, heightDp: Float): CustomerDisplayLayoutMode {
        require(widthDp > 0f) { "widthDp must be positive" }
        require(heightDp > 0f) { "heightDp must be positive" }

        val compact = minOf(widthDp, heightDp) < PHONE_SMALLEST_WIDTH_DP
        val portrait = heightDp > widthDp
        return when {
            compact && portrait -> CustomerDisplayLayoutMode.PHONE_PORTRAIT
            compact -> CustomerDisplayLayoutMode.PHONE_LANDSCAPE
            portrait -> CustomerDisplayLayoutMode.TABLET_PORTRAIT
            else -> CustomerDisplayLayoutMode.TABLET_LANDSCAPE
        }
    }
}
