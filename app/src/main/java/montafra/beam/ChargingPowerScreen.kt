package montafra.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.BatteryManager
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private data class UiPowerReading(
    val plugged: Boolean,
    val powerText: String?,
)

private fun isPlugged(intent: Intent?): Boolean {
    return (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

private fun readUiPower(context: Context): UiPowerReading {
    val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
    val battery = Battery(context).apply {
        currentScalar = prefs.getFloat("currentScalar", 1000f).toDouble()
        invertCurrent = prefs.getBoolean("invertCurrent", true)
    }
    val snapshot = battery.snapshot()
    val plugged = snapshot.plugged ?: snapshot.charging
    val watts = snapshot.watts?.takeIf { it.isFinite() }?.let { abs(it) }
    val powerText = watts?.let { String.format(Locale.US, "%.1f W", it) }
    return UiPowerReading(plugged, powerText)
}

@Composable
fun ChargingPowerScreen() {
    val context = LocalContext.current
    val initialPlugged = remember(context) {
        runCatching {
            isPlugged(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }.getOrDefault(false)
    }
    var plugged by remember(context) { mutableStateOf(initialPlugged) }
    var powerText by remember(context) { mutableStateOf<String?>(null) }

    // Power connection broadcasts control whether measurement polling is allowed.
    // No BatteryManager sampling loop exists while the charger is disconnected.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        plugged = true
                        powerText = null
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        plugged = false
                        powerText = null
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Poll instantaneous BatteryManager readings once per second only while plugged in.
    LaunchedEffect(context, plugged) {
        if (plugged) {
            while (true) {
                val result = runCatching { readUiPower(context) }.getOrNull()
                if (result != null) {
                    if (!result.plugged) {
                        plugged = false
                        powerText = null
                        break
                    }
                    powerText = result.powerText
                }
                delay(1_000L)
            }
        } else {
            powerText = null
        }
    }

    val status = when {
        !plugged -> stringResource(R.string.connectCharger)
        powerText != null -> stringResource(R.string.charging)
        else -> stringResource(R.string.readingPower)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.chargingPowerTitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                if (plugged) powerText ?: "— W" else "— W",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold
            )
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
