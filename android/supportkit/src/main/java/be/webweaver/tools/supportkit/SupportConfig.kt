package be.webweaver.tools.supportkit

import androidx.annotation.StringRes

/**
 * What an app plugs into the kit (SPEC §6). Everything app-specific lives here; the kit itself
 * knows no app.
 */
class SupportConfig(
    /** File names: `<filePrefix>-log-….txt`, `<filePrefix>-support-….zip`. */
    val filePrefix: String,
    val version: String,
    val build: String,
    /** The app's own categories with a one-line description each (listed in the header). */
    val appCategories: Map<String, String> = emptyMap(),
    /** Small always-on log (SPEC §12.1): a per-app default. Off = nothing is written while support mode is off. */
    val smallLog: Boolean = false,
    /** Categories the small log keeps; the rest needs support mode. */
    val smallLogCategories: Set<String> = SupportKit.CORE_CATEGORIES,
    val smallLogDays: Int = 14,
    val smallLogMaxBytes: Long = 100_000,
    val supportModeMaxBytes: Long = 1_000_000,
    val smallLogHeartbeatMinutes: Int = 60,
    val supportModeHeartbeatMinutes: Int = 10,
    /** Extra `key=value` pairs for every heartbeat line (called on the main thread; keep it cheap). */
    val heartbeatValues: () -> String = { "" },
    /** Strings that must never appear in a log line (a pet's name, an e-mail); asked for at every write. */
    val privateStrings: () -> Collection<String> = { emptyList() },
    val packItems: List<PackItem> = emptyList(),
    val texts: SupportTexts,
)

/** The app's own words on the support screen and in the pack's README (EN and NL in the app). */
data class SupportTexts(
    /** Above the support mode switch: what it's for. */
    @StringRes val intro: Int,
    /** The confirmation when switching it on: what is kept. */
    @StringRes val consent: Int,
    /** Bullets under "What is kept on this device". */
    val kept: List<Int>,
    /** The small log's explanation (only shown when [SupportConfig.smallLog] is on). */
    @StringRes val smallLog: Int? = null,
    /** README: what is not in the pack. */
    @StringRes val notIncluded: Int,
)
