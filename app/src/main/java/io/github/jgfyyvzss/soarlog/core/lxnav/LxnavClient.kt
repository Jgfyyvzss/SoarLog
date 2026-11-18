package io.github.jgfyyvzss.soarlog.core.lxnav

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The values from the LXNAV section of the specification, kept in one place so they can be tuned. */
object LxnavParameters {
    /**
     * Adaptive lines per request: start here (also the most it grows back to), halve after a failed block down to
     * one line, and double after [ADAPTIVE_GROW_AFTER] clean blocks in a row. LXNAV reported 10-line requests as the
     * most reliable on the S100's short Bluetooth buffer (XCSoar issue #3229); to be confirmed on the logger.
     */
    const val ADAPTIVE_START = 10
    const val ADAPTIVE_GROW_AFTER = 4
    /** The wait for a block's replies, plus [REPLY_TIMEOUT_STEP_MS] per failure in a row, up to the maximum. */
    const val REPLY_TIMEOUT_MS = 4_000L
    /**
     * The first request of a download, before the logger has said the file's line count, may take a while as the
     * logger opens the file; XCSoar waits 20 seconds for it.
     */
    const val FIRST_REPLY_TIMEOUT_MS = 20_000L
    const val REPLY_TIMEOUT_STEP_MS = 1_000L
    const val REPLY_TIMEOUT_MAX_MS = 12_000L
    /**
     * Failures in a row before a download gives up. Any saved line resets the count, so scattered errors never add
     * up to an abort; XCSoar issue #1252 found 5 too few and 20 reliable.
     */
    const val ATTEMPTS = 20
    /** Logbook reads keep their own, smaller limit. */
    const val LOGBOOK_ATTEMPTS = 8
    /** Logbook entries read at a time: the newest first, then this many older ones for each More. */
    const val LOGBOOK_PAGE = 8
    /** The longest back-off between re-asks. */
    const val BACKOFF_MAX_MS = 5_000L
    /** Requests in a row with no row at all before the link counts as stalled; a dropped link counts at once. */
    const val STALL_AFTER_SILENT = 2
    /**
     * After a stall the link is closed and left for this long: the S100 keeps its side of a transfer open for about
     * 30 seconds after the phone stops, and re-asking on the same link does not recover (pilot's tests, 2026-10).
     */
    const val STALL_WAIT_MS = 35_000L
    /** Further waits before trying to reconnect again; then the download stops and asks for the logger to be restarted. */
    val RECOVERY_RETRY_MS = listOf(5_000L, 15_000L, 30_000L)
    /** A harmless query after each reconnect, its reply thrown away: the first exchange on a new link often fails. */
    const val WARM_UP = "PLXVC,LOGBOOKSIZE,R"
    /** A line that fails the IGC checks is accepted once this many identical copies have arrived. */
    const val IDENTICAL_TO_ACCEPT = 3
    /** The logbook read reconnects after this wait. */
    const val RECONNECT_WAIT_MS = 3_000L
    /**
     * A connect is given up after this long. Android's own limit is usually about 12 seconds, but on 2026-10-30 a
     * connect to the S100 hung for 26 seconds with nothing on screen until the pilot cancelled it.
     */
    const val CONNECT_TIMEOUT_MS = 15_000L
    /** Connect attempts at the start of a session; a fresh second attempt often works when the first does not. */
    const val CONNECT_ATTEMPTS = 2
    const val CONNECT_RETRY_WAIT_MS = 2_000L
    const val BACKOFF_MS = 1_000L
    /**
     * Draining stops after this much quiet or at most [DRAIN_MAX_MS]. With the NMEA stream flowing the link is never
     * quiet, so the maximum is what a drain costs; 3 seconds was measured on the phone (2026-10-28) and cut to 1.
     */
    const val DRAIN_QUIET_MS = 500L
    const val DRAIN_MAX_MS = 1_000L
    /**
     * No bytes at all for this long, not even the NMEA stream (at least 1 Hz), means the radio link has paused
     * rather than the request being lost. In the hangar test (2026-10-28) such pauses lasted 4 to 5 seconds, and the
     * reply then arrived in one burst, so the wait goes on while the link is paused.
     */
    const val LINK_PAUSE_MS = 2_500L
    /** A pause this long counts as a stall at once, without asking again first. */
    const val LINK_SILENT_MAX_MS = 15_000L
    /** After a pause ends, the rows held up behind it get this long to arrive, even past the usual wait. */
    const val RESUME_GRACE_MS = 2_000L
    /** How often the wait is reconsidered while the link is quiet. */
    const val WAIT_STEP_MS = 500L
    /** Measured on the S100: about 2.6 KB/s. */
    const val BYTES_PER_SECOND = 2_600L
}

