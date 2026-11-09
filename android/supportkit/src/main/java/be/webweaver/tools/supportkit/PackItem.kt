package be.webweaver.tools.supportkit

import androidx.annotation.StringRes
import java.io.File

/** How private an item is (SPEC §6); `AUDIO` is never ticked by default and shows a warning. */
enum class Sensitivity { NUMBERS, SETTINGS, HISTORY, AUDIO }

/**
 * One thing the owner can add to a support pack. [choices] = null is a switch (0 = off, 1 = on);
 * otherwise the owner picks one of them (value 0 = none), e.g. "Last 3" recordings.
 */
interface PackItem {
    val id: String
    @get:StringRes val title: Int
    val sensitivity: Sensitivity
    val defaultValue: Int
    val choices: List<PackChoice>? get() = null
    /** Shown under the item when it is on (e.g. "This is real sound from your home"). */
    @get:StringRes val warning: Int? get() = null
    /** README line ("detections.json: …"), in the phone's language. */
    @get:StringRes val readme: Int

    /** Writes the item's files into [dir]; returns their names relative to [dir] (empty = nothing to add). */
    fun write(dir: File, value: Int): List<String>

    /** About how many bytes [value] adds, for the screen (0 when unknown). */
    fun estimateBytes(value: Int): Long = 0
}

data class PackChoice(@StringRes val label: Int, val value: Int)
