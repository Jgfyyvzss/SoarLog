package io.github.jgfyyvzss.soarlog.core.igc

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An IGC file the app has received, with its two jobs: logging its flights in the logbook and uploading it to
 * WeGlide. Either, both or neither may be wanted, so each is shown wherever the file is.
 */
@Serializable
data class StoredIgc(
    /** From the file's content, so receiving the same file again finds this entry and its statuses. */
    val id: String,
    val name: String,
    val receivedAt: Long,
    val sizeBytes: Long,
    /** The flying segments found in it; 0 when the file has none, so there is nothing to log. */
    val flights: Int,
    /** The first segment's launch, as epoch milliseconds, and its duration, for showing the file. */
    val firstLaunchAt: Long? = null,
    val firstDurationMinutes: Int? = null,
    val gliderIdHeader: String? = null,
    /** The segments saved as logbook flights. */
    val logged: Set<Int> = emptySet(),
    /** The glider of the last flight saved from this file, which supplies the WeGlide aircraft. */
    val loggedGliderId: String? = null,
    val uploaded: Boolean = false,
    val weglideFlightId: Long? = null,
    /** The file's name in the files folder; null for a file stored before 2026-10-28, kept as `<id>.igc`. */
    val fileName: String? = null,
) {
    val fullyLogged: Boolean get() = flights > 0 && logged.size >= flights
    val firstLaunch: Instant? get() = firstLaunchAt?.let(Instant::ofEpochMilli)
}

/**
 * Received IGC files, kept byte for byte so a long logger download survives a crash, a flat battery or closing the
 * app, and a file can be uploaded later on Wi-Fi. The files sit in [dir] under their own names, where the pilot can
 * see and copy them; their details and statuses sit apart in [metaDir], which can be private. The logbook is not an
 * archive of IGC files: each is deleted when it is [MAX_AGE_MS] old, when more than [MAX_FILES] are kept (oldest
 * first), or when the pilot removes it. Only files this library wrote are ever deleted from [dir].
 */
class IgcLibrary(
    private val dir: File,
    private val metaDir: File = dir,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Stores a file, or finds the copy already stored, and returns its entry. */
    @Synchronized
    fun add(name: String, bytes: ByteArray): StoredIgc {
        dir.mkdirs()
        metaDir.mkdirs()
        val id = idOf(bytes)
        get(id)?.let { return it }
        val segments = when (val r = IgcParser.parse(bytes.toString(Charsets.ISO_8859_1))) {
            is IgcRead.Ok -> r.track to IgcFlights.segments(r.track)
            is IgcRead.Refused -> null
        }
        val first = segments?.second?.firstOrNull()
        val entry = StoredIgc(
            id = id,
            name = name.ifBlank { "flight.igc" },
            receivedAt = now(),
            sizeBytes = bytes.size.toLong(),
            flights = segments?.second?.size ?: 0,
            firstLaunchAt = first?.launch?.time?.toEpochMilli(),
            firstDurationMinutes = first?.durationMinutes,
            gliderIdHeader = segments?.first?.gliderId,
            fileName = freeName(name),
        )
        write(file(entry), bytes)
        write(meta(id), json.encodeToString(StoredIgc.serializer(), entry).toByteArray(Charsets.UTF_8))
        prune()
        return entry
    }

    /** The entry, or null when it is unknown or its file has gone, for example deleted with a file manager. */
    @Synchronized
    fun get(id: String): StoredIgc? {
        val f = meta(id)
        if (!f.exists()) return null
        val entry = runCatching { json.decodeFromString(StoredIgc.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
        return entry?.takeIf { file(it).exists() }
    }

    /** The file's bytes exactly as received. */
    @Synchronized
    fun bytes(id: String): ByteArray? = get(id)?.let(::file)?.takeIf { it.exists() }?.readBytes()

    /**
     * Moves files stored the old way in [old] (`<id>.igc` with `<id>.json` beside it) here, under their own names,
     * keeping their details, then deletes the old folder. Run once at start-up.
     */
    @Synchronized
    fun moveFrom(old: File) {
        if (old.absoluteFile == dir.absoluteFile || !old.isDirectory) return
        dir.mkdirs()
        metaDir.mkdirs()
        old.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { m ->
            val entry = runCatching { json.decodeFromString(StoredIgc.serializer(), m.readText(Charsets.UTF_8)) }.getOrNull()
            val bytes = File(old, "${m.nameWithoutExtension}.igc").takeIf { it.exists() }?.readBytes()
            if (entry != null && bytes != null && meta(entry.id).exists().not()) {
                val moved = entry.copy(fileName = freeName(entry.name))
                write(file(moved), bytes)
                write(meta(moved.id), json.encodeToString(StoredIgc.serializer(), moved).toByteArray(Charsets.UTF_8))
            }
        }
        old.deleteRecursively()
        prune()
    }

    /** Newest first, after removing expired files. */
    @Synchronized
    fun list(): List<StoredIgc> {
        prune()
        return entries().sortedByDescending { it.receivedAt }
    }

    @Synchronized
    fun update(id: String, change: (StoredIgc) -> StoredIgc): StoredIgc? {
        val updated = change(get(id) ?: return null)
        write(meta(id), json.encodeToString(StoredIgc.serializer(), updated).toByteArray(Charsets.UTF_8))
        return updated
    }

    @Synchronized
    fun remove(id: String) {
        val f = meta(id)
        val entry = runCatching { json.decodeFromString(StoredIgc.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
        entry?.let { file(it).delete() }
        f.delete()
    }

    /**
     * Deletes files past their age, then the oldest beyond the count, then details whose file has gone and any
     * half-written leftovers. Nothing else in [dir] is touched, since the pilot can put other files there.
     */
    @Synchronized
    fun prune() {
        val all = entries()
        val cutoff = now() - MAX_AGE_MS
        val expired = all.filter { it.receivedAt < cutoff }
        val extra = (all - expired.toSet()).sortedByDescending { it.receivedAt }.drop(MAX_FILES)
        (expired + extra).forEach { remove(it.id) }
        metaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().filter { get(it.nameWithoutExtension) == null }.forEach { it.delete() }
        listOf(dir, metaDir).distinct().forEach { d -> d.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() } }
    }

    private fun entries(): List<StoredIgc> =
        metaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { get(it.nameWithoutExtension) }

    private fun meta(id: String) = File(metaDir, "$id.json")

    private fun file(entry: StoredIgc) = File(dir, entry.fileName ?: "${entry.id}.igc")

    /**
     * The received name made safe for a file name and ending in `.igc`, with `-2`, `-3` and so on added when another
     * file already has it.
     */
    private fun freeName(received: String): String {
        val clean = received.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.').ifBlank { "flight.igc" }
        val withExtension = if (clean.endsWith(".igc", ignoreCase = true)) clean else "$clean.igc"
        val base = withExtension.substring(0, withExtension.length - 4)
        val extension = withExtension.takeLast(4)
        return generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) withExtension else "$base-$n$extension" }
            .first { !File(dir, it).exists() && !File(dir, "$it.tmp").exists() }
    }

    /** Writes through a temporary file, so a crash never leaves a half-written file under the real name. */
    private fun write(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        /** Seven days, as the pilot chose: enough to download at the field and upload at home after a weekend away. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** The pilot's limit (2026-10-28), now that the files sit in a folder the pilot can see. */
        const val MAX_FILES = 20

        fun idOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
    }
}
