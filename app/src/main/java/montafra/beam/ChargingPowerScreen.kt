package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
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
import java.util.Locale

private fun currentPluggedState(context: Context): Boolean = runCatching {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}.getOrDefault(false)

@Composable
fun ChargingPowerScreen() {
    val context = LocalContext.current
    var plugged by remember(context) { mutableStateOf(currentPluggedState(context)) }
    var powerText by remember(context) { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        plugged = true
                        powerText = null
                        receiverContext.sendBroadcast(
                            Intent(batteryDataReq).setPackage(receiverContext.packageName)
                        )
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        plugged = false
                        powerText = null
                    }
                    batteryDataResp -> {
                        plugged = intent.getBooleanExtra("plugged", plugged)
                        val watts = intent.getDoubleExtra("powerWatts", Double.NaN)
                        powerText = watts.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f W", it) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(batteryDataResp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        context.sendBroadcast(Intent(batteryDataReq).setPackage(context.packageName))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val status = when {
        !plugged -> stringResource(R.string.connectCharger)
        powerText != null -> stringResource(R.string.charging)
        else -> stringResource(R.string.readingPower)
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.chargingPowerTitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (plugged) powerText ?: "— W" else "— W",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                status,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
