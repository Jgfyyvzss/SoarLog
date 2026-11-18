package io.github.jgfyyvzss.soarlog.core.lxnav

/**
 * Checks each downloaded IGC line beyond its NMEA checksum, which only proves the logger sent what it sent: a row
 * cut short or spliced with an older one can still carry a valid checksum (XCSoar issue #3201). Lines are checked
 * in file order, because the I and J records set the B and K lengths and B times must not go back.
 *
 * The rules use only the B record layout given in Appendix D of the specification (from the FAI IGC technical
 * specification, A4.1): `B HHMMSS DDMMmmmN DDDMMmmmE V PPPPP GGGGG`, 35 characters, extended to the last finish byte
 * an I record declares; K lengths come from the J record the same way. No maximum line length is enforced.
 */
class IgcLineCheck {
    private var bLength = B_BASE
    private var kLength: Int? = null
    private var lastB: Int? = null

    /** Why [line] cannot be the next line of the file, or null when it can. Changes nothing. */
    fun problem(line: String): String? {
        if (line.isEmpty()) return "empty line"
        val type = line[0]
        if (type !in 'A'..'Z') return "record type '$type' is not a capital letter"
        line.firstOrNull { it.code < 0x20 || it.code == 0x7F }?.let { return "control character 0x%02X".format(it.code) }
        return when (type) {
            'I', 'J' -> if (extensionEnd(line) == null) "$type record is not a list of extensions" else null
            'B' -> bProblem(line)
            'K' -> kProblem(line)
            else -> null
        }
    }

    /** Takes [line] as the next line of the file, whether checked or accepted after identical copies. */
    fun accept(line: String) {
        when (line.firstOrNull()) {
            'I' -> extensionEnd(line)?.let { bLength = maxOf(B_BASE, it) }
            'J' -> extensionEnd(line)?.let { kLength = maxOf(K_BASE, it) }
            'B' -> seconds(line)?.let { lastB = it }
        }
    }

    private fun bProblem(line: String): String? {
        if (line.length != bLength) return "B record has ${line.length} characters, expected $bLength"
        val t = seconds(line) ?: return "B record time is not HHMMSS"
        if (!line.substring(7, 14).all(Char::isDigit) || line[14] !in "NS") return "B record latitude is not DDMMmmmN or S"
        if (!line.substring(15, 23).all(Char::isDigit) || line[23] !in "EW") return "B record longitude is not DDDMMmmmE or W"
        if (line[24] !in "AV") return "B record validity is not A or V"
        if (!altitude(line.substring(25, 30)) || !altitude(line.substring(30, 35))) return "B record altitudes are not 5 digits"
        val last = lastB
        // Times run forward; only the step past midnight UTC may go back.
        if (last != null && t < last && !(last >= 20 * 3600 && t < 4 * 3600)) return "B record time goes back from ${clock(last)} to ${clock(t)}"
        return null
    }

    private fun kProblem(line: String): String? {
        if (line.length < K_BASE || !line.substring(1, 7).all(Char::isDigit)) return "K record time is not HHMMSS"
        val expected = kLength ?: return null
        return if (line.length != expected) "K record has ${line.length} characters, expected $expected" else null
    }

    companion object {
        /** The B record without extensions, and the K record's time part. */
        const val B_BASE = 35
        const val K_BASE = 7

        /** `I` or `J`, a two-digit count, then per extension a start and finish byte and a three-character code. */
        private val EXTENSIONS = Regex("^[IJ](\\d{2})((?:\\d{4}[A-Z0-9]{3})*)$")

        /** The last finish byte an I or J record declares, or the base length when it declares none. */
        fun extensionEnd(line: String): Int? {
            val m = EXTENSIONS.matchEntire(line) ?: return null
            val count = m.groupValues[1].toInt()
            val list = m.groupValues[2]
            if (list.length != count * 7) return null
            if (count == 0) return if (line[0] == 'I') B_BASE else K_BASE
            return (0 until count).maxOf { list.substring(it * 7 + 2, it * 7 + 4).toInt() }
        }

        private fun seconds(line: String): Int? {
            if (line.length < 7 || !line.substring(1, 7).all(Char::isDigit)) return null
            val h = line.substring(1, 3).toInt()
            val m = line.substring(3, 5).toInt()
            val s = line.substring(5, 7).toInt()
            return if (h < 24 && m < 60 && s < 60) h * 3600 + m * 60 + s else null
        }

        /** Five digits, or a minus sign and four, as altitudes below sea level are written. */
        private fun altitude(text: String) = text.all(Char::isDigit) || (text[0] == '-' && text.substring(1).all(Char::isDigit))

        private fun clock(t: Int) = "%02d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
    }
}
