package io.github.jgfyyvzss.soarlog.data.lxnav

import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavTrace
import java.io.Closeable
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The logger communication log: one text file per session, for finding out why a download stalls. The folder is
 * `Android/media/<package>/logs` where shared storage is available, so a file manager can reach the logs without
 * the logger (decided 2026-11-03), and the app's cache otherwise. Each line has the local time to the millisecond
 * and the milliseconds since the session began. The newest [KEEP] files are kept; the pilot shares one from the
 * logger screen or copies it with a file manager. It holds the logger's own data (the IGC lines, which name the
 * pilot), so it must never be committed.
 */
class LoggerLog(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    /** Starts a new log file for one session; close it when the session ends. */
    fun start(device: String, purpose: String): Session {
        dir.mkdirs()
        val started = now()
        val stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(started), ZoneId.systemDefault()).format(NAME_TIME)
        val file = File(dir, "lxnav-$stamp-$purpose.log")
        prune()
        return Session(file, started).also { it.line("$purpose with $device") }
    }

    /**
     * Moves logs from [old] (the cache folder used before 2026-11-03) here, keeping their names, then keeps the
     * newest [KEEP]. Copied and deleted rather than renamed, since the two folders can be on different storage.
     */
    @Synchronized
    fun moveFrom(old: File) {
        if (old.absoluteFile == dir.absoluteFile || !old.isDirectory) return
        dir.mkdirs()
        old.listFiles { f -> f.isFile && f.name.startsWith("lxnav-") && f.name.endsWith(".log") }.orEmpty().forEach { f ->
            val target = File(dir, f.name)
            if (!target.exists()) runCatching { f.copyTo(target) }
            if (target.exists()) f.delete()
        }
        old.delete()
        files().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    /** The newest log, or null when there is none. */
    fun latest(): File? = files().maxByOrNull { it.name }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.startsWith("lxnav-") && f.name.endsWith(".log") }?.toList().orEmpty()

    /** Leaves room for the next file within [KEEP]. */
    private fun prune() {
        files().sortedByDescending { it.name }.drop(KEEP - 1).forEach { it.delete() }
    }

    inner class Session internal constructor(private val file: File, private val started: Long) : LxnavTrace, Closeable {
        private val out: Writer = file.bufferedWriter(Charsets.UTF_8)

        @Synchronized
        override fun line(text: String) {
            val t = now()
            val clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).format(LINE_TIME)
            runCatching {
                out.write("$clock +${t - started}ms $text\n")
                // Flushed each line, so the log survives a crash or the app being stopped mid-download.
                out.flush()
            }
        }

        @Synchronized
        override fun close() {
            runCatching { out.close() }
        }
    }

    companion object {
        /** Raised from 5 on 2026-11-03: a test run writes several logs, and earlier runs were being lost. */
        const val KEEP = 20
        private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
