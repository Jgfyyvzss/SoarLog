package io.github.jgfyyvzss.soarlog.ui.flights

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.FlightField
import io.github.jgfyyvzss.soarlog.core.FlightRules
import io.github.jgfyyvzss.soarlog.core.FlightValidation
import io.github.jgfyyvzss.soarlog.core.LandingChoice
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.PicName
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.igc.IgcMatching
import io.github.jgfyyvzss.soarlog.core.igc.IgcSegment
import io.github.jgfyyvzss.soarlog.core.igc.LandingMatch
import io.github.jgfyyvzss.soarlog.core.igc.MatchPlace
import io.github.jgfyyvzss.soarlog.core.igc.NearbyPlace
import io.github.jgfyyvzss.soarlog.data.ClubEntity
import io.github.jgfyyvzss.soarlog.data.FlightEntity
import io.github.jgfyyvzss.soarlog.data.FlightSource
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SaveResult
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FlightEditViewModel(
    private val repo: LogbookRepository,
    private val settingsRepo: SettingsRepository,
    private val id: String?,
    /** A segment from an IGC file and the file's glider ID header, for a new flight pre-filled from it. */
    private val igc: Pair<IgcSegment, String?>? = null,
) : ViewModel() {
    var draft by mutableStateOf(FlightDraft())
        private set
    var errors by mutableStateOf<Map<FlightField, Msg>>(emptyMap())
        private set
    var warnings by mutableStateOf<List<Msg>>(emptyList())
        private set
    /** The clubs linked to the chosen airfield. */
    var airfieldClubs by mutableStateOf<List<ClubEntity>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    /** Launch and landing as local HH:MM, shown read-only for checking an IGC import. */
    var igcPreview by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** Airfields within range of the IGC launch point, nearest first. */
    var nearbyAirfields by mutableStateOf<List<NearbyPlace>>(emptyList())
        private set
    /** No airfield is near the IGC launch point, so the form offers to create one there. */
    var offerAirfieldAtLaunch by mutableStateOf(false)
        private set
    /** The flight's saved date, for the witness card's "days after the flight". */
    var savedDate by mutableStateOf<LocalDate?>(null)
        private set
    /** The witness's name while the form asks before a save changes a signed field. */
    var witnessWarning by mutableStateOf<String?>(null)
        private set
    /** Set once the pilot has seen the warnings, so the next save goes ahead. */
    private var warningsShownFor: FlightDraft? = null
    private var witnessChangeConfirmedFor: FlightDraft? = null
    private var profileName = ""
    /** A launch type the pilot picked is never replaced by an airfield's default. */
    private var launchTypeChosen = id != null

    val isNew get() = id == null

    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val gliders = repo.observeGliders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val airfields = repo.observeAirfields().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val launchTypes = repo.observeLaunchTypes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            profileName = s.pilotName
            draft = if (id == null) {
                FlightDraft(
                    logNoText = repo.nextLogNo().toString(),
                    date = LocalDate.now(),
                    // Only a flight entered by hand starts with the default glider; an IGC import matches its own.
                    gliderId = if (igc == null) defaultGlider(s.defaultGliderId) else null,
                    role = Role.PIC,
                    picName = PicName.initial(Role.PIC, profileName),
                )
            } else {
                repo.flight(id)?.toDraft() ?: FlightDraft()
            }
            savedDate = if (id == null) null else draft.date
            igc?.let { (segment, header) -> prefillFromIgc(segment, header) }
            // A flight entered by hand starts at the default airfield, which brings its default club and launch type.
            if (id == null && igc == null) defaultAirfield(s.defaultAirfieldId)?.let { applyAirfield(it) }
            draft.airfieldId?.let { airfieldClubs = repo.clubsFor(it) }
            loaded = true
        }
    }

    /** The default airfield's ID while it is live and not archived, otherwise null. */
    private suspend fun defaultAirfield(id: String?): String? =
        id?.let { repo.airfield(it) }?.takeIf { it.deletedAt == null && !it.archived }?.id

    /** The default glider's ID while it is live and not archived, otherwise null. */
    private suspend fun defaultGlider(id: String?): String? =
        id?.let { repo.glider(it) }?.takeIf { it.deletedAt == null && !it.archived }?.id

    /**
     * Fills date, duration, glider and airfield from the segment, plus times and the landing place when their
     * Settings are on. Launch type and role are left to the pilot.
     */
    private suspend fun prefillFromIgc(segment: IgcSegment, gliderIdHeader: String?) {
        val zone = ZoneId.systemDefault()
        val shown = segment.launchTime(zone).toString() to segment.landingTime(zone).toString()
        igcPreview = shown
        val s = settingsRepo.settings.first()
        val glider = IgcMatching.glider(gliderIdHeader, repo.observeGliders().first().filter { !it.archived }) { it.registrationKey }
        draft = draft.copy(
            date = segment.date(zone),
            durationText = Durations.formatHm(segment.durationMinutes),
            gliderId = glider?.id,
            launchTimeText = if (s.recordTimes) shown.first else "",
            landingTimeText = if (s.recordTimes) shown.second else "",
        )
        val places = matchPlaces()
        nearbyAirfields = IgcMatching.airfieldsNear(segment.launch, places)
        val nearest = nearbyAirfields.firstOrNull()?.id
        if (nearest != null) applyAirfield(nearest) else offerAirfieldAtLaunch = true
        if (s.recordLandingPlace) fillLanding(segment, places)
    }

    private suspend fun matchPlaces(): List<MatchPlace> = repo.observeAirfields().first()
        .filter { !it.archived && it.latitude != null && it.longitude != null }
        .map { MatchPlace(it.id, it.latitude!!, it.longitude!!, it.matchRadiusM) }

    private fun fillLanding(segment: IgcSegment, places: List<MatchPlace>) {
        draft = when (val l = IgcMatching.landing(segment, draft.airfieldId, places)) {
            LandingMatch.SameAsDeparture -> draft.copy(landingChoice = LandingChoice.SAME_AS_DEPARTURE)
            is LandingMatch.Airfield -> draft.copy(landingChoice = LandingChoice.AIRFIELD, landingAirfieldId = l.id)
            is LandingMatch.Other -> draft.copy(landingChoice = LandingChoice.OTHER, landingPlace = l.place)
        }
    }

    /** Creates an airfield at the IGC launch point, with its altitude as the elevation, and selects it. */
    fun addAirfieldAtLaunch(name: String, onResult: (Msg?) -> Unit) = viewModelScope.launch {
        val segment = igc?.first ?: return@launch
        val fix = segment.launch
        when (val r = repo.saveAirfield(null, name, fix.latitude, fix.longitude, fix.altitudeM, null, emptyList(), archived = false)) {
            is SaveResult.Saved -> {
                offerAirfieldAtLaunch = false
                applyAirfield(r.id)
                if (settings.value.recordLandingPlace) fillLanding(segment, matchPlaces())
                onResult(null)
            }
            is SaveResult.Rejected -> onResult(r.message)
        }
    }

    /** The IGC launch point, for the create-airfield prompt. */
    val igcLaunchFix get() = igc?.first?.launch

    fun dismissAirfieldOffer() { offerAirfieldAtLaunch = false }

    private fun FlightEntity.toDraft() = FlightDraft(
        logNoText = logNo.toString(),
        date = LocalDate.parse(date),
        durationText = Durations.formatHm(durationMinutes),
        gliderId = gliderId,
        airfieldId = airfieldId,
        clubId = clubId,
        launchTypeId = launchTypeId,
        role = Role.valueOf(role),
        picName = picName,
        launchTimeText = launchTime ?: "",
        landingTimeText = landingTime ?: "",
        landingChoice = when {
            landingAirfieldId != null -> LandingChoice.AIRFIELD
            landingPlace != null -> LandingChoice.OTHER
            else -> LandingChoice.SAME_AS_DEPARTURE
        },
        landingAirfieldId = landingAirfieldId,
        landingPlace = landingPlace ?: "",
        notes = notes ?: "",
    )

    private fun edit(field: FlightField?, change: (FlightDraft) -> FlightDraft) {
        draft = change(draft)
        if (field != null) errors = errors - field
        warnings = emptyList()
    }

    fun setLogNo(text: String) = edit(FlightField.LOG_NO) { it.copy(logNoText = text.filter(Char::isDigit)) }
    fun setDate(date: LocalDate) = edit(FlightField.DATE) { it.copy(date = date) }
    fun setDuration(text: String) = edit(FlightField.DURATION) { it.copy(durationText = text) }
    fun setGlider(id: String) = edit(FlightField.GLIDER) { it.copy(gliderId = id) }
    fun setClub(id: String) = edit(FlightField.CLUB) { it.copy(clubId = id) }
    fun setLaunchType(id: String) {
        launchTypeChosen = true
        edit(FlightField.LAUNCH_TYPE) { it.copy(launchTypeId = id) }
    }
    fun setPicName(text: String) = edit(FlightField.PIC_NAME) { it.copy(picName = text) }
    fun setNotes(text: String) = edit(null) { it.copy(notes = text) }
    fun setLandingChoice(choice: LandingChoice) = edit(null) { it.copy(landingChoice = choice) }
        .also { errors = errors - FlightField.LANDING_AIRFIELD - FlightField.LANDING_PLACE }
    fun setLandingAirfield(id: String) = edit(FlightField.LANDING_AIRFIELD) { it.copy(landingAirfieldId = id) }
    fun setLandingPlace(text: String) = edit(FlightField.LANDING_PLACE) { it.copy(landingPlace = text) }

    /**
     * The club is set silently when the airfield has exactly one, and offered when it has more, starting at the
     * airfield's default club. The airfield's default launch type fills the launch type unless the pilot chose one.
     */
    fun setAirfield(id: String) {
        edit(FlightField.AIRFIELD) { it.copy(airfieldId = id) }
        viewModelScope.launch { applyAirfield(id) }
    }

    private suspend fun applyAirfield(id: String) {
        draft = draft.copy(airfieldId = id)
        val airfield = repo.airfield(id)
        val clubs = repo.clubsFor(id)
        airfieldClubs = clubs
        val keep = draft.clubId?.takeIf { c -> clubs.any { it.id == c } }
        val default = airfield?.defaultClubId?.takeIf { c -> clubs.any { it.id == c } }
        draft = draft.copy(
            clubId = if (clubs.size == 1) clubs.single().id else keep ?: default,
            launchTypeId = if (launchTypeChosen) draft.launchTypeId else airfield?.defaultLaunchTypeId,
        )
        errors = errors - FlightField.CLUB - FlightField.AIRFIELD
        if (draft.launchTypeId != null) errors = errors - FlightField.LAUNCH_TYPE
    }

    /** Creates a glider from the flight form and selects it. Reports a rejection message, or null on success. */
    fun addGlider(registration: String, type: String, onResult: (Msg?) -> Unit) = viewModelScope.launch {
        when (val r = repo.saveGlider(null, registration, type, archived = false)) {
            is SaveResult.Saved -> { setGlider(r.id); onResult(null) }
            is SaveResult.Rejected -> onResult(r.message)
        }
    }

    /** Creates an airfield by name from the flight form and selects it. Coordinates and clubs are added later. */
    fun addAirfield(name: String, onResult: (Msg?) -> Unit) = viewModelScope.launch {
        when (val r = repo.saveAirfield(null, name, null, null, null, null, emptyList(), archived = false)) {
            is SaveResult.Saved -> { setAirfield(r.id); onResult(null) }
            is SaveResult.Rejected -> onResult(r.message)
        }
    }

    fun setRole(role: Role) = edit(FlightField.PIC_NAME) {
        it.copy(role = role, picName = PicName.afterRoleChange(it.picName, role, profileName))
    }

    /** When both times are valid, the duration is pre-filled from them and can still be edited. */
    fun setLaunchTime(text: String) = setTimes(text, draft.landingTimeText, FlightField.LAUNCH_TIME)
    fun setLandingTime(text: String) = setTimes(draft.launchTimeText, text, FlightField.LANDING_TIME)

    private fun setTimes(launch: String, landing: String, field: FlightField) {
        edit(field) { it.copy(launchTimeText = launch, landingTimeText = landing) }
        errors = errors - FlightField.LAUNCH_TIME - FlightField.LANDING_TIME
        FlightRules.durationFromTimes(launch, landing)?.let { minutes ->
            draft = draft.copy(durationText = Durations.formatHm(minutes))
            errors = errors - FlightField.DURATION
        }
    }

    fun save() = viewModelScope.launch {
        val check = repo.validateFlight(id, draft)
        if (check is FlightValidation.Invalid) {
            errors = check.errors
            warnings = check.warnings
            return@launch
        }
        check as FlightValidation.Valid
        if (check.warnings.isNotEmpty() && warningsShownFor != draft) {
            warnings = check.warnings
            warningsShownFor = draft
            return@launch
        }
        if (id != null && settings.value.witnessSignoff && witnessChangeConfirmedFor != draft) {
            repo.witnessAffectedBy(id, draft)?.let { witnessWarning = it; return@launch }
        }
        val (result, _) = repo.saveFlight(id, draft, if (igc != null) FlightSource.IGC else FlightSource.MANUAL)
        when (result) {
            is FlightValidation.Valid -> done = true
            is FlightValidation.Invalid -> { errors = result.errors; warnings = result.warnings }
        }
    }

    /** The pilot agreed to change a witnessed flight's signed field; it will show as changed since witnessed. */
    fun confirmWitnessChange() {
        witnessChangeConfirmedFor = draft
        witnessWarning = null
        save()
    }

    fun dismissWitnessWarning() { witnessWarning = null }

    fun delete() = viewModelScope.launch {
        id?.let { repo.deleteFlight(it) }
        done = true
    }
}
