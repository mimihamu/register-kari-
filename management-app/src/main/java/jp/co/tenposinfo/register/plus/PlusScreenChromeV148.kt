package jp.co.tenposinfo.register.plus

import android.graphics.Color as AndroidColor
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

/** つぐレジ＋共通ヘッダーとAndroidシステム領域の色を統一する。 */
internal val PlusNavyV148 = Color(0xFF173F6B)

@Suppress("DEPRECATION")
internal fun configurePlusSystemBarsV148(window: Window) {
    window.statusBarColor = AndroidColor.rgb(23, 63, 107)
    window.navigationBarColor = AndroidColor.rgb(23, 63, 107)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

/** Android 15以降のedge-to-edgeでもタイトルや保存操作を隠さない。 */
@Composable
internal fun PlusSafeAreaV148(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(PlusNavyV148)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            content()
        }
    }
}
