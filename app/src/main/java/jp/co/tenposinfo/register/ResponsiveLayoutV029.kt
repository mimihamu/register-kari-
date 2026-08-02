package jp.co.tenposinfo.register

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

internal enum class RegisterWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

internal data class RegisterResponsiveMetrics(
    val windowClass: RegisterWindowClass,
    val screenPaddingDp: Int,
    val panelGapDp: Int,
    val cardPaddingDp: Int,
    val headerHeightDp: Int,
    val bottomBarHeightDp: Int,
    val salesListWeight: Float,
    val salesKeypadWeight: Float,
    val salesProductsWeight: Float,
    val paymentDetailWeight: Float,
    val paymentKeypadWeight: Float,
) {
    val isCompact: Boolean
        get() = windowClass == RegisterWindowClass.COMPACT
}

internal data class RegisterKeypadMetrics(
    val keyHeightDp: Int,
    val functionHeightDp: Int,
    val valueHeightDp: Int,
    val gapDp: Int,
    val allocationListMaxHeightDp: Int,
    val scrollRequired: Boolean,
)

internal object RegisterResponsiveLayoutPolicy {
    const val MIN_TOUCH_DP = 48
    const val MAX_KEY_HEIGHT_DP = 80
    const val MAX_FUNCTION_HEIGHT_DP = 64
    const val MAX_VALUE_HEIGHT_DP = 68

    fun classify(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
    ): RegisterWindowClass = when {
        widthDp < 1_000 || heightDp < 600 || fontScale >= 1.30f -> RegisterWindowClass.COMPACT
        widthDp < 1_400 || heightDp < 800 || fontScale >= 1.15f -> RegisterWindowClass.MEDIUM
        else -> RegisterWindowClass.EXPANDED
    }

    fun metrics(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
    ): RegisterResponsiveMetrics = when (classify(widthDp, heightDp, fontScale)) {
        RegisterWindowClass.COMPACT -> RegisterResponsiveMetrics(
            windowClass = RegisterWindowClass.COMPACT,
            screenPaddingDp = 8,
            panelGapDp = 8,
            cardPaddingDp = 10,
            headerHeightDp = 56,
            bottomBarHeightDp = 72,
            salesListWeight = 0.31f,
            salesKeypadWeight = 0.34f,
            salesProductsWeight = 0.35f,
            paymentDetailWeight = 0.55f,
            paymentKeypadWeight = 0.45f,
        )

        RegisterWindowClass.MEDIUM -> RegisterResponsiveMetrics(
            windowClass = RegisterWindowClass.MEDIUM,
            screenPaddingDp = 12,
            panelGapDp = 12,
            cardPaddingDp = 14,
            headerHeightDp = 62,
            bottomBarHeightDp = 80,
            salesListWeight = 0.34f,
            salesKeypadWeight = 0.30f,
            salesProductsWeight = 0.36f,
            paymentDetailWeight = 0.60f,
            paymentKeypadWeight = 0.40f,
        )

        RegisterWindowClass.EXPANDED -> RegisterResponsiveMetrics(
            windowClass = RegisterWindowClass.EXPANDED,
            screenPaddingDp = 16,
            panelGapDp = 16,
            cardPaddingDp = 18,
            headerHeightDp = 68,
            bottomBarHeightDp = 88,
            salesListWeight = 0.35f,
            salesKeypadWeight = 0.29f,
            salesProductsWeight = 0.36f,
            paymentDetailWeight = 0.64f,
            paymentKeypadWeight = 0.36f,
        )
    }

    /**
     * Calculates keypad dimensions from the real remaining panel height.
     * The key area grows first. If even the minimum 48dp keys do not fit,
     * the caller must switch only that panel to vertical scrolling.
     */
    fun keypadMetrics(
        availableHeightDp: Int,
        functionRows: Int,
        reservedTopDp: Int = 0,
    ): RegisterKeypadMetrics {
        require(functionRows >= 0) { "functionRows must not be negative" }

        val gap = when {
            availableHeightDp >= 700 -> 8
            availableHeightDp >= 520 -> 6
            else -> 5
        }
        val functionHeight = when {
            availableHeightDp >= 720 -> 60
            availableHeightDp >= 560 -> 54
            else -> MIN_TOUCH_DP
        }.coerceAtMost(MAX_FUNCTION_HEIGHT_DP)
        val valueHeight = when {
            availableHeightDp >= 720 -> 64
            availableHeightDp >= 560 -> 56
            else -> MIN_TOUCH_DP
        }.coerceAtMost(MAX_VALUE_HEIGHT_DP)

        // Four numeric rows, three internal row gaps, plus the gaps around
        // the value field and the optional function rows.
        val fixedHeight = reservedTopDp + valueHeight +
            functionRows * functionHeight +
            (functionRows + 5) * gap
        val rawKeyHeight = (availableHeightDp - fixedHeight - 3 * gap) / 4
        val scrollRequired = rawKeyHeight < MIN_TOUCH_DP
        val keyHeight = rawKeyHeight.coerceIn(MIN_TOUCH_DP, MAX_KEY_HEIGHT_DP)
        val allocationHeight = when {
            availableHeightDp >= 720 -> 112
            availableHeightDp >= 560 -> 76
            else -> 52
        }

        return RegisterKeypadMetrics(
            keyHeightDp = keyHeight,
            functionHeightDp = functionHeight,
            valueHeightDp = valueHeight,
            gapDp = gap,
            allocationListMaxHeightDp = allocationHeight,
            scrollRequired = scrollRequired,
        )
    }
}

@Composable
internal fun rememberRegisterResponsiveMetrics(): RegisterResponsiveMetrics {
    val configuration = LocalConfiguration.current
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.fontScale,
    ) {
        RegisterResponsiveLayoutPolicy.metrics(
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            fontScale = configuration.fontScale,
        )
    }
}
