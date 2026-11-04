package jp.co.tenposinfo.register

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-size numeric entry for short landscape displays. Calls the ORIGINAL entry callbacks
 * of the hosting screen. A dialog never approves a refund or payment on its own.
 */
@Composable
internal fun RegisterExpandedKeypadV179(
    title: String,
    displayValue: String,
    onClose: () -> Unit,
    keypad: @Composable (heightDp: Int, gapDp: Int) -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(0.76f).fillMaxHeight(0.94f)) {
            val metrics = RegisterResponsiveLayoutPolicy.keypadMetrics(
                availableHeightDp = maxHeight.value.toInt(),
                functionRows = 0,
                reservedTopDp = 122,
                preferredMinKeyDp = 72,
            )
            Surface(
                Modifier.fillMaxSize(),
                color = Color.White,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 10.dp,
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(onClick = onClose) { Text("閉じる") }
                    }
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(Color(0xFFEAF3FA), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            displayValue,
                            color = RegisterTopBarColorV141,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        keypad(metrics.keyHeightDp, metrics.gapDp)
                    }
                }
            }
        }
    }
}
