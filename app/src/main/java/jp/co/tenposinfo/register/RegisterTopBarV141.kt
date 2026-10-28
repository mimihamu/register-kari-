package jp.co.tenposinfo.register

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** つぐレジ共通のタイトルバー色。 */
internal val RegisterTopBarColorV141 = Color(0xFF173F6B)

/** Bottom action rows are clearly separated from white content cards without a white stripe. */
internal val RegisterSecondaryActionBarColorV159 = Color(0xFFE3EDF7)

/** システムアイコンと画面操作を重ねず、ステータスバー背景も揃える。 */
@Composable
internal fun RegisterSafeAreaV141(
    topBarColor: Color = RegisterTopBarColorV141,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(topBarColor)) {
        Spacer(
            Modifier.fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(topBarColor)
        )
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            content()
        }
    }
}

/** 画面幅が狭いときもタイトルと右側の操作を重ねない共通バー。 */
@Composable
internal fun RegisterTopBarV141(
    title: String,
    action: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backgroundColor: Color = RegisterTopBarColorV141,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Row(
        Modifier.fillMaxWidth()
            .height(metrics.headerHeightDp.dp)
            .background(backgroundColor)
            .padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = RegisterTopBarColorV141,
                ),
            ) { Text("戻る", fontWeight = FontWeight.Bold, maxLines = 1) }
            Spacer(Modifier.width(if (metrics.isCompact) 8.dp else 12.dp))
        }
        Text(
            "つぐレジ",
            color = Color.White,
            fontSize = if (metrics.isCompact) 19.sp else 23.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.width(if (metrics.isCompact) 10.dp else 20.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = if (metrics.isCompact) 17.sp else 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) action()
    }
}
