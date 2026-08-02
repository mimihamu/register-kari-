package jp.co.tenposinfo.register

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowCompat

object RegisterUiChromeV028 {
    const val TOP_BAR_ARGB: Long = 0xFF173F6B
    const val TOP_BAR_HEIGHT_DP: Int = 62
}

@Suppress("DEPRECATION")
fun configureRegisterSystemBars(window: Window) {
    window.statusBarColor = Color.rgb(23, 63, 107)
    window.navigationBarColor = Color.WHITE
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = true
    }
}
