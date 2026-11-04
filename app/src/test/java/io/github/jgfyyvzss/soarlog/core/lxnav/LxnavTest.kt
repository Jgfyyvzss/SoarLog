package io.github.jgfyyvzss.soarlog.core.lxnav

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.lxnav.FakeLogger.Companion.lines
import io.github.jgfyyvzss.soarlog.core.lxnav.FakeLogger.Fault
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LxnavTest {
    @get:Rule val folder = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readBytes()

    private fun entry(name: String, listedSize: Long? = null): FakeLogger.Entry {
        val bytes = fixture(name)
        return FakeLogger.Entry(name, "13.02.2025", "01:25:44", bytes, listedSize ?: bytes.size.toLong())
    }

    private val names = listOf("52DVPSA1.igc", "52JVPSA1.igc", "52JVPSA2.igc", "52OVPSA1.igc", "53PVPSA1.igc", "55DVPSA1.igc")

    private fun partial() = FilePartialDownload(File(folder.root, "lxnav.part"))

    private fun TestScope.logger(vararg files: FakeLogger.Entry) = FakeLogger(backgroundScope, files.toList()) { testScheduler.currentTime }

    /** The line number of the file's sixth B record, a fix in the middle of the header-free part. */
    private fun FakeLogger.Entry.sixthB(): Int = lines().withIndex().filter { it.value.startsWith("B") }[5].index + 1

    private suspend fun FakeLogger.download(
        file: FakeLogger.Entry,
        partial: PartialDownload = partial(),
        progress: MutableList<LxnavProgress> = mutableListOf(),
        size: BlockSize = BlockSize(),
    ): ByteArray = LxnavClient(this, clock = clock).session { download(logbook().first { it.name == file.name }, partial, size, progress::add) }

    /** The start and exclusive end of every FLIGHT request so far. */
    private fun FakeLogger.requests() =
        commands.filter { it.startsWith("PLXVC,FLIGHT,R") }.map { it.split(',').let { f -> f[4].toInt() to f[5].toInt() } }

    @Test fun checksumsMatchTheOnesConfirmedOnTheDevice() {
        assertEquals("\$PLXVC,FILE_CANCEL,R*5C\r\n", String(Nmea.sentence("PLXVC,FILE_CANCEL,R")))
        assertEquals("\$PLXVC,INFO,R*0D\r\n", String(Nmea.sentence("PLXVC,INFO,R")))
        assertEquals("\$PLXVC,BT_INFO,R*44\r\n", String(Nmea.sentence("PLXVC,BT_INFO,R")))
        assertEquals("\$PLXVC,LOGBOOKSIZE,R*4B\r\n", String(Nmea.sentence("PLXVC,LOGBOOKSIZE,R")))
        assertEquals("PLXVC,FILE_CANCEL,R", Nmea.body("\$PLXVC,FILE_CANCEL,R*5C\r"))
        assertNull("bad checksum", Nmea.body("\$PLXVC,FILE_CANCEL,R*5D"))
        assertNull("not a sentence", Nmea.body("PLXVC,FILE_CANCEL,R*5C"))
    }

    @Test fun logbookListsEveryFile() = runTest {
        val files = names.map { entry(it) }.toTypedArray()
        val book = LxnavClient(logger(*files)).session { logbook() }
        assertEquals(names, book.map { it.name })
        assertEquals(files.map { it.bytes.size.toLong() }, book.map { it.sizeBytes })
        assertEquals(LocalDate.of(2025, 2, 13), book.first().date)
        assertEquals(LocalTime.of(1, 25, 44), book.first().startUtc)
    }

    @Test fun everySampleFileDownloadsByteForByte() = runTest {
        names.forEach { name ->
            val e = entry(name)
            val logger = logger(e)
            assertArrayEquals(name, e.bytes, logger.download(e))
            // A transfer is reset at the start and end of every session.
            assertEquals("PLXVC,FILE_CANCEL,R", logger.commands.first())
            assertEquals("PLXVC,FILE_CANCEL,R", logger.commands.last())
        }
    }

    @Test fun linesWithCommasSurviveTheExtraEmptyField() = runTest {
        val e = entry("52DVPSA1.igc")
        val text = String(logger(e).download(e), Charsets.ISO_8859_1)
        assertTrue(text.contains("HFFTYFRTYPE:LXNAV,S100\r\n"))
    }

    @Test fun progressCountsUpToTheTotal() = runTest {
        val e = entry("52DVPSA1.igc")
        val progress = mutableListOf<LxnavProgress>()
        logger(e).download(e, progress = progress)
        val total = e.lines().size
        assertEquals(LxnavProgress(0, null, 0), progress.first())
        assertEquals(LxnavProgress(total, total, 0), progress.last())
    }

    @Test fun aLostLineOrReplyIsAskedForAgain() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        // Lines 1 to 9 of the first block are kept; the size halves to 5 from line 10, grows back, and a block starts at 40.
        logger.faults += listOf(Fault.DropLine(10), Fault.DropReply(40), Fault.DropLogbook)
        assertArrayEquals(e.bytes, logger.download(e))
        assertTrue(logger.faults.isEmpty())
        assertEquals(1, logger.connects)
    }

    @Test fun failuresHalveTheBlockAndCleanBlocksGrowItBack() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += listOf(Fault.DropReply(11), Fault.DropReply(11))
        assertArrayEquals(e.bytes, logger.download(e))
        // 10 lines, then 5 and 2 after the two failures, then four clean 2-line blocks double it to 4.
        assertEquals(
            listOf(1 to 11, 11 to 21, 11 to 16, 11 to 13, 13 to 15, 15 to 17, 17 to 19, 19 to 23),
            logger.requests().take(8),
        )
        assertTrue("it grows no further than the start size", logger.requests().all { (a, b) -> b - a <= LxnavParameters.ADAPTIVE_START })
    }

    @Test fun aFixedBlockSizeNeverChanges() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += listOf(Fault.DropReply(51))
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(50)))
        assertTrue(logger.requests().all { (a, b) -> b - a == 50 })
        assertEquals(LxnavParameters.ADAPTIVE_START, BlockSize.fromSetting(0).lines)
        assertEquals(1, BlockSize.fromSetting(1).lines)
    }

    @Test fun scatteredFailuresNeverAddUpToAnAbort() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        // More failures than the limit, but each is followed by a good block, so the count goes back to 0 every time.
        logger.faults += (1..LxnavParameters.ATTEMPTS + 5).map { Fault.DropReply(1 + 10 * it) }
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        assertTrue(logger.faults.isEmpty())
    }

    @Test fun aBlockCutShortKeepsTheLinesBeforeTheGap() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += Fault.DropLine(5)
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        // Lines 1 to 4 arrived, so the next request starts at line 5.
        assertEquals(listOf(1 to 11, 5 to 15), logger.requests().take(2))
    }

    @Test fun twentyFailuresInARowGiveUp() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += (1..LxnavParameters.ATTEMPTS).map { Fault.DropReply(11) }
        val failure = runCatching { logger.download(e, size = BlockSize.fromSetting(10)) }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_GAVE_UP, failure.shown.key)
        assertEquals(11, failure.shown.args.single())
        assertEquals(LxnavParameters.ATTEMPTS, logger.requests().count { it.first == 11 })
    }

    @Test fun aCutShortLineIsAskedForAgain() = runTest {
        val e = entry("52JVPSA1.igc")
        val n = e.sixthB()
        val logger = logger(e)
        // A row cut short still has a valid NMEA checksum; the IGC check catches it.
        logger.faults += Fault.Corrupt(n, e.lines()[n - 1].dropLast(4))
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        assertTrue(logger.faults.isEmpty())
        assertEquals("asked again from the bad line", 2, logger.requests().count { (a, b) -> n in a until b })
    }

    @Test fun aRowSentTwiceDifferentlyIsAskedForAgain() = runTest {
        val e = entry("52JVPSA1.igc")
        val n = e.sixthB()
        val logger = logger(e)
        logger.faults += Fault.Doubled(n, e.lines()[n - 1].replaceRange(1, 7, "235959"))
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        assertEquals(2, logger.requests().count { (a, b) -> n in a until b })
    }

    @Test fun aLineThatKeepsArrivingTheSameIsAccepted() = runTest {
        val e = entry("52JVPSA1.igc")
        val n = e.sixthB()
        // Same length but the time goes back: the check rejects it, yet the logger keeps sending exactly this.
        val odd = e.lines()[n - 1].replaceRange(1, 7, "000001")
        val logger = logger(e)
        logger.faults += (1..LxnavParameters.IDENTICAL_TO_ACCEPT).map { Fault.Corrupt(n, odd) }
        val text = String(logger.download(e, size = BlockSize.fromSetting(10)), Charsets.ISO_8859_1)
        assertEquals(odd, text.removeSuffix("\r\n").split("\r\n")[n - 1])
    }

    @Test fun aLateReplyDoesNotSpoilTheNextBlock() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += Fault.Late(11, 4_500)
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        // The late rows arrived during the back-off and were kept, so lines 11 to 20 were never asked for again.
        assertEquals(1, logger.requests().count { it.first == 11 })
    }

    @Test fun aLinkPauseIsWaitedThrough() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        // 8 seconds of silence is twice the usual wait, but well short of a stall.
        logger.faults += Fault.Pause(300, 8_000)
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        assertEquals("asked once, no failure", 1, logger.requests().count { (a, b) -> 300 in a until b })
        assertEquals(1, logger.connects)
    }

    @Test fun aLinkSilentForLongIsAStallAtOnce() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += Fault.Pause(300, 60_000)
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        // One request waited out the silence and the link was recovered at once, without asking again on the old
        // link: so the range with line 300 was asked for twice, once on each link.
        assertEquals(2, logger.requests().count { (a, b) -> 300 in a until b })
        assertEquals(2, logger.connects)
    }

    @Test fun aRowGivingAnotherLineCountIsAskedForAgain() = runTest {
        val e = entry("52JVPSA1.igc")
        val n = e.sixthB()
        val logger = logger(e)
        logger.faults += Fault.WrongTotal(n)
        assertArrayEquals(e.bytes, logger.download(e, size = BlockSize.fromSetting(10)))
        assertEquals(2, logger.requests().count { (a, b) -> n in a until b })
    }

    @Test fun theFirstRequestMayTakeLongToAnswer() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        // Well past the usual 4 seconds, but within the first request's longer wait.
        logger.faults += Fault.Late(1, 10_000)
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(1, logger.requests().count { it.first == 1 })
    }

    @Test fun aQueryCollectsOnlyItsOwnSentenceType() = runTest {
        val logger = logger(entry("52DVPSA1.igc"))
        // A made-up answer: the S100's real reply to this query has not been seen yet.
        logger.answers["PLXV0,NMEARATE,R"] = listOf("PLXVF,1,2,3", "PLXV0,NMEARATE,A,1,2,3")
        val replies = LxnavClient(logger).session { query("PLXV0,NMEARATE,R") }
        assertEquals(listOf("PLXV0,NMEARATE,A,1,2,3"), replies)
        assertTrue(runCatching { LxnavClient(logger).session { query("PLXV0,NMEARATE,W,0") } }.isFailure)
    }

    @Test fun aStallWaitsOutTheLoggerAndWarmsTheNewLinkUp() = runTest {
        val e = entry("52OVPSA1.igc")
        val logger = logger(e)
        logger.faults += Fault.Stall(300)
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(2, logger.connects)
        assertTrue("left for the logger's own timeout", logger.connectedAt[1] - logger.connectedAt[0] >= LxnavParameters.STALL_WAIT_MS)
        val after = logger.commands.drop(logger.commandsAtConnect[1])
        assertEquals(listOf("PLXVC,FILE_CANCEL,R", LxnavParameters.WARM_UP), after.take(2))
    }

    @Test fun aHungConnectIsGivenUpAndTriedAgain() = runTest {
        val e = entry("52DVPSA1.igc")
        val logger = logger(e)
        logger.hangingConnects = 1
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(2, logger.connectAttempts)
        assertTrue("the hung attempt was given up after the timeout", logger.connectedAt.first() >= LxnavParameters.CONNECT_TIMEOUT_MS)
    }

    @Test fun twoHungConnectsReportAConnectFailure() = runTest {
        val e = entry("52DVPSA1.igc")
        val logger = logger(e)
        logger.hangingConnects = LxnavParameters.CONNECT_ATTEMPTS
        val failure = runCatching { logger.download(e) }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_CONNECT_FAILED, failure.shown.key)
        assertEquals(LxnavParameters.CONNECT_ATTEMPTS, logger.connectAttempts)
        assertEquals(0, logger.connects)
    }

    @Test fun aSilentStallRecoversByReconnecting() = runTest {
        val e = entry("52OVPSA1.igc")
        val logger = logger(e)
        logger.faults += Fault.Stall(300)
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(2, logger.connects)
    }

    @Test fun aDroppedConnectionReconnectsAndContinues() = runTest {
        val e = entry("53PVPSA1.igc")
        val logger = logger(e)
        logger.faults += listOf(Fault.Disconnect(100), Fault.Disconnect(900))
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(3, logger.connects)
    }

    @Test fun loggerSwitchedOffFailsCleanlyAndResumesLater() = runTest {
        val e = entry("55DVPSA1.igc")
        val logger = logger(e)
        val partial = partial()
        logger.faults += Fault.SwitchOff(500)
        val failure = runCatching { logger.download(e, partial) }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_GAVE_UP, failure.shown.key)
        val stoppedAt = failure.shown.args.single() as Int
        assertTrue("stopped at $stoppedAt", stoppedAt in 491..500)

        logger.switchOn()
        val progress = mutableListOf<LxnavProgress>()
        assertArrayEquals(e.bytes, logger.download(e, partial, progress))
        assertEquals(stoppedAt - 1, progress.first().resumedFrom)
        // The resumed download asks again for the last saved line, to learn the total and check the partial.
        val firstRequest = logger.commands.last { it.startsWith("PLXVC,FLIGHT,R") && it.split(',')[4].toInt() == stoppedAt - 1 }
        assertTrue(firstRequest, firstRequest.isNotEmpty())
        assertTrue("the partial is deleted once the file is complete", !File(folder.root, "lxnav.part").exists())
    }

    @Test fun aSizeMismatchFailsAndDiscardsTheDownload() = runTest {
        val e = entry("52DVPSA1.igc", listedSize = 47_516)
        val failure = runCatching { logger(e).download(e) }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_SIZE_MISMATCH, failure.shown.key)
        assertEquals(listOf<Any>(47_514, 47_516L), failure.shown.args)
        assertTrue(!File(folder.root, "lxnav.part").exists())
    }

    @Test fun cancellingStillResetsTheTransfer() = runTest {
        val e = entry("53PVPSA1.igc")
        val logger = logger(e)
        val job = launch { logger.download(e) }
        advanceTimeBy(5_000)
        job.cancel()
        job.join()
        assertEquals("PLXVC,FILE_CANCEL,R", logger.commands.last())
    }

    @Test fun anUnreachableLoggerReportsAConnectFailure() = runTest {
        val logger = logger(entry("52DVPSA1.igc"))
        logger.faults += Fault.SwitchOff(1)
        runCatching { logger.download(entry("52DVPSA1.igc")) }
        val failure = runCatching { LxnavClient(logger).session { logbook() } }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_CONNECT_FAILED, failure.shown.key)
    }

    // The resume file

    @Test fun partialResumesOnlyTheSameFileAndDropsACutShortLine() {
        val file = File(folder.root, "p.part")
        val a = LxnavFile("A.igc", null, null, 100)
        val p = FilePartialDownload(file)
        assertEquals(emptyList<String>(), p.resume(a))
        p.append(listOf("one", "two"))
        file.appendText("thr", Charsets.ISO_8859_1) // interrupted mid-line
        assertEquals(listOf("one", "two"), p.resume(a))
        p.append(listOf("three"))
        assertEquals(listOf("one", "two", "three"), FilePartialDownload(file).resume(a))
        // Another file, or the same name with another size, starts afresh.
        assertEquals(emptyList<String>(), p.resume(a.copy(sizeBytes = 101)))
        assertEquals(emptyList<String>(), p.resume(a))
    }

    @Test fun partialExpiresAfterTwoDays() {
        val file = File(folder.root, "p.part")
        val a = LxnavFile("A.igc", null, null, 100)
        var now = System.currentTimeMillis()
        val p = FilePartialDownload(file) { now }
        p.resume(a)
        p.append(listOf("one"))
        now = file.lastModified() + FilePartialDownload.MAX_AGE_MS - 1
        assertEquals(listOf("one"), p.resume(a))
        now = file.lastModified() + FilePartialDownload.MAX_AGE_MS + 1
        p.deleteIfExpired()
        assertTrue(!file.exists())
    }
}
