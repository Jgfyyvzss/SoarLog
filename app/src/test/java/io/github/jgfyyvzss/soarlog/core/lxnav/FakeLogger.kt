package io.github.jgfyyvzss.soarlog.core.lxnav

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A pretend S100 for tests, behaving as the specification describes the real one: a 1 Hz NMEA stream with replies
 * mixed into it, FLIGHT replies with an extra empty field and commas inside the content, 1-based line numbers, and
 * ranges past the end returning only the lines that exist. Faults are scripted and each happens once.
 */
class FakeLogger(
    private val scope: CoroutineScope,
    private val files: List<Entry>,
    /** The test's virtual clock, for when connects happened; the app's session uses it too. */
    val clock: () -> Long = { 0L },
) : LxnavConnector {
    class Entry(val name: String, val date: String, val time: String, val bytes: ByteArray, val listedSize: Long = bytes.size.toLong())

    sealed interface Fault {
        /** The reply leaves out this line. */
        data class DropLine(val line: Int) : Fault
        /** The request starting at this line gets no reply. */
        data class DropReply(val start: Int) : Fault
        /** On a request including this line, the link goes silent: no replies, no NMEA, until a reconnect. */
        data class Stall(val line: Int) : Fault
        /** On a request including this line, the link drops. */
        data class Disconnect(val line: Int) : Fault
        /** On a request including this line, the link drops and the logger stays off until [switchOn]. */
        data class SwitchOff(val line: Int) : Fault
        /** The first LOGBOOKSIZE request gets no reply. */
        data object DropLogbook : Fault
        /** The next reply with this line sends [text] in its place, with a valid checksum. */
        data class Corrupt(val line: Int, val text: String) : Fault
        /** The next reply with this line sends it, then a second copy with [text]. */
        data class Doubled(val line: Int, val text: String) : Fault
        /** The request starting at this line is answered only after [delayMs], when the app has given up on it. */
        data class Late(val start: Int, val delayMs: Long) : Fault
        /** The next reply with this line gives a total line count one higher than the file has. */
        data class WrongTotal(val line: Int) : Fault
        /**
         * On a request including this line the link pauses for [ms]: nothing at all, not even the NMEA stream, and
         * then the stream and the reply arrive together, as the radio link did in the hangar test.
         */
        data class Pause(val line: Int, val ms: Long) : Fault
    }

    /** Scripted answers to other queries, by command; the real logger's replies to these are not yet known. */
    val answers = mutableMapOf<String, List<String>>()

    val faults = mutableListOf<Fault>()
    /** Every valid command received, across all links, in order. */
    val commands = mutableListOf<String>()
    var connects = 0
        private set
    /** For each connect: the virtual time, and how many commands had arrived before it. */
    val connectedAt = mutableListOf<Long>()
    val commandsAtConnect = mutableListOf<Int>()
    private var off = false
    private var current: Link? = null

    fun switchOn() { off = false }

    /** This many connect attempts hang until cancelled, as one did on the phone on 2026-10-30. */
    var hangingConnects = 0
    /** Every connect attempt, including those that failed or hung. */
    var connectAttempts = 0
        private set

    override suspend fun connect(): LxnavLink {
        connectAttempts++
        if (hangingConnects > 0) {
            hangingConnects--
            awaitCancellation()
        }
        if (off) throw IOException("logger is off")
        connects++
        connectedAt += clock()
        commandsAtConnect += commands.size
        current?.drop()
        return Link().also { current = it }
    }

    private fun take(match: (Fault) -> Boolean): Fault? = faults.firstOrNull(match)?.also { faults.remove(it) }

    inner class Link : LxnavLink {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var silent = false
        private var closed = false

        init {
            scope.launch {
                var n = 0
                while (isActive && !closed) {
                    delay(1_000)
                    if (!silent && !closed) emit("GPRMC,0125${"%02d".format(n++ % 60)},A,3644.081,S,14710.277,E,0.0,0.0,130225,,,A")
                }
            }
        }

        fun drop() {
            closed = true
            incoming.close(LinkClosed())
        }

        private fun emit(body: String) {
            if (!closed) incoming.trySend(Nmea.sentence(body))
        }

        override suspend fun send(bytes: ByteArray) {
            if (closed) throw LinkClosed()
            val body = Nmea.body(String(bytes, Charsets.ISO_8859_1)) ?: error("bad sentence from the app: ${String(bytes)}")
            commands += body
            if (silent) return
            val f = body.split(',')
            when {
                body == "PLXVC,FILE_CANCEL,R" -> Unit
                body == "PLXVC,LOGBOOKSIZE,R" -> {
                    if (take { it is Fault.DropLogbook } != null) return
                    reply(listOf("PLXVC,LOGBOOKSIZE,A,${files.size}"))
                }
                f.take(3) == listOf("PLXVC", "LOGBOOK", "R") -> {
                    val (from, to) = f[3].toInt() to f[4].toInt()
                    reply((from until to).mapNotNull { i ->
                        files.getOrNull(i - 1)?.let { e -> "PLXVC,LOGBOOK,A,$i,${files.size},${e.name},${e.date},${e.time},00:00:00,${e.listedSize}" }
                    })
                }
                f.take(3) == listOf("PLXVC", "FLIGHT", "R") -> flight(f[3], f[4].toInt(), f[5].toInt())
                body in answers -> reply(answers.getValue(body))
            }
        }

        private fun flight(name: String, start: Int, end: Int) {
            val lines = files.first { it.name == name }.lines()
            val asked = start until end
            if (take { it is Fault.DropReply && it.start == start } != null) return
            if (take { it is Fault.Stall && it.line in asked } != null) { silent = true; return }
            (take { it is Fault.Pause && it.line in asked } as Fault.Pause?)?.let { p ->
                silent = true
                scope.launch {
                    delay(p.ms)
                    silent = false
                    flight(name, start, end)
                }
                return
            }
            if (take { it is Fault.Disconnect && it.line in asked } != null) { drop(); return }
            if (take { it is Fault.SwitchOff && it.line in asked } != null) { off = true; drop(); return }
            val dropped = asked.filter { line -> take { it is Fault.DropLine && it.line == line } != null }.toSet()
            val late = (take { it is Fault.Late && it.start == start } as Fault.Late?)?.delayMs ?: 0L
            fun row(n: Int, text: String): String {
                val total = if (take { it is Fault.WrongTotal && it.line == n } != null) lines.size + 1 else lines.size
                return "PLXVC,FLIGHT,A,,$n,$total,$text"
            }
            reply((start until minOf(end, lines.size + 1)).filter { it !in dropped }.flatMap { n ->
                val corrupt = take { it is Fault.Corrupt && it.line == n } as Fault.Corrupt?
                val doubled = take { it is Fault.Doubled && it.line == n } as Fault.Doubled?
                listOfNotNull(row(n, corrupt?.text ?: lines[n - 1]), doubled?.let { row(n, it.text) })
            }, late)
        }

        /** Replies arrive in one burst shortly after the request, possibly after a stream sentence. */
        private fun reply(bodies: List<String>, extraDelayMs: Long = 0L) {
            scope.launch {
                delay(100 + extraDelayMs)
                emit("GPGGA,012544,3644.081,S,14710.277,E,1,08,1.0,355.0,M,,M,,")
                bodies.forEach(::emit)
            }
        }

        override suspend fun receive(): ByteArray = try {
            incoming.receive()
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw LinkClosed(e)
        }

        override fun clearInput(): Int {
            var bytes = 0
            while (true) bytes += incoming.tryReceive().getOrNull()?.size ?: break
            return bytes
        }

        override fun close() = drop()
    }

    companion object {
        /** The file's lines as the logger sends them: without their CR LF endings. */
        fun Entry.lines(): List<String> = String(bytes, Charsets.ISO_8859_1).removeSuffix("\r\n").split("\r\n")
    }
}
