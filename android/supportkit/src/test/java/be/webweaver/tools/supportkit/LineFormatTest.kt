package be.webweaver.tools.supportkit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LineFormatTest {
    private val brussels = ZoneId.of("Europe/Brussels")
    private val at = Instant.parse("2026-11-05T20:14:03Z")

    @Test
    fun aLineHasTheLocalTimeWithItsOffset() {
        assertEquals("2026-11-05 21:14:03 +01:00 [listen] start sensitivity=MEDIUM\n", LineFormat.line(at, "listen", "start sensitivity=MEDIUM", zone = brussels))
        // Summer time shows as +02:00, so a DST change is visible in the log.
        assertEquals("2026-07-01 12:00:00 +02:00 [app] launch\n", LineFormat.line(Instant.parse("2026-07-01T10:00:00Z"), "app", "launch", zone = brussels))
    }

    @Test
    fun oneEventIsOneLine() {
        assertEquals("2026-11-05 21:14:03 +01:00 [app] a b\n", LineFormat.line(at, "app", "a\nb", zone = brussels))
    }

    @Test
    fun privateStringsNeverReachTheLog() {
        val line = LineFormat.line(at, "voice", "played Brutus! Quiet! for dirk@example.com", listOf("brutus", "dirk@example.com"), brussels)
        assertFalse(line.contains("Brutus", ignoreCase = true))
        assertFalse(line.contains("dirk@example.com"))
        assertTrue(line.contains("${LineFormat.PRIVATE}! Quiet!"))
        // One letter or blank is no name: it would wipe every "a" from the log.
        assertEquals("a b", LineFormat.redact("a b", listOf("a", " ", "")))
    }

    @Test
    fun categoriesAreLowercaseWords() {
        assertTrue(LineFormat.isValidCategory("hb"))
        assertTrue(LineFormat.isValidCategory("bark_window2"))
        assertFalse(LineFormat.isValidCategory("Listen"))
        assertFalse(LineFormat.isValidCategory("a b"))
        assertFalse(LineFormat.isValidCategory(""))
    }

    @Test
    fun theTimeIsReadBack() {
        assertEquals(at, LineFormat.timeOf(LineFormat.line(at, "app", "x", zone = brussels)))
        assertNull(LineFormat.timeOf("format: support-log/1"))
        assertNull(LineFormat.timeOf(""))
    }

    @Test
    fun pruningKeepsTheLastDays() {
        val old = LineFormat.line(Instant.parse("2026-10-01T08:00:00Z"), "app", "launch", zone = brussels).trimEnd()
        val broken = "continuation without a stamp"
        val recent = LineFormat.line(Instant.parse("2026-11-04T08:00:00Z"), "hb", "battery=80%", zone = brussels).trimEnd()
        val kept = LineFormat.prune(listOf(old, broken, recent, broken), Instant.parse("2026-10-22T00:00:00Z"))
        assertEquals(listOf(recent, broken), kept)
    }

    private val facts = DeviceFacts("com.example.app", "0.1", "162", "Android 16 (API 36)", "samsung SM-A556B", "nl-BE", "Europe/Brussels")

    @Test
    fun theHeaderNamesFormatAppDeviceAndCategories() {
        val header = LineFormat.header(facts, "0.1.0", mapOf("listen" to "listening", "app" to "lifecycle"))
        val lines = header.lines()
        assertEquals("format: support-log/1", lines[0])
        assertTrue("version: 0.1 (162)" in lines)
        assertTrue("platform: android" in lines)
        assertTrue("timeZone: Europe/Brussels" in lines)
        assertTrue(lines.indexOf("  app: lifecycle") < lines.indexOf("  listen: listening"))
        assertTrue(header.endsWith("\n\n"))
    }

    @Test
    fun theManifestIsSupportPack2() {
        val text = SupportPack.manifest(facts, at, "0.1.0", listOf(PackEntry("log", listOf("log.txt"), "numbers", 1234), PackEntry("clips", listOf("clips"), "audio", 99)), brussels)
        // Readable as text too: no escaped slashes.
        assertTrue(text.contains("\"support-pack/2\""))
        val json = JSONObject(text)
        assertEquals("support-pack/2", json.getString("format"))
        assertEquals("support-log/1", json.getString("logFormat"))
        assertEquals("android", json.getString("platform"))
        assertEquals("2026-11-05T21:14:03+01:00", json.getString("createdAt"))
        val items = json.getJSONArray("items")
        assertEquals("audio", items.getJSONObject(1).getString("sensitivity"))
        assertEquals(1234, items.getJSONObject(0).getLong("bytes"))
    }

    @Test
    fun theReadmeListsWhatIsAndIsNotIncluded() {
        val text = SupportPack.readmeText("App support pack, 20261105-2114", listOf("log.txt: the log"), "Included:", "Not included: names.")
        assertEquals("App support pack, 20261105-2114\n\nIncluded:\n- log.txt: the log\n\nNot included: names.\n", text)
    }
}
