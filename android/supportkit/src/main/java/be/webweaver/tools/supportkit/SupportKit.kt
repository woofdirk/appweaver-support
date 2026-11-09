package be.webweaver.tools.supportkit

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The support kit (appweaver-support, SPEC.md): a local log with an optional small always-on part,
 * support mode with consent, a support pack shared by the owner. Nothing is ever sent by the kit.
 *
 * Call [init] once from `Application.onCreate`; then [log] from anywhere (cheap, never blocks).
 */
object SupportKit {
    const val VERSION = "0.1.1"
    private const val TAG = "SupportKit"

    /** Kit-provided categories (SPEC §5): `log`, `app`, `system`, `hb`. */
    val CORE_CATEGORIES: Set<String> = setOf("log", "app", "system", "hb")
    private val CORE_DESCRIPTIONS = mapOf(
        "log" to "the log itself: on, off, cleared, rotated, pack made",
        "app" to "launch (with the previous exit's reason), foreground, background, memory",
        "system" to "charger, battery level, battery saver, thermal, time zone, locale, notifications, app update",
        "hb" to "heartbeat while the app runs",
    )

    private const val PREFS = "supportkit"
    private const val KEY_ON = "supportModeOn"

    @Volatile private var started = false
    internal lateinit var app: Application
        private set
    lateinit var config: SupportConfig
        private set
    private lateinit var file: SupportLog
    internal lateinit var events: SystemEvents
        private set
    private var heartbeat: Heartbeat? = null

    private val _supportMode = MutableStateFlow(false)
    /** Support mode is on (the orange bar shows). */
    val supportMode: StateFlow<Boolean> = _supportMode.asStateFlow()

    private val emptySize = MutableStateFlow(0L)
    /** Size of the log on disk, both parts. */
    val sizeBytes: StateFlow<Long> get() = if (started) file.sizeBytes else emptySize

    val isStarted: Boolean get() = started

    @Synchronized
    fun init(application: Application, config: SupportConfig) {
        if (started) return
        app = application
        this.config = config
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _supportMode.value = prefs.getBoolean(KEY_ON, false)
        file = SupportLog(File(application.noBackupFilesDir, "supportkit"))
        file.maxBytes = currentMaxBytes()
        started = true
        // Packs and exported logs from an earlier run: shared or abandoned, never kept.
        File(application.cacheDir, "supportkit").deleteRecursively()
        if (!_supportMode.value) {
            if (config.smallLog) file.prune(Instant.now().minus(Duration.ofDays(config.smallLogDays.toLong())))
            else file.clear()
        }
        file.refreshSize()
        events = SystemEvents(application)
        events.launch()
        events.attach()
        heartbeat = Heartbeat { minutes() }.also { it.start() }
    }

    /** Is anything written right now for [category]? Lets a caller skip building an expensive message. */
    fun isLogging(category: String): Boolean =
        started && (_supportMode.value || (config.smallLog && category in config.smallLogCategories))

    fun log(category: String, message: String) {
        if (!isLogging(category)) return
        if (!LineFormat.isValidCategory(category)) {
            Log.w(TAG, "bad category $category")
            return
        }
        val line = LineFormat.line(Instant.now(), category, message, runCatching { config.privateStrings() }.getOrDefault(emptyList()))
        file.append(line, onRotated = null)
    }

    /** As [log], but the message is only built when the line is kept. */
    inline fun log(category: String, message: () -> String) {
        if (isLogging(category)) log(category, message())
    }

    /** Blocks (up to 2 s) until written: for receivers and jobs that end right after logging. */
    fun flush() {
        if (started) file.flush()
    }

    /** Switches support mode; the owner has agreed to the consent text before [on] = true. */
    fun setSupportMode(on: Boolean) {
        if (!started || on == _supportMode.value) return
        if (on) {
            _supportMode.value = true
            file.maxBytes = currentMaxBytes()
            log("log", "on ${events.snapshot()}")
        } else {
            log("log", "off")
            _supportMode.value = false
            file.maxBytes = currentMaxBytes()
            if (!config.smallLog) file.clear()
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ON, on) }
        heartbeat?.reschedule()
    }

    fun clearLog() {
        if (!started) return
        file.clear()
        log("log", "cleared")
    }

    fun refreshSize() {
        if (started) file.refreshSize()
    }

    /** Every category this app logs, with what it means (header and pack manifest). */
    fun categories(): Map<String, String> = CORE_DESCRIPTIONS + config.appCategories

    fun facts(): DeviceFacts = DeviceFacts(
        appId = app.packageName,
        version = config.version,
        build = config.build,
        os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" "),
        locale = Locale.getDefault().toLanguageTag(),
        timeZone = ZoneId.systemDefault().id,
    )

    internal fun header(): String = LineFormat.header(facts(), VERSION, categories())

    internal fun cacheDir(): File = File(app.cacheDir, "supportkit").apply { mkdirs() }

    internal fun fileStamp(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).format(Instant.now().atZone(ZoneId.systemDefault()))

    internal fun exportLogTo(target: File): Boolean = file.exportTo(target, header())

    /** The whole log as one text file for the share sheet ("Send log to support"). */
    fun exportLog(): File? {
        if (!started) return null
        val out = File(cacheDir(), "${config.filePrefix}-log-${fileStamp()}.txt")
        return if (exportLogTo(out)) out.also { log("log", "exported bytes=${it.length()}") } else null
    }

    /** Opens the share sheet for [file]; where it goes is the owner's choice and isn't logged. */
    fun share(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.supportkit", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun currentMaxBytes(): Long = if (_supportMode.value) config.supportModeMaxBytes else config.smallLogMaxBytes

    private fun minutes(): Int? = when {
        _supportMode.value -> config.supportModeHeartbeatMinutes
        config.smallLog && "hb" in config.smallLogCategories -> config.smallLogHeartbeatMinutes
        else -> null
    }
}
