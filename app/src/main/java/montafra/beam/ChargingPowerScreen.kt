package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun ChargingPowerScreen() {
    val context = LocalContext.current
    val initialPlugged = remember(context) {
        runCatching { Battery(context).snapshot().plugged ?: false }.getOrDefault(false)
    }
    var power by remember { mutableStateOf("— W") }
    var plugged by remember { mutableStateOf(initialPlugged) }
    var hasLiveData by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    batteryDataResp -> {
                        plugged = intent.getBooleanExtra("plugged", false)
                        hasLiveData = true
                        power = if (plugged) intent.getStringExtra("power") ?: "— W" else "— W"
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        plugged = true
                        hasLiveData = false
                        power = "— W"
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        plugged = false
                        hasLiveData = false
                        power = "— W"
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(batteryDataResp)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(batteryDataReq).setPackage(context.packageName))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val status = when {
        !plugged -> stringResource(R.string.connectCharger)
        hasLiveData -> stringResource(R.string.charging)
        else -> stringResource(R.string.readingPower)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chargingPowerTitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(if (plugged && hasLiveData) power else "— W", color = MaterialTheme.colorScheme.onBackground, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
