package io.github.jgfyyvzss.soarlog.core.lxnav

import io.github.jgfyyvzss.soarlog.core.Msg
import java.io.IOException

/**
 * A byte stream to a logger. The Android implementation is a Bluetooth serial socket; tests use a fake logger.
 * [receive] suspends until bytes arrive and throws [LinkClosed] once the link has dropped.
 */
interface LxnavLink {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
    fun close()

    /** Throws away whatever has already arrived without waiting for more, and says how many bytes that was. */
    fun clearInput(): Int = 0
}

/** Opens a new link to the same logger, for the first connection and for reconnecting. */
fun interface LxnavConnector {
    suspend fun connect(): LxnavLink
}

class LinkClosed(cause: Throwable? = null) : IOException("link closed", cause)

/**
 * Receives one line per event of a logger session, for the communication log: what was sent, every chunk received,
 * block results, failures, reconnects and closes. The writer adds the time. Lines are technical and in English,
 * like the logger's own sentences, and are never shown in the app.
 */
fun interface LxnavTrace {
    fun line(text: String)

    companion object {
        val NONE = LxnavTrace { }

        /** Received text made readable on one line: CR, LF and other control characters escaped. */
        fun escape(text: String): String = buildString {
            text.forEach { ch ->
                when {
                    ch == '\r' -> append("\\r")
                    ch == '\n' -> append("\\n")
                    ch == '\\' -> append("\\\\")
                    ch.code < 0x20 || ch.code == 0x7F -> append("\\x%02X".format(ch.code))
                    else -> append(ch)
                }
            }
        }
    }
}

/** A failure with the message to show the pilot. */
class LxnavFailure(val shown: Msg, cause: Throwable? = null) : Exception(cause)

/** NMEA-style sentences: `$<body>*<CS>` and CR LF, the checksum being the XOR of the body's characters. */
object Nmea {
    fun checksum(body: String): Int = body.fold(0) { c, ch -> c xor ch.code }

    /** Frames a body. Commands never contain spaces, but replies can, because IGC lines carry text such as names. */
    fun sentence(body: String): ByteArray {
        val cs = checksum(body).toString(16).uppercase().padStart(2, '0')
        return "\$$body*$cs\r\n".toByteArray(Charsets.ISO_8859_1)
    }

    /** The body of a valid sentence, or null for anything else, including a bad checksum. */
    fun body(line: String): String? {
        val text = line.trim()
        if (!text.startsWith("$")) return null
        val star = text.lastIndexOf('*')
        if (star < 1 || text.length < star + 3) return null
        val body = text.substring(1, star)
        val cs = text.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
        return if (checksum(body) == cs) body else null
    }
}
