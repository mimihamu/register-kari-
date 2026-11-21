package jp.co.tenposinfo.register

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowCompat

object RegisterUiChromeV028 {
    const val TOP_BAR_ARGB: Long = 0xFF173F6B
    const val TOP_BAR_HEIGHT_DP: Int = 62
}

@Suppress("DEPRECATION")
fun configureRegisterSystemBars(window: Window, barColor: Int = Color.rgb(23, 63, 107)) {
    // Android 15+ draws transparent system bars. Paint the inset in our root UI.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.decorView.setBackgroundColor(barColor)
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
    window.statusBarColor = barColor
    window.navigationBarColor = barColor
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}
