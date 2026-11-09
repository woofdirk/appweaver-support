package be.webweaver.tools.supportkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The log files: two rotating parts in [dir], one writer thread (SPEC §2.6). Which lines are kept
 * is decided by [SupportKit]; this class only writes, rotates, prunes, exports and clears.
 */
internal class SupportLog(private val dir: File) {
    private val file = File(dir, "support.log")
    private val oldFile = File(dir, "support.1.log")
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "supportkit-log").apply { isDaemon = true } }
    private var stream: FileOutputStream? = null

    @Volatile var maxBytes: Long = 100_000

    private val _size = MutableStateFlow(0L)
    val sizeBytes: StateFlow<Long> = _size.asStateFlow()

    init { dir.mkdirs() }

    fun append(line: String, onRotated: (() -> Unit)? = null) {
        writer.execute {
            try {
                if (file.length() > maxBytes) {
                    closeStream()
                    oldFile.delete()
                    file.renameTo(oldFile)
                    onRotated?.invoke()
                }
                val s = stream ?: FileOutputStream(file, true).also { stream = it }
                s.write(line.toByteArray())
                s.flush()
                _size.value = file.length() + oldFile.length()
            } catch (_: Exception) {
                closeStream()
            }
        }
    }

    /** Waits until everything is on disk (a broadcast receiver or a dying process may end right after). */
    fun flush() {
        runCatching { writer.submit { runCatching { stream?.fd?.sync() } }.get(2, TimeUnit.SECONDS) }
    }

    /** Drops lines older than [cutoff] from both parts (the small log keeps 14 days). */
    fun prune(cutoff: Instant) {
        writer.execute {
            closeStream()
            for (f in listOf(oldFile, file)) {
                if (!f.exists()) continue
                runCatching {
                    val lines = f.readLines()
                    val kept = LineFormat.prune(lines, cutoff)
                    if (kept.size != lines.size) {
                        if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n", postfix = "\n"))
                    }
                }
            }
            _size.value = file.length() + oldFile.length()
        }
    }

    fun refreshSize() {
        writer.execute { _size.value = file.length() + oldFile.length() }
    }

    fun clear() {
        runCatching {
            writer.submit {
                closeStream()
                file.delete()
                oldFile.delete()
                _size.value = 0
            }.get(2, TimeUnit.SECONDS)
        }
    }

    /** [header] then the whole log, oldest first, into [target]. */
    fun exportTo(target: File, header: String): Boolean = runCatching {
        writer.submit<Boolean> {
            runCatching { stream?.flush() }
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                out.write(header.toByteArray())
                for (f in listOf(oldFile, file)) if (f.exists()) f.inputStream().use { it.copyTo(out) }
            }
            true
        }.get(5, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }
}
