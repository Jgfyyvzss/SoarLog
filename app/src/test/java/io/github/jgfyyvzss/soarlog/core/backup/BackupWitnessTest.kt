package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.backup.BackupTestData.T1
import io.github.jgfyyvzss.soarlog.core.backup.BackupTestData.T2
import io.github.jgfyyvzss.soarlog.core.witness.SealInput
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Witness sign-offs in the backup: round trip, photo checks, seal summary and merge. The zip itself is BackupZipTest's. */
class BackupWitnessTest {
    /** Starts like a JPEG; the import checks only the marker, the size and the hash. */
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2_000) { it.toByte() }
    private val hash = WitnessSeal.sha256Hex(photo)

    private fun signoff(id: String = "s1", sha: String? = hash, updatedAt: String = T1, removed: Boolean = false) =
        BackupWitnessSignoff(
            id = id, witnessName = "Lee Instructor", witnessIdentifier = "GFA 1", signedAt = T1, photoSha256 = sha,
            photoRemoved = removed, sealVersion = WitnessSeal.VERSION, consentConfirmed = true, updatedAt = updatedAt,
        )

    /** The test file with f1 and f2 witnessed by [s] and correctly sealed. */
    private fun witnessed(s: BackupWitnessSignoff = signoff(), containsImages: Boolean = true): BackupFile {
        val base = BackupTestData.file()
        val flights = base.flights.map { f -> if (f.id == "f0") f else f.copy(witnessSignoffId = s.id, witnessSeal = sealFor(f, s)) }
        return base.copy(flights = flights, witnessSignoffs = listOf(s), containsImages = containsImages)
    }

    /** The seal the app would have stored when signing, from the flight's fields and the sign-off. */
    private fun sealFor(f: BackupFlight, s: BackupWitnessSignoff): String {
        val input = SealInput(
            f.logNo, LocalDate.parse(f.date), f.durationMinutes, f.gliderId, f.airfieldId, f.launchTypeId, f.role,
            f.picName, f.launchTime, f.landingTime, f.landingAirfieldId, f.landingPlace, s.witnessName, s.witnessIdentifier,
            BackupTime.fromIso(s.signedAt)!!, s.photoSha256,
        )
        return WitnessSeal.seal(input)
    }

    /** Images that answer every hash with [answer], counting the reads. */
    private class Images(val answer: ImageRead) : BackupImages {
        val reads = mutableListOf<String>()
        override fun read(sha256: String, maxBytes: Int): ImageRead {
            reads += sha256
            assertEquals(BackupWitness.MAX_PHOTO_BYTES, maxBytes)
            return answer
        }
    }

    private fun keys(r: WitnessReview) = r.summary.notes.map { it.key }

    @Test fun witnessDataRoundTripsUnchanged() {
        val first = BackupCodec.encode(witnessed())
        val decoded = (BackupCodec.decode(first) as DecodeResult.Ok).file
        assertEquals(first, BackupCodec.encode(decoded))
        assertTrue(first.contains("\"witness_signoff_id\": \"s1\""))
        assertTrue(first.contains("\"photo_sha256\": \"$hash\""))
        assertFalse("no photo in the JSON", first.contains("\"photo\":"))
        assertTrue(first.contains("\"contains_images\": true"))
        assertTrue(BackupValidation.validate(decoded).isEmpty())
    }

    @Test fun sealsAreCountedForLiveFlights() {
        val r = BackupWitness.review(witnessed())
        assertEquals(WitnessSummary(verified = 2, changed = 0, cannotVerify = 0, notes = emptyList()), r.summary)

        val edited = witnessed().let { f -> f.copy(flights = f.flights.map { if (it.id == "f2") it.copy(durationMinutes = 300) else it }) }
        val e = BackupWitness.review(edited).summary
        assertEquals(1, e.verified)
        assertEquals("a mismatch never blocks the import", 1, e.changed)
        assertTrue(BackupValidation.validate(edited).isEmpty())

        val future = witnessed(signoff().copy(sealVersion = 9))
        assertEquals(2, BackupWitness.review(future).summary.cannotVerify)
    }

    @Test fun aGoodPhotoIsListedForRestoring() {
        val images = Images(ImageRead.Ok(photo))
        val r = BackupWitness.review(witnessed(), images)
        assertEquals(setOf(hash), r.photos)
        assertTrue(r.summary.notes.isEmpty())
        assertEquals(listOf(hash), images.reads)
    }

    @Test fun badPhotosAreLeftOutAndTheSignoffKept() {
        listOf(MsgKey.BK_PHOTO_UNREADABLE, MsgKey.BK_PHOTO_NOT_JPEG, MsgKey.BK_PHOTO_TOO_LARGE, MsgKey.BK_PHOTO_HASH).forEach { key ->
            val r = BackupWitness.review(witnessed(), Images(ImageRead.Bad(key)))
            assertEquals(key.name, listOf(key), keys(r))
            assertTrue(r.photos.isEmpty())
            assertEquals("the sign-off and its hash stay", hash, r.file.witnessSignoffs.single().photoSha256)
            assertEquals(2, r.file.flights.count { it.witnessSignoffId == "s1" })
            assertEquals(2, r.summary.verified)
        }
    }

    @Test fun aMissingPhotoIsAProblemOnlyInAFileWithImages() {
        val r = BackupWitness.review(witnessed(containsImages = true), Images(ImageRead.Missing))
        assertEquals(listOf(MsgKey.BK_PHOTO_MISSING), keys(r))
        assertTrue(r.photos.isEmpty())

        val leftOut = BackupWitness.review(witnessed(containsImages = false), Images(ImageRead.Missing))
        assertTrue("left out on purpose", leftOut.summary.notes.isEmpty())
        assertEquals(2, leftOut.summary.verified)
    }

    @Test fun removedAndDeletedPhotosAreNotLookedFor() {
        val images = Images(ImageRead.Missing)
        BackupWitness.review(witnessed(signoff(removed = true)), images)
        // A sign-off deleted with its flights: its tombstone keeps the hash but never the photo.
        val tombstone = BackupTestData.file().copy(witnessSignoffs = listOf(signoff().copy(deletedAt = T2)), containsImages = true)
        assertTrue(BackupWitness.review(tombstone, images).summary.notes.isEmpty())
        assertTrue(images.reads.isEmpty())
    }

    @Test fun aPhotoSharedBySignoffsIsReadOnce() {
        val second = signoff(id = "s2")
        val file = witnessed().let { it.copy(witnessSignoffs = it.witnessSignoffs + second) }
        val images = Images(ImageRead.Bad(MsgKey.BK_PHOTO_HASH))
        val r = BackupWitness.review(file, images)
        assertEquals(1, images.reads.size)
        assertEquals("each sign-off is reported", 2, keys(r).size)
    }

    @Test fun danglingReferencesLoseTheWitnessMark() {
        val base = witnessed()
        val missing = base.copy(witnessSignoffs = emptyList())
        val r = BackupWitness.review(missing)
        assertEquals(listOf(MsgKey.BK_WITNESS_DANGLING, MsgKey.BK_WITNESS_DANGLING), keys(r))
        assertTrue(r.file.flights.all { it.witnessSignoffId == null && it.witnessSeal == null })
        assertEquals("the flights keep their data", base.flights.map { it.durationMinutes }, r.file.flights.map { it.durationMinutes })

        val halfSet = base.copy(flights = base.flights.map { if (it.id == "f1") it.copy(witnessSeal = null) else it })
        val h = BackupWitness.review(halfSet)
        assertEquals(listOf(MsgKey.BK_WITNESS_DANGLING), keys(h))
        assertEquals(null, h.file.flights.first { it.id == "f1" }.witnessSignoffId)

        val deletedSignoff = witnessed(signoff().copy(deletedAt = T2))
        assertEquals(2, keys(BackupWitness.review(deletedSignoff)).size)

        val deletedFlight = BackupTestData.file().let { f ->
            f.copy(flights = f.flights.map { if (it.id == "f0") it.copy(witnessSignoffId = "gone", witnessSeal = "x") else it })
        }
        val d = BackupWitness.review(deletedFlight)
        assertTrue("a deleted flight is cleared quietly", d.summary.notes.isEmpty())
        assertEquals(null, d.file.flights.first { it.id == "f0" }.witnessSignoffId)
    }

    @Test fun signoffsNeedANameConsentAndReadableTimes() {
        val bad = witnessed(signoff().copy(witnessName = " ", consentConfirmed = false, signedAt = "yesterday"))
        val problems = BackupValidation.validate(bad).map { it.key }
        assertTrue(MsgKey.BK_NO_NAME in problems)
        assertTrue(MsgKey.BK_WITNESS_NO_CONSENT in problems)
        assertTrue(MsgKey.BK_BAD_TIME in problems)

        val twice = witnessed().let { it.copy(witnessSignoffs = it.witnessSignoffs + it.witnessSignoffs) }
        assertTrue(MsgKey.BK_DUPLICATE_ID in BackupValidation.validate(twice).map { it.key })
    }

    @Test fun mergeTakesTheNewerSignoffWithItsHash() {
        val merged = BackupMerge.merge(witnessed(), witnessed(signoff(updatedAt = T2))).merged.witnessSignoffs.single()
        assertEquals(T2, merged.updatedAt)
        assertEquals("the photo is found again by this hash when committing", hash, merged.photoSha256)
    }

    @Test fun mergeHonoursARemovedPhoto() {
        val removed = witnessed(signoff(updatedAt = T2, removed = true))
        val merged = BackupMerge.merge(witnessed(), removed).merged.witnessSignoffs.single()
        assertTrue(merged.photoRemoved)
        assertNotNull(merged.photoSha256)
    }

    @Test fun aNewerEditedFlightBringsItsSealAndShowsChanged() {
        val local = witnessed()
        val theirs = local.copy(flights = local.flights.map { if (it.id == "f1") it.copy(durationMinutes = 200, updatedAt = T2) else it })
        val merged = BackupMerge.merge(local, theirs).merged
        val f1 = merged.flights.first { it.id == "f1" }
        assertEquals(200, f1.durationMinutes)
        assertEquals(SealStatus.CHANGED, BackupWitness.status(f1, merged.witnessSignoffs.single()))
    }
}
