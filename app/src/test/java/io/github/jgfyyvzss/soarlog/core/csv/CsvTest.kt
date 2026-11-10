package io.github.jgfyyvzss.soarlog.core.csv

import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.english
import io.github.jgfyyvzss.soarlog.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {
    private val winch = Keys.seededLaunchTypeId("Winch")
    private val known = KnownNames(
        gliderKeys = setOf("fqm"),
        airfieldKeys = setOf("ymbt"),
        launchTypes = mapOf("winch" to winch, "aero" to Keys.seededLaunchTypeId("Aero")),
        launchTypeNames = listOf("Aero", "Winch"),
        liveLogNumbers = setOf(1, 2),
    )

    private fun flights(vararg lines: String) = FlightCsv.parse(lines.joinToString("\r\n"), known)
    private fun messages(imp: FlightImport) = imp.errors.map { it.message.english() }

    @Test fun readsQuotedFieldsLineBreaksAndBom() {
        val text = Csv.BOM + "a,b,c\r\n1,\"two, too\",\"say \"\"hi\"\"\"\n\"multi\nline\",,end"
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "two, too", "say \"hi\""), listOf("multi\nline", "", "end")),
            Csv.parse(text),
        )
    }

    @Test fun writesWithQuotingBomAndCrlf() {
        val file = Csv.file(listOf("x", "y"), listOf(listOf("plain", "has, comma"), listOf("quote \"q\"", "line\nbreak")))
        assertEquals(Csv.BOM + "x,y\r\nplain,\"has, comma\"\r\n\"quote \"\"q\"\"\",\"line\nbreak\"\r\n", file)
        assertEquals(listOf(listOf("x", "y"), listOf("plain", "has, comma"), listOf("quote \"q\"", "line\nbreak")), Csv.parse(file))
    }

    @Test fun validRowsParseWithHeadersInAnyCaseAndOrder() {
        val imp = flights(
            " Date ,GLIDER,Duration,airfield,role,launch_type,log_no,club,notes,pic",
            "2026-10-17,F-QM,1:75,YMBT,pic,winch,3,MBGC,\"Wave, 8000ft\",",
            "2026-10-18,fqm,90,ymbt,Instructor,,4,,,",
        )
        assertEquals(emptyList<String>(), messages(imp))
        val (a, b) = imp.flights
        assertEquals(135, a.durationMinutes)
        assertEquals(Role.PIC, a.role)
        assertEquals(winch, a.launchTypeId)
        assertEquals("Wave, 8000ft", a.notes)
        assertEquals(90, b.durationMinutes)
        assertEquals(null, b.launchTypeId)
        assertEquals(1, imp.rowsNeedingLaunchType)
        assertEquals(2, imp.rowsNeedingProfileName)
        assertFalse(imp.needsNumbering)
        assertTrue(imp.unmatchedGliders.isEmpty())
    }

    @Test fun errorsNameRowColumnValueAndExpectation() {
        val imp = flights(
            "date,glider,duration,airfield,role,launch_type,log_no,pic",
            "18/10/26,FQM,1:00,YMBT,PIC,Winch,3,",
            "2026-10-18,FQM,0,YMBT,DUAL,Bungee,2,",
            "2026-10-19,FQM,1:00,YMBT,STUDENT,Winch,3,",
        )
        assertEquals(
            listOf(
                "Row 2, date: found 18/10/26, expected YYYY-MM-DD",
                "Row 3, log_no: found 2, expected a log number not already in the logbook",
                "Row 3, duration: found 0, expected between 1 minute and 24:00",
                "Row 3, role: found DUAL, expected PIC, STUDENT, INSTRUCTOR, COACH or MUTUAL",
                "Row 3, launch_type: found Bungee, expected one of Aero, Winch",
                "Row 4, log_no: found 3, expected a log number not used earlier in the file (row 2)",
                "Row 4, pic: found blank, expected the name of the pilot in command on a STUDENT row",
            ),
            messages(imp),
        )
        assertTrue(imp.flights.isEmpty())
    }

    @Test fun missingRequiredColumnsStopEarly() {
        val imp = flights("date,glider,airfield", "2026-10-17,FQM,YMBT")
        assertEquals(listOf("Row 1, duration: expected a duration column", "Row 1, role: expected a role column"), messages(imp))
    }

    @Test fun unmatchedNamesUnknownColumnsAndNumbering() {
        val imp = flights(
            "date,glider,duration,airfield,role,pic,place,landing_airfield",
            "2026-10-17,GZY,60,Benalla,PIC,Pat,YMBT MBGC,Mt Beauty",
            "2026-10-18,gzy,60,benalla,PIC,Pat,,",
        )
        assertEquals(emptyList<String>(), messages(imp))
        assertEquals(mapOf("gzy" to "GZY"), imp.unmatchedGliders)
        assertEquals(mapOf("benalla" to "Benalla", "mt beauty" to "Mt Beauty"), imp.unmatchedAirfields)
        assertEquals(listOf("Column place is not used and was ignored"), imp.warnings.map { it.english() })
        assertTrue(imp.needsNumbering)
    }

    @Test fun landingRulesAndTimes() {
        val imp = flights(
            "date,glider,duration,airfield,role,pic,launch_time,landing_time,landing_airfield,landing_place",
            "2026-10-17,FQM,60,YMBT,PIC,Pat,13:05,12:00,,",
            "2026-10-17,FQM,60,YMBT,PIC,Pat,,,YMBT,Paddock",
            "2026-10-17,FQM,60,YMBT,PIC,Pat,9:30,10:30,,Paddock",
        )
        assertEquals(
            listOf(
                "Row 2, landing_time: found 12:00, expected a time after the launch time 13:05",
                "Row 3, landing_place: found Paddock, expected landing_airfield or landing_place, not both",
            ),
            messages(imp),
        )
        assertEquals("09:30", imp.flights.single().launchTime.toString())
    }

    @Test fun exportReimportsCleanly() {
        val text = FlightCsv.export(
            listOf(
                FlightCsv.ExportRow(5, "2026-10-17", "FQM", "YMBT", "MBGC", "Winch", "PIC", "Pat", 271, "13:05", "17:36", null, "Paddock, near hill", "a \"quote\""),
            ),
        )
        val imp = FlightCsv.parse(text, known)
        assertEquals(emptyList<String>(), messages(imp))
        val f = imp.flights.single()
        assertEquals(271, f.durationMinutes)
        assertEquals("Paddock, near hill", f.landingPlace)
        assertEquals("a \"quote\"", f.notes)
        assertTrue(text.startsWith(Csv.BOM + FlightCsv.exportColumns.joinToString(",")))
    }

    @Test fun witnessColumnsAreExportedButNeverImported() {
        val row = FlightCsv.ExportRow(
            5, "2026-10-17", "FQM", "YMBT", null, "Winch", "STUDENT", "Lee Instructor", 30, null, null, null, null, null,
            witnessedBy = FlightCsv.witnessedBy("Lee Instructor", "GFA 1"), witnessedOn = "2026-10-18",
        )
        val text = FlightCsv.export(listOf(row), witness = true)
        assertTrue(text.startsWith(Csv.BOM + (FlightCsv.exportColumns + FlightCsv.witnessColumns).joinToString(",")))
        assertTrue(text.endsWith(",Lee Instructor (GFA 1),2026-10-18\r\n"))
        assertEquals("Lee", FlightCsv.witnessedBy("Lee", " "))
        assertFalse("off by default", FlightCsv.export(listOf(row)).contains("witnessed_by"))

        val imp = FlightCsv.parse(text, known)
        assertEquals(emptyList<String>(), messages(imp))
        assertTrue("no unused-column warning", imp.warnings.isEmpty())
        assertEquals(1, imp.flights.size)
    }

    @Test fun archivedColumnIsReadAndWritten() {
        val g = GliderCsv.parse("registration,type,archived\r\nGZY,Junior,YES\r\nBLNK,Blanik,\r\nKA6,Ka 6,no\r\nX,Y,maybe\r\n", emptySet())
        assertEquals(listOf(true, false, false), g.records.map { it.archived })
        assertEquals(listOf("Row 5, archived: found maybe, expected yes, no or blank"), g.errors.map { it.message.english() })
        val a = AirfieldCsv.parse("name,archived\r\nYBLA,yes\r\nYMBT,\r\n", emptySet())
        assertEquals(listOf(true, false), a.records.map { it.archived })

        val gliders = GliderCsv.export(listOf(GliderCsv.ExportRow("GZY", "Junior", null, null, archived = true)))
        assertEquals(listOf(true), GliderCsv.parse(gliders, emptySet()).records.map { it.archived })
        val airfields = AirfieldCsv.export(listOf(AirfieldCsv.ExportRow("YBLA", null, null, null, null, emptyList(), archived = true)))
        assertTrue(airfields.endsWith("YBLA,,,,,,,yes\r\n"))
        assertEquals(listOf(true), AirfieldCsv.parse(airfields, emptySet()).records.map { it.archived })
    }

    @Test fun gliderAndAirfieldFilesSkipExistingAndReportErrors() {
        val g = GliderCsv.parse("registration,type\r\nFQM,Hornet\r\nGZY,Junior\r\ngzy,Junior\r\nB4,\r\n", setOf("fqm"))
        assertEquals(listOf("GZY"), g.records.map { it.registration })
        assertEquals(listOf("Row 2: FQM is already in the glider list", "Row 4: gzy appears earlier in the file"), g.skipped.map { it.english() })
        assertEquals(listOf("Row 5, type: found blank, expected the glider type"), g.errors.map { it.message.english() })

        val a = AirfieldCsv.parse(
            "name,latitude,longitude,elevation_m,match_radius_m,clubs\r\nYMBT,,,,,\r\nBenalla,-36.55,146.0,173,1500,BGC; VMFG ;bgc\r\nX,95,1,,,\r\nY,-36,,,,\r\n",
            setOf("ymbt"),
        )
        assertEquals(listOf("Benalla"), a.records.map { it.name })
        assertEquals(listOf("BGC", "VMFG"), a.records.single().clubs)
        assertEquals(
            listOf(
                "Row 4, latitude: found 95, expected decimal degrees between -90 and 90",
                "Row 5, longitude: found blank, expected both latitude and longitude, or neither",
            ),
            a.errors.map { it.message.english() },
        )
    }

    @Test fun airfieldCodesAreOptionalAndUnique() {
        val a = AirfieldCsv.parse(
            "name,code\r\nMount Beauty,ymbt \r\nTemora,YTEM\r\nCorowa,ymbt\r\nPrivate strip,\r\nBenalla,YBLA\r\n",
            emptySet(),
            mapOf("YBLA" to "Benalla old"),
        )
        assertEquals(listOf("Mount Beauty" to "YMBT", "Temora" to "YTEM", "Private strip" to null), a.records.map { it.name to it.code })
        assertEquals(
            listOf("Row 4: the code YMBT is already used by Mount Beauty", "Row 6: the code YBLA is already used by Benalla old"),
            a.skipped.map { it.english() },
        )

        val out = AirfieldCsv.export(listOf(AirfieldCsv.ExportRow("Mount Beauty", null, null, null, null, emptyList(), code = "YMBT")))
        assertTrue(out.contains("name,code,latitude"))
        assertEquals(listOf("YMBT"), AirfieldCsv.parse(out, emptySet()).records.map { it.code })
        assertEquals("a file without the column still imports", listOf(null), AirfieldCsv.parse("name\r\nYMBT\r\n", emptySet()).records.map { it.code })
    }
}
