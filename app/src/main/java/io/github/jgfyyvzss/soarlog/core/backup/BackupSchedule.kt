package io.github.jgfyyvzss.soarlog.core.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** When an automatic backup is due, and how backup files are named and pruned. */
object BackupSchedule {
    const val DEFAULT_AFTER_CHANGES = 20
    const val DEFAULT_AFTER_DAYS = 7
    /** Each backup is a full copy with its images, so 5 are kept (was 10 for .json files). */
    const val KEEP_FILES = 5
    /** The Home screen nudges when the last backup is older than this. */
    const val STALE_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Due when something changed since the last backup and either trigger is met. A trigger of 0 is off, so with
     * both at 0 automatic backups never run.
     */
    fun isDue(changes: Int, lastBackupAt: Long?, now: Long, afterChanges: Int, afterDays: Int): Boolean {
        if (changes <= 0) return false
        if (afterChanges > 0 && changes >= afterChanges) return true
        if (afterDays > 0 && (lastBackupAt == null || now - lastBackupAt >= afterDays * DAY_MS)) return true
        return false
    }

    fun isStale(lastBackupAt: Long?, now: Long): Boolean = lastBackupAt == null || now - lastBackupAt > STALE_DAYS * DAY_MS

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val namePattern = Regex("""^glider-logbook-\d{8}-\d{6}\.zip$""")

    fun fileName(at: LocalDateTime): String = "glider-logbook-${at.format(stamp)}.zip"

    /**
     * Backup files to delete so only the newest [keep] remain. Files not named like backups are never touched,
     * nor are old .json backups, which only an older app can restore.
     */
    fun filesToPrune(names: List<String>, keep: Int = KEEP_FILES): List<String> =
        names.filter { namePattern.matches(it) }.sortedDescending().drop(keep)
}
