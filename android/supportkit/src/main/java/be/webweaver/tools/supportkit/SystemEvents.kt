package be.webweaver.tools.supportkit

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.Locale
import java.util.TimeZone

/** Core `app` and `system` lines (SPEC §5), from Android's own sources. */
internal class SystemEvents(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val prefs = context.getSharedPreferences("supportkit", Context.MODE_PRIVATE)
    @Volatile private var lastBatteryStep = -1

    /** `[app] launch …`, the previous exit (Android 11+) and an app update since the last run. */
    fun launch() {
        SupportKit.log("app") { "launch ${snapshot()}" }
        previousExit()?.let { SupportKit.log("app", it) }
        val version = "${SupportKit.config.version}(${SupportKit.config.build})"
        val before = prefs.getString("lastVersion", null)
        if (before != null && before != version) SupportKit.log("system", "update from=$before to=$version")
        prefs.edit { putString("lastVersion", version) }
    }

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SupportKit.log("app", "foreground")
                notificationsChanged()
            }
            override fun onStop(owner: LifecycleOwner) = SupportKit.log("app", "background")
        })
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) SupportKit.log("app") { "trim memory level=$level ${memory()}" }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {
                val locale = Locale.getDefault().toLanguageTag()
                if (locale != prefs.getString("lastLocale", locale)) SupportKit.log("system", "locale $locale")
                prefs.edit { putString("lastLocale", locale) }
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = SupportKit.log("app") { "low memory ${memory()}" }
        })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> SupportKit.log("system", "low power ${if (power.isPowerSaveMode) "on" else "off"}")
                    Intent.ACTION_POWER_CONNECTED -> SupportKit.log("system") { "power charging ${battery()}" }
                    Intent.ACTION_POWER_DISCONNECTED -> SupportKit.log("system") { "power unplugged ${battery()}" }
                    Intent.ACTION_TIMEZONE_CHANGED -> SupportKit.log("system", "time zone ${TimeZone.getDefault().id}")
                    Intent.ACTION_BATTERY_CHANGED -> batteryStep(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.addThermalStatusListener(context.mainExecutor) { SupportKit.log("system", "thermal ${thermal()}") }
        }
    }

    /** Battery level in steps of 10 % (the broadcast comes far more often). */
    private fun batteryStep(intent: Intent) {
        val pct = percent(intent) ?: return
        val step = pct / 10
        if (lastBatteryStep >= 0 && step != lastBatteryStep) SupportKit.log("system") { "battery level=$pct% ${chargingText(intent)}" }
        lastBatteryStep = step
    }

    private fun notificationsChanged() {
        val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val before = if (prefs.contains("notifications")) prefs.getBoolean("notifications", allowed) else null
        if (before != null && before != allowed) SupportKit.log("system", "notifications ${if (allowed) "allowed" else "blocked"}")
        prefs.edit { putBoolean("notifications", allowed) }
    }

    /** Why the previous process ended (Android 11+), once per exit. */
    private fun previousExit(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val seen = prefs.getLong("lastExitAt", 0)
        if (info.timestamp <= seen) return null
        prefs.edit { putLong("lastExitAt", info.timestamp) }
        return "previous exit reason=${exitReason(info.reason)} at=${isoStamp(info.timestamp)} importance=${info.importance} pss=${info.pss / 1024}MB"
    }

    private fun isoStamp(millis: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
            .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

    /** `battery=82% charging=true lowPower=false thermal=nominal memory=123MB` */
    fun snapshot(): String = "${battery()} lowPower=${power.isPowerSaveMode} thermal=${thermal()} ${memory()}"

    private fun battery(): String {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "battery=unknown"
        val pct = percent(i)?.let { "$it%" } ?: "unknown"
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val t = if (temp != Int.MIN_VALUE) " batteryTemp=${temp / 10.0}C" else ""
        return "battery=$pct ${chargingText(i)}$t"
    }

    private fun percent(i: Intent): Int? {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun chargingText(i: Intent): String {
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return "charging=$plugged"
    }

    private fun memory(): String = "memory=${Debug.getPss() / 1024}MB"

    fun thermal(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unknown"
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_OTHER -> "other"
        13 -> "freezer"
        14 -> "package-state-change"
        15 -> "package-updated"
        else -> "unknown-$reason"
    }
}
