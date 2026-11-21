package jp.co.tenposinfo.register

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * v2.5 MODAL-001: shared modal layout and explicit safe dismissal contract.
 *
 * A destructive confirmation can only be dismissed using the visible Cancel action:
 * neither a backdrop tap nor Android Back should silently cancel that confirmation.
 * Never request focus for the destructive primary action.
 */
@Composable
internal fun RegisterModalV154(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        properties = DialogProperties(
            dismissOnBackPress = !destructive,
            dismissOnClickOutside = !destructive,
        ),
        containerColor = Color.White,
        titleContentColor = RegisterTopBarColorV141,
        textContentColor = Color(0xFF152635),
    )
}

@Composable
internal fun RegisterModalCancelV154(
    onClick: () -> Unit,
    label: String = "キャンセル",
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun RegisterModalConfirmV154(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Color(0xFFB42318) else RegisterTopBarColorV141,
        ),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}
