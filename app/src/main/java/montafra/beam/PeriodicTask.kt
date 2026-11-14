package montafra.beam

import android.os.Handler
import android.os.Looper

class PeriodicTask(private val callback: () -> Unit, private val intervalMs: () -> Long) {
    private val ticker = Handler(Looper.getMainLooper())
    private var running = false
    private val runnable = Runnable { tick() }

    fun stop() {
        running = false
        ticker.removeCallbacks(runnable)
    }

    private fun tick() {
        if (!running) return
        callback()
        // The callback may stop the task (for example, when the charger is unplugged).
        // Never enqueue another poll after that stop.
        if (running) ticker.postDelayed(runnable, intervalMs().coerceAtLeast(250L))
    }

    fun start() {
        stop()
        running = true
        tick()
    }
}