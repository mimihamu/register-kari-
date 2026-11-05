package jp.co.tenposinfo.register

/**
 * POS entry gates. Editing must stop as soon as checkout confirmation starts;
 * an already fully allocated payment should never accept another tender.
 */
internal object RegisterPaymentInputSafetyV180 {
    fun canEditTender(remaining: Long, completing: Boolean): Boolean =
        remaining > 0L && !completing

    fun canClear(completing: Boolean): Boolean = !completing
}

/** The zoomed keypad consumes the available width without being unwieldy on big tablets. */
internal object RegisterExpandedKeypadLayoutV180 {
    fun widthFraction(screenWidthDp: Int, fontScale: Float): Float = when {
        fontScale >= 1.3f || screenWidthDp < 900 -> 0.92f
        screenWidthDp < 1200 -> 0.84f
        screenWidthDp < 1600 -> 0.76f
        else -> 0.68f
    }
}
