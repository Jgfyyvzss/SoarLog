package io.github.jgfyyvzss.soarlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.data.ogn.ReviewRow
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** OGN Create: one transaction, one import batch, stub gliders, renumbering, and Undo last import. */
@RunWith(AndroidJUnit4::class)
class OgnCreateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LogbookDatabase
    private var clock = 1_760_000_000_000L
    private val aero = Keys.seededLaunchTypeId("Aero")

    @Before fun open() { db = LogbookDatabase.inMemory(context) }
    @After fun close() = db.close()

    private fun repo() = LogbookRepository(db, now = { ++clock })
    private fun SaveResult.id() = (this as SaveResult.Saved).id

    private fun draft(logNo: Int, glider: String, airfield: String) = FlightDraft(
        logNoText = logNo.toString(), date = LocalDate.of(2026, 11, 9), durationText = "4:22", gliderId = glider,
        airfieldId = airfield, launchTypeId = aero, role = Role.PIC, picName = "Peter",
    )

    @Test fun createsAllAsOneBatchWithStubsAndUndoRemovesThem() = runTest {
        val r = repo()
        val a = r.saveAirfield(null, "Mount Beauty", -36.72895, 147.1671, 334, null, emptyList(), archived = false, code = "YMBT").id()
        val g = r.saveGlider(null, "VH-GJF", "ASK-21", archived = false).id()
        val stub = ReviewRow.stubId("VH-GVA")
        val result = r.createImportedFlights(
            listOf(draft(1, g, a), draft(2, stub, a)), mapOf(stub to ("VH-GVA" to "DG-1000")), "OGN YMBT 2026-11-09", FlightSource.OGN,
        )
        assertEquals(ImportCreateResult.Created(2, 1, 2, renumbered = false), result)
        val flights = db.csvDao().liveFlights()
        assertTrue(flights.all { it.source == "OGN" && it.importBatchId != null })
        val created = r.observeGliders().first().single { it.registration == "VH-GVA" }
        assertTrue(created.incomplete)
        assertEquals(created.id, flights[1].gliderId)

        val csv = CsvService(db, now = { ++clock })
        val undo = csv.undoPreview()!!
        assertEquals("OGN YMBT 2026-11-09", undo.batch.fileName)
        assertEquals(2, undo.flights)
        csv.undo(undo.batch.id)
        assertTrue(db.csvDao().liveFlights().isEmpty())
        assertTrue(r.observeGliders().first().none { it.registration == "VH-GVA" })
    }

    @Test fun aNumberTakenDuringReviewRenumbersEveryRow() = runTest {
        val r = repo()
        val a = r.saveAirfield(null, "Mount Beauty", null, null, null, null, emptyList(), archived = false).id()
        val g = r.saveGlider(null, "VH-GJF", "ASK-21", archived = false).id()
        r.saveFlight(null, draft(1, g, a))
        val result = r.createImportedFlights(listOf(draft(1, g, a), draft(2, g, a)), emptyMap(), "OGN", FlightSource.OGN)
        assertEquals(ImportCreateResult.Created(2, 2, 3, renumbered = true), result)
        assertEquals(listOf(1, 2, 3), db.csvDao().liveFlights().map { it.logNo })
    }

    @Test fun anInvalidRowWritesNothing() = runTest {
        val r = repo()
        val a = r.saveAirfield(null, "Mount Beauty", null, null, null, null, emptyList(), archived = false).id()
        val g = r.saveGlider(null, "VH-GJF", "ASK-21", archived = false).id()
        val stub = ReviewRow.stubId("VH-GVA")
        val result = r.createImportedFlights(
            listOf(draft(1, g, a), draft(2, stub, a).copy(durationText = "")), mapOf(stub to ("VH-GVA" to "")), "OGN", FlightSource.OGN,
        )
        assertTrue(result is ImportCreateResult.Failed)
        assertTrue(db.csvDao().liveFlights().isEmpty())
        assertTrue(r.observeGliders().first().none { it.registration == "VH-GVA" })
    }
}