/** One entry of the logger's logbook. The start time is UTC; the logbook's end time is always 00:00:00 and is ignored. */
data class LxnavFile(val name: String, val date: LocalDate?, val startUtc: LocalTime?, val sizeBytes: Long) {
    val start: Instant? get() = if (date != null && startUtc != null) date.atTime(startUtc).toInstant(ZoneOffset.UTC) else null
    val estimatedSeconds: Long get() = sizeBytes / LxnavParameters.BYTES_PER_SECOND
}

/**
 * The logbook entry numbers to read next, newest first, after the [alreadyRead] newest of [count]; empty when every
 * entry has been read. The only place that knows which end of the logbook is the newest.
 *
 * Assumed (pilot, 2026-11-18, not yet confirmed on the S100): entry 1 is the newest, so pages run 1 to 8, 9 to 16
 * and so on. If entry 1 proves to be the oldest, this becomes `(count - alreadyRead - PAGE + 1).coerceAtLeast(1)..
 * (count - alreadyRead)`; nothing else changes, since the list on screen is sorted by start time either way.
 */
fun logbookPage(count: Int, alreadyRead: Int, size: Int = LxnavParameters.LOGBOOK_PAGE): IntRange {
    val first = alreadyRead + 1
    return first..minOf(count, alreadyRead + size)
}

/**
 * One page of the logbook: its [files] (entries with an unreadable date left out), the logbook's entry [count] when
 * read, and how many entries have been [read] so far, newest first, including this page's. [fromStart] means the
 * page is the newest one, so it replaces any earlier pages rather than adding to them.
 */
data class LxnavLogbookPage(val files: List<LxnavFile>, val count: Int, val read: Int, val fromStart: Boolean) {
    val more: Boolean get() = read < count
}

/**
 * Lines per FLIGHT request, adaptive (see [LxnavParameters.ADAPTIVE_START]). [fixed] keeps every request at that
 * size; only the tests use it.
 */
class BlockSize(private val fixed: Int? = null) {
    init {
        require(fixed == null || fixed >= 1) { "a block has at least one line" }
    }

    var lines: Int = fixed ?: LxnavParameters.ADAPTIVE_START
        private set
    private var clean = 0

    /** A block arrived complete. */
    fun good() {
        if (fixed != null || lines >= LxnavParameters.ADAPTIVE_START) return
        if (++clean >= LxnavParameters.ADAPTIVE_GROW_AFTER) {
            lines = minOf(LxnavParameters.ADAPTIVE_START, lines * 2)
            clean = 0
        }
    }

    /** A block failed or was cut short. */
    fun bad() {
        clean = 0
        if (fixed == null) lines = maxOf(1, lines / 2)
    }

    override fun toString() = if (fixed == null) "adaptive, now $lines" else "fixed $fixed"
}

/**
 * Connects, giving up after [LxnavParameters.CONNECT_TIMEOUT_MS]. Cancelling the connect closes the half-open
 * socket (see the Android connector), so a hung attempt does not linger.
 */
internal suspend fun connectWithin(connector: LxnavConnector): LxnavLink =
    withTimeoutOrNull(LxnavParameters.CONNECT_TIMEOUT_MS) { connector.connect() }
        ?: throw IOException("connect timed out after ${LxnavParameters.CONNECT_TIMEOUT_MS} ms")

/** Lines saved so far out of the file's total, once the logger has said it. */
data class LxnavProgress(val lines: Int, val totalLines: Int?, val resumedFrom: Int)

/**
 * Talks to an LXNAV logger with the `PLXVC` commands observed on the S100 and described in the specification.
 * Read-only: nothing on the logger is deleted, uploaded or changed.
 */
