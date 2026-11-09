package be.webweaver.tools.supportkit

import android.os.Handler
import android.os.Looper

/**
 * `[hb]` every N minutes while the process runs (SPEC §5): no alarms or wake-ups of its own, so a
 * missing heartbeat in the log means the process wasn't running (or the phone was asleep).
 * [minutes] = null: no heartbeat right now.
 */
internal class Heartbeat(private val minutes: () -> Int?) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            beat()
            schedule()
        }
    }

    fun start() = schedule()

    fun reschedule() {
        handler.removeCallbacks(tick)
        schedule()
    }

    private fun schedule() {
        val m = minutes() ?: return
        handler.postDelayed(tick, m * 60_000L)
    }

    private fun beat() {
        SupportKit.log("hb") {
            val app = runCatching { SupportKit.config.heartbeatValues() }.getOrDefault("").trim()
            listOf(app, SupportKit.events.snapshot()).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}
