package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TpNavy = Color(0xFF173F6B)
private val TpBackground = Color(0xFFF4F7FA)
private val TpBorder = Color(0xFFD5DEE7)

class TaxPaymentSettingsHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val actor = intent.getStringExtra(EXTRA_ACTOR).orEmpty().ifBlank { "責任者" }
        setContent {
            RegisterSafeAreaV141 {
            MaterialTheme {
                TaxPaymentSettingsHub(
                    actor = actor,
                    onTax = { startActivity(Intent(this, TaxInvoiceSettingsActivity::class.java)) },
                    onPayment = {
                        startActivity(
                            Intent(this, PaymentSettingsActivity::class.java)
                                .putExtra(PaymentSettingsActivity.EXTRA_ACTOR, actor),
                        )
                    },
                    onClose = { finish() },
                )
            }
        
            }
        }
    }

    companion object {
        const val EXTRA_ACTOR = "jp.co.tenposinfo.register.extra.TAX_PAYMENT_SETTINGS_ACTOR"
    }
}

@Composable
private fun TaxPaymentSettingsHub(
    actor: String,
    onTax: () -> Unit,
    onPayment: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = TpBackground) {
        Column(Modifier.fillMaxSize()) {
            RegisterTopBarV141(title = "税・支払設定", onBack = onClose)
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HubTileV136(
                    title = "税設定",
                    screenId = "SCR-630A",
                    description = "税区分、内税・外税混在、インボイス集計基準を設定します。",
                    modifier = Modifier.weight(1f),
                    onClick = onTax,
                )
                HubTileV136(
                    title = "支払設定",
                    screenId = "SCR-630B",
                    description = "現金・カード・電子マネー・QR・商品券・掛売などの有効/無効と表示順を設定します。",
                    modifier = Modifier.weight(1f),
                    onClick = onPayment,
                )
                }
            }

        }
    }
}

@Composable
private fun HubTileV136(
    title: String,
    screenId: String,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, TpBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, color = TpNavy, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}