class LxnavClient(
    private val connector: LxnavConnector,
    /** Milliseconds from a steady clock; tests pass their virtual time. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Connects, resets any transfer, runs [block], and always sends FILE_CANCEL and closes when it ends. */
    suspend fun <T> session(block: suspend LxnavSession.() -> T): T {
        val link = run {
            var last: Exception? = null
            repeat(LxnavParameters.CONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(LxnavParameters.CONNECT_RETRY_WAIT_MS)
                try {
                    return@run connectWithin(connector)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            throw LxnavFailure(msg(MsgKey.LXNAV_CONNECT_FAILED), last)
        }
        val session = LxnavSession(connector, link, clock)
        try {
            session.reset()
            return session.block()
        } finally {
            withContext(NonCancellable) { session.end() }
        }
    }
}

class LxnavSession internal constructor(
    private val connector: LxnavConnector,
    private var link: LxnavLink,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val pending = StringBuilder()
    /** When the last bytes of any kind arrived, and when the last pause of the link ended. */
    private var lastByteAt = Long.MIN_VALUE
    private var pauseEndedAt = Long.MIN_VALUE
    /** Set when the last block ended because the link stayed silent for [LxnavParameters.LINK_SILENT_MAX_MS]. */
    private var lastBlockSilent = false

    internal suspend fun reset() {
        sendQuietly(CANCEL)
        drain()
    }

    internal suspend fun end() {
        sendQuietly(CANCEL)
        runCatching { link.close() }
    }

    /**
     * The next page of the logbook after the [alreadyRead] newest entries (0 for the first page): the logbook size,
     * then the entries [logbookPage] picks. Entries with an unreadable date are left out. If the logbook no longer
     * has the [knownCount] entries of the earlier pages, for example after a new flight, the entry numbers may have
     * moved, so the newest page is read instead and the result says it starts afresh.
     */
    suspend fun logbook(alreadyRead: Int = 0, knownCount: Int? = null): LxnavLogbookPage {
        val count = logbookSize()
        val from = if (knownCount != null && knownCount != count) 0 else alreadyRead
        val range = logbookPage(count, from)
        val files = if (range.isEmpty()) emptyList() else logbookEntries(range).filter { it.date != null }
        return LxnavLogbookPage(files, count, from + range.count(), fromStart = from == 0)
    }

    /**
     * The number of logbook entries. Older firmware answers with `PLXVC,LOGBOOK,A,<n>` instead of
     * `PLXVC,LOGBOOKSIZE,A,<n>`, so both are accepted; the four fields tell it apart from an entry.
     */
    private suspend fun logbookSize(): Int {
        repeat(LxnavParameters.LOGBOOK_ATTEMPTS) {
            try {
                drain()
                send("PLXVC,LOGBOOKSIZE,R")
                val count = withTimeoutOrNull(LxnavParameters.REPLY_TIMEOUT_MS) {
                    while (true) {
                        val f = nextBody().split(',')
                        val sizeReply = f.getOrNull(2) == "A" &&
                            (f.getOrNull(1) == "LOGBOOKSIZE" || (f.getOrNull(1) == "LOGBOOK" && f.size == 4))
                        if (sizeReply) return@withTimeoutOrNull f.getOrNull(3)?.toIntOrNull()
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                if (count != null && count >= 0) return count
            } catch (e: IOException) {
                reconnect()
            }
        }
        throw LxnavFailure(msg(MsgKey.LXNAV_NO_LOGBOOK))
    }

    /** The logbook entries numbered [range], in the logger's order. */
    private suspend fun logbookEntries(range: IntRange): List<LxnavFile> {
        repeat(LxnavParameters.LOGBOOK_ATTEMPTS) {
            try {
                drain()
                send("PLXVC,LOGBOOK,R,${range.first},${range.last + 1}")
                val rows = HashMap<Int, LxnavFile>()
                withTimeoutOrNull(LxnavParameters.REPLY_TIMEOUT_MS) {
                    while (rows.size < range.count()) {
                        val f = nextBody().split(',')
                        if (f.size < 10 || f[1] != "LOGBOOK" || f[2] != "A") continue
                        val index = f[3].toIntOrNull()?.takeIf { it in range } ?: continue
                        rows[index] = LxnavFile(f[5], parseDate(f[6]), parseTime(f[7]), f[9].toLongOrNull() ?: continue)
                    }
                }
                if (rows.size == range.count()) return range.map { rows.getValue(it) }
            } catch (e: IOException) {
                reconnect()
            }
        }
        throw LxnavFailure(msg(MsgKey.LXNAV_NO_LOGBOOK))
    }

    /**
     * Downloads a file by FLIGHT line ranges, saving the lines as they arrive to [partial] and resuming from it. The
     * lines are joined with CR LF after each, exactly as in the original file, and the size must match the
     * logbook's. [size] sets the lines per request, adaptive unless a test fixes it. Each line must also pass
     * [IgcLineCheck]; a line that fails is asked for again, and accepted only after identical copies. Failures are
     * counted only while they come one after another: a block that brings even one new line resets the count. Each
     * failure in a row waits a little longer for replies, and the wait goes on while the link is paused. During the
     * back-off after a failure, rows that arrive late for the lines still wanted are kept. A dropped link, a link
     * silent for [LxnavParameters.LINK_SILENT_MAX_MS], or requests with no reply at all count as a stall: the link is
     * closed, left for the logger's own timeout, reconnected and warmed up. The download stops after
     * [LxnavParameters.ATTEMPTS] failures in a row, or when the logger cannot be reached again.
     */
    suspend fun download(
        file: LxnavFile,
        partial: PartialDownload,
        size: BlockSize = BlockSize(),
        onProgress: (LxnavProgress) -> Unit,
    ): ByteArray {
        val lines = partial.resume(file).toMutableList()
        val resumedFrom = lines.size
        var total: Int? = null
        var failures = 0
        var silentInARow = 0
        var check = IgcLineCheck().also { c -> lines.forEach(c::accept) }
        /** A rejected line's content and how many identical copies have come, by line number. */
        val rejected = HashMap<Int, Pair<String, Int>>()

        /**
         * Keeps the rows that follow the saved lines in order and pass the checks, stopping at the first gap or
         * failed line, and says how many were kept and whether a line failed.
         */
        fun keep(rows: Map<Int, String>, fileTotal: Int, end: Int): Pair<Int, Boolean> {
            val fresh = mutableListOf<String>()
            var badLine = false
            var n = lines.size + 1
            while (n < min(end, fileTotal + 1) && n in rows) {
                val text = rows.getValue(n)
                val why = check.problem(text)
                if (why != null) {
                    val copies = rejected[n]?.takeIf { it.first == text }?.second?.plus(1) ?: 1
                    rejected[n] = text to copies
                    if (copies < LxnavParameters.IDENTICAL_TO_ACCEPT) {
                        badLine = true
                        break
                    }
                }
                check.accept(text)
                rejected.remove(n)
                fresh += text
                n++
            }
            if (fresh.isNotEmpty()) {
                partial.append(fresh)
                lines += fresh
                failures = 0
                onProgress(LxnavProgress(lines.size, fileTotal, resumedFrom))
            }
            return fresh.size to badLine
        }

        onProgress(LxnavProgress(lines.size, null, resumedFrom))
        while (total == null || lines.size < total) {
            // When resuming, ask again for the last saved line too: it gives the total, and checks the partial.
            val first = if (total == null && lines.isNotEmpty()) lines.size else lines.size + 1
            val width = size.lines
            val end = lines.size + 1 + width
            var dropped = false
            val timeout = if (total == null) LxnavParameters.FIRST_REPLY_TIMEOUT_MS
                else minOf(LxnavParameters.REPLY_TIMEOUT_MS + LxnavParameters.REPLY_TIMEOUT_STEP_MS * failures, LxnavParameters.REPLY_TIMEOUT_MAX_MS)
            val block = try {
                block(file.name, first, end, timeout, total)
            } catch (e: IOException) {
                dropped = true
                null
            }
            // A resumed download keeps nothing until the logger's copy of the last saved line has been compared.
            val unchecked = block != null && first == lines.size && first !in block.lines
            if (block != null && !unchecked) {
                total = block.total
                if (first == lines.size && block.lines[first] != lines.last()) {
                    // The saved lines do not belong to this file after all; start again.
                    partial.delete()
                    lines.clear()
                    partial.resume(file)
                    total = null
                    check = IgcLineCheck()
                    rejected.clear()
                    continue
                }
                // Keep every line that arrived in order after the saved ones and passes the checks, even from a
                // block cut short; stop at the first gap or failed line.
                val (_, badLine) = keep(block.lines, block.total, end)
                if (block.complete && !badLine) {
                    silentInARow = 0
                    size.good()
                    continue
                }
            }
            failures++
            size.bad()
            if (failures >= LxnavParameters.ATTEMPTS) throw LxnavFailure(msg(MsgKey.LXNAV_GAVE_UP, lines.size + 1))
            silentInARow = if (block == null) silentInARow + 1 else 0
            if (dropped || lastBlockSilent || silentInARow >= LxnavParameters.STALL_AFTER_SILENT) {
                if (!recover()) throw LxnavFailure(msg(MsgKey.LXNAV_GAVE_UP, lines.size + 1))
                silentInARow = 0
            } else {
                // Rows still on their way are wanted, so listen through the back-off instead of throwing input away,
                // and only then reset the transfer.
                val wait = minOf(LxnavParameters.BACKOFF_MS * failures, LxnavParameters.BACKOFF_MAX_MS)
                val late = try {
                    listenForLate(wait, lines.size + 1, total)
                } catch (e: IOException) {
                    emptyMap() // down after all; the next request finds out and counts as a stall
                }
                val known = total
                if (late.isNotEmpty() && known != null) {
                    val (kept, _) = keep(late, known, Int.MAX_VALUE)
                    if (kept > 0) silentInARow = 0
                }
                sendQuietly(CANCEL)
            }
        }
        val bytes = lines.joinToString("") { it + "\r\n" }.toByteArray(Charsets.ISO_8859_1)
        partial.delete()
        if (bytes.size.toLong() != file.sizeBytes) throw LxnavFailure(msg(MsgKey.LXNAV_SIZE_MISMATCH, bytes.size, file.sizeBytes))
        return bytes
    }

    /** The rows that arrived for one request; [complete] when every line in the range came. */
    private class Block(val total: Int, val lines: Map<Int, String>, val complete: Boolean)

    /**
     * Lines [first, end) as far as they arrived within [timeoutMs], or null when no row came at all. Once the file's
     * line count is [knownTotal], a row giving any other count ends the block like a differing copy.
     */
    private suspend fun block(name: String, first: Int, end: Int, timeoutMs: Long, knownTotal: Int?): Block? {
        // Anything already received belongs to an earlier request: throw it away without waiting.
        link.clearInput()
        pending.clear()
        send("PLXVC,FLIGHT,R,$name,$first,$end")
        val requestAt = clock()
        lastBlockSilent = false
        val got = HashMap<Int, String>()
        var seenTotal: Int? = null
        var conflict: Int? = null
        val done: Block? = run {
            while (true) {
                // The usual wait, longer while the link is silent (it has paused, not lost the request), and a short
                // grace for the rows held up behind a pause that has just ended.
                val now = clock()
                val heardAt = maxOf(lastByteAt, requestAt)
                val paused = now - heardAt >= LxnavParameters.LINK_PAUSE_MS
                val deadline = if (paused) maxOf(requestAt + timeoutMs, heardAt + LxnavParameters.LINK_SILENT_MAX_MS)
                    else maxOf(requestAt + timeoutMs, if (pauseEndedAt == Long.MIN_VALUE) Long.MIN_VALUE else pauseEndedAt + LxnavParameters.RESUME_GRACE_MS)
                if (now >= deadline) {
                    if (paused) lastBlockSilent = true
                    return@run null
                }
                val body = withTimeoutOrNull(minOf(deadline - now, LxnavParameters.WAIT_STEP_MS)) { nextBody() } ?: continue
                // PLXVC,FLIGHT,A,<empty>,<line>,<total>,<content>; the content may itself contain commas.
                val f = body.split(',', limit = 7)
                if (f.size < 7 || f[1] != "FLIGHT" || f[2] != "A") continue
                val number = f[4].toIntOrNull() ?: continue
                val total = f[5].toIntOrNull() ?: continue
                if (number !in first until end) continue // a stale reply to an earlier request
                if (knownTotal != null && total != knownTotal) {
                    seenTotal = knownTotal
                    conflict = number
                    return@run null
                }
                val earlier = got[number]
                if (earlier != null && earlier != f[6]) {
                    // Two different copies of one row: neither can be trusted.
                    conflict = number
                    return@run null
                }
                got[number] = f[6]
                seenTotal = total
                if ((first until min(end, total + 1)).all { it in got }) return@run Block(total, got, complete = true)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        if (done != null) return done
        conflict?.let { c -> return seenTotal?.let { Block(it, got.filterKeys { k -> k < c }, complete = false) } }
        return seenTotal?.let { Block(it, got, complete = false) }
    }

    /** The body of the next valid `PLXV` sentence; the NMEA stream and anything with a bad checksum are skipped. */
    private suspend fun nextBody(): String {
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) {
                pending.append(receive())
                continue
            }
            val line = pending.substring(0, newline)
            pending.delete(0, newline + 1)
            val body = Nmea.body(line) ?: continue
            if (body.startsWith("PLXV")) return body
        }
    }

    /** The next chunk from the link as text, noting when bytes arrive so link pauses can be told apart. */
    private suspend fun receive(): String {
        val text = String(link.receive(), Charsets.ISO_8859_1)
        val now = clock()
        if (lastByteAt != Long.MIN_VALUE && now - lastByteAt >= LxnavParameters.LINK_PAUSE_MS) pauseEndedAt = now
        lastByteAt = now
        return text
    }

    /**
     * Listens for [waitMs] and collects FLIGHT rows numbered [from] or later that give the file's [knownTotal]: the
     * late answer to a request already given up on. A row that arrives twice, differently, is dropped with all after it.
     */
    private suspend fun listenForLate(waitMs: Long, from: Int, knownTotal: Int?): Map<Int, String> {
        val late = HashMap<Int, String>()
        var cutAt = Int.MAX_VALUE
        withTimeoutOrNull(waitMs) {
            while (true) {
                val f = nextBody().split(',', limit = 7)
                if (f.size < 7 || f[1] != "FLIGHT" || f[2] != "A") continue
                val number = f[4].toIntOrNull()?.takeIf { it >= from } ?: continue
                if (knownTotal == null || f[5].toIntOrNull() != knownTotal) continue
                val earlier = late[number]
                if (earlier != null && earlier != f[6]) cutAt = minOf(cutAt, number) else late[number] = f[6]
            }
        }
        return late.filterKeys { it < cutAt }
    }

    /** Discards input until the link has been quiet for a moment, so old replies are not mistaken for new ones. */
    private suspend fun drain() {
        withTimeoutOrNull(LxnavParameters.DRAIN_MAX_MS) {
            while (withTimeoutOrNull(LxnavParameters.DRAIN_QUIET_MS) { receive() } != null) {
                // Keep reading; what arrives is thrown away.
            }
        }
        pending.clear()
    }

    /**
     * Recovers from a stall: closes the link, waits out the logger's own timeout, reconnects (trying again after
     * each of [LxnavParameters.RECOVERY_RETRY_MS]) and warms the new link up. False when the logger stays unreachable.
     */
    private suspend fun recover(): Boolean {
        runCatching { link.close() }
        delay(LxnavParameters.STALL_WAIT_MS)
        for (wait in listOf(0L) + LxnavParameters.RECOVERY_RETRY_MS) {
            if (wait > 0) delay(wait)
            try {
                link = connectWithin(connector)
                warmUp()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not reachable yet; try again after the next wait.
            }
        }
        return false
    }

    /** Resets any transfer and sends a harmless query whose reply is thrown away, as the first exchange often fails. */
    private suspend fun warmUp() {
        pending.clear()
        sendQuietly(CANCEL)
        sendQuietly(LxnavParameters.WARM_UP)
        try {
            drain()
        } catch (e: IOException) {
            // Dropped again; the next request finds out.
        }
    }

    private suspend fun reconnect() {
        runCatching { link.close() }
        delay(LxnavParameters.RECONNECT_WAIT_MS)
        try {
            link = connectWithin(connector)
            sendQuietly(CANCEL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still unreachable: the closed link fails the next request, which counts as another failure.
        }
        pending.clear()
    }

    private suspend fun send(body: String) {
        require(' ' !in body) { "commands have no spaces" }
        link.send(Nmea.sentence(body))
    }

    private suspend fun sendQuietly(body: String) {
        try {
            send(body)
        } catch (e: IOException) {
            // A dropped link is handled by the next read.
        }
    }

    private companion object {
        const val CANCEL = "PLXVC,FILE_CANCEL,R"
        val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text, dateFormat) }.getOrNull()
        fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()
    }
}
