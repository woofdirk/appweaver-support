package be.webweaver.tools.supportkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `support-log/1` (SPEC §4): `2026-11-05 21:14:03 +01:00 [category] message key=value`.
 * Pure, so the format, redaction and pruning are unit-tested.
 */
object LineFormat {
    const val FORMAT = "support-log/1"
    const val PRIVATE = "‹private›"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
    private val categoryRegex = Regex("[a-z0-9_]+")

    fun isValidCategory(category: String): Boolean = categoryRegex.matches(category)

    fun stamp(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = stamp.format(at.atZone(zone))

    /** One line, newline included. Line breaks inside the message are flattened: one event per line. */
    fun line(at: Instant, category: String, message: String, privateStrings: Collection<String> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): String =
        "${stamp(at, zone)} [$category] ${redact(message.replace('\n', ' ').replace('\r', ' '), privateStrings)}\n"

    /**
     * Replaces strings the app marked private (a pet's name, an e-mail address) — a safety net, the
     * app should never log them. Case-insensitive; strings shorter than 2 characters are ignored.
     */
    fun redact(text: String, privateStrings: Collection<String>): String =
        privateStrings.filter { it.trim().length >= 2 }.sortedByDescending { it.length }
            .fold(text) { acc, s -> acc.replace(s.trim(), PRIVATE, ignoreCase = true) }

    /** The time of a line, or null for a line that doesn't start with a stamp (a header, a broken line). */
    fun timeOf(line: String): Instant? =
        if (line.length < 26) null else runCatching { ZonedDateTime.parse(line.substring(0, 26), stamp).toInstant() }.getOrNull()

    /** Keeps lines at or after [cutoff]; lines without a stamp stay with the line before them. */
    fun prune(lines: List<String>, cutoff: Instant): List<String> {
        var keep = false
        return lines.filter { line ->
            timeOf(line)?.let { keep = !it.isBefore(cutoff) }
            keep
        }
    }

    /** The export header (SPEC §4), followed by one empty line. */
    fun header(facts: DeviceFacts, kitVersion: String, categories: Map<String, String>): String = buildString {
        appendLine("format: $FORMAT")
        appendLine("app: ${facts.appId}")
        appendLine("version: ${facts.version} (${facts.build})")
        appendLine("platform: android")
        appendLine("os: ${facts.os}")
        appendLine("device: ${facts.deviceModel}")
        appendLine("locale: ${facts.locale}")
        appendLine("timeZone: ${facts.timeZone}")
        appendLine("kit: $kitVersion")
        appendLine("categories:")
        categories.toSortedMap().forEach { (name, description) -> appendLine("  $name: $description") }
        appendLine()
    }
}

/** What the header and the manifest say about the app and the device. */
data class DeviceFacts(
    val appId: String,
    val version: String,
    val build: String,
    val os: String,
    val deviceModel: String,
    val locale: String,
    val timeZone: String,
)
