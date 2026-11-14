package montafra.beam

import java.util.Locale
import kotlin.math.absoluteValue

fun fmt(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    return if (value.absoluteValue >= 10.0) {
        "%.0f".format(Locale.ROOT, value)
    } else {
        "%.1f".format(Locale.ROOT, value)
    }
}
