package be.webweaver.tools.supportkit

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file in a pack, as the manifest lists it. */
data class PackEntry(val id: String, val files: List<String>, val sensitivity: String, val bytes: Long)

/**
 * `support-pack/2` (SPEC §7): a zip with `manifest.json`, `log.txt`, `README.txt` and the items the
 * owner ticked. Made in the cache, deleted at the next start.
 */
object SupportPack {
    const val FORMAT = "support-pack/2"
    const val LOG_ID = "log"

    /** [values]: item id → value (0 = leave out); [LOG_ID] for the log. Blocking: call off the main thread. */
    fun build(values: Map<String, Int>): File? = runCatching {
        val kit = SupportKit
        val stamp = kit.fileStamp()
        val dir = File(kit.cacheDir(), "${kit.config.filePrefix}-support-$stamp").apply { deleteRecursively(); mkdirs() }
        val entries = mutableListOf<PackEntry>()
        val readme = mutableListOf<String>()
        val res = kit.app.resources

        if ((values[LOG_ID] ?: 1) != 0 && kit.exportLogTo(File(dir, "log.txt"))) {
            entries += PackEntry(LOG_ID, listOf("log.txt"), Sensitivity.NUMBERS.json(), File(dir, "log.txt").length())
            readme += "log.txt: " + res.getString(R.string.supportkit_readme_log)
        }
        for (item in kit.config.packItems) {
            val value = values[item.id] ?: item.defaultValue
            if (value == 0) continue
            val files = runCatching { item.write(dir, value) }.getOrDefault(emptyList())
            if (files.isEmpty()) continue
            val bytes = files.sumOf { sizeOf(File(dir, it)) }
            entries += PackEntry(item.id, files, item.sensitivity.json(), bytes)
            readme += res.getString(item.readme)
        }
        File(dir, "README.txt").writeText(readmeText(res.getString(R.string.supportkit_readme_title, kit.app.applicationInfo.loadLabel(kit.app.packageManager).toString(), stamp), readme, res.getString(R.string.supportkit_readme_included), res.getString(kit.config.texts.notIncluded)))
        File(dir, "manifest.json").writeText(manifest(kit.facts(), Instant.now(), SupportKit.VERSION, entries))

        val zip = File(kit.cacheDir(), "${dir.name}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { f ->
                out.putNextEntry(ZipEntry("${dir.name}/${f.relativeTo(dir).invariantSeparatorsPath}"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        dir.deleteRecursively()
        kit.log("log", "pack made parts=${entries.joinToString(",") { it.id }} bytes=${zip.length()}")
        zip
    }.getOrNull()

    /** Pure: the manifest JSON (unit-tested). */
    fun manifest(facts: DeviceFacts, createdAt: Instant, kitVersion: String, entries: List<PackEntry>, zone: ZoneId = ZoneId.systemDefault()): String =
        JSONObject().apply {
            put("format", FORMAT)
            put("appId", facts.appId)
            put("version", facts.version)
            put("build", facts.build)
            put("platform", "android")
            put("os", facts.os)
            put("deviceModel", facts.deviceModel)
            put("locale", facts.locale)
            put("timeZone", facts.timeZone)
            put("createdAt", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(createdAt.atZone(zone)))
            put("kitVersion", kitVersion)
            put("logFormat", LineFormat.FORMAT)
            put("items", JSONArray().apply {
                entries.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("files", JSONArray(e.files))
                        put("sensitivity", e.sensitivity)
                        put("bytes", e.bytes)
                    })
                }
            })
        }.toString(2).replace("\\/", "/") // Android's org.json escapes every "/"

    /** Pure: README.txt in the phone's language. */
    fun readmeText(title: String, included: List<String>, includedHeading: String, notIncluded: String): String = buildString {
        appendLine(title)
        appendLine()
        appendLine(includedHeading)
        included.forEach { appendLine("- $it") }
        appendLine()
        appendLine(notIncluded)
    }

    private fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    private fun Sensitivity.json(): String = name.lowercase(Locale.ROOT)
}
