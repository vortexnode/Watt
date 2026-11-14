package montafra.beam

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private fun readUiPower(context: Context): UiPowerReading {
    val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
    val battery = Battery(context).apply {
        // Match the Samsung-compatible Beam calibration used by StatusService.
        currentScalar = prefs.getFloat("currentScalar", 1000f).toDouble()
        invertCurrent = prefs.getBoolean("invertCurrent", true)
    }
    val snapshot = battery.snapshot()
    val plugged = snapshot.plugged ?: snapshot.charging
    val watts = snapshot.watts
        ?.takeIf { it.isFinite() }
        ?.let { abs(it) }
    val powerText = watts?.let { String.format(Locale.US, "%.1f W", it) }
    return UiPowerReading(plugged, powerText)
}

@Composable
fun ChargingPowerScreen() {
    val context = LocalContext.current
    var reading by remember(context) {
        mutableStateOf(runCatching { readUiPower(context) }.getOrDefault(UiPowerReading(false, null)))
    }

    // Read BatteryManager directly in the UI process. The previous implementation waited for a
    // broadcast from StatusService; if that one-shot request raced service startup (or the
    // notification service was disabled), the screen stayed on "Reading..." indefinitely.
    // This screen is foreground-only, so a one-second coroutine is lightweight and independent
    // of Shizuku's optional UserService.
    LaunchedEffect(context) {
        while (true) {
            reading = runCatching { readUiPower(context) }
                .getOrDefault(UiPowerReading(false, null))
            delay(1_000L)
        }
    }

    val status = when {
        !reading.plugged -> stringResource(R.string.connectCharger)
        reading.powerText != null -> stringResource(R.string.charging)
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
                if (reading.plugged) reading.powerText ?: "— W" else "— W",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold
            )
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
