package jp.co.tenposinfo.register.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/** Management app chrome mirrors POS Back/Title semantics. SafeDrawing is applied by PlusSafeAreaV148. */
@Composable
internal fun PlusTopBarV155(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .background(PlusNavyV148)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = PlusNavyV148,
            ),
        ) { Text("戻る", fontWeight = FontWeight.Bold, maxLines = 1) }
        Text(
            "つぐレジ＋",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
