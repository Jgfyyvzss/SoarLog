package io.github.jgfyyvzss.soarlog.ui.witness

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessCheck
import io.github.jgfyyvzss.soarlog.core.witness.WitnessChecks
import io.github.jgfyyvzss.soarlog.core.witness.WitnessedFlight
import io.github.jgfyyvzss.soarlog.data.EndorsementEntity
import io.github.jgfyyvzss.soarlog.data.EndorsementRepository
import io.github.jgfyyvzss.soarlog.data.FlightEntity
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import io.github.jgfyyvzss.soarlog.data.SignRefusal
import io.github.jgfyyvzss.soarlog.data.SignResult
import io.github.jgfyyvzss.soarlog.data.WitnessPhoto
import io.github.jgfyyvzss.soarlog.data.WitnessPick
import io.github.jgfyyvzss.soarlog.data.WitnessRepository
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.flights.WitnessMark
import io.github.jgfyyvzss.soarlog.ui.isoDateShort
import io.github.jgfyyvzss.soarlog.ui.label
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A flight the sign-off screen offers, with what the list shows about it. */
data class WitnessCandidate(val flight: FlightEntity, val registration: String, val status: SealStatus?, val witnessedBy: String?) {
    val role: Role get() = Role.entries.firstOrNull { it.name == flight.role } ?: Role.PIC
}

/**
 * The sign-off screen's state: one flight ([flightId]), a day's group ([date]), or one endorsement ([endorsementId]).
 * Unwitnessed flights start ticked. The witness name is pre-filled from the PIC of ticked Student flights until the
 * witness types one.
 */
class WitnessSignViewModel(
    private val repo: LogbookRepository,
    private val witness: WitnessRepository,
    private val endorsements: EndorsementRepository,
    private val settingsRepo: SettingsRepository,
    private val photoStore: WitnessPhoto,
    private val flightId: String?,
    private val date: String?,
    private val endorsementId: String?,
) : ViewModel() {
    var candidates by mutableStateOf<List<WitnessCandidate>>(emptyList())
        private set
    /** The endorsement being witnessed, and who witnessed it before, if anyone. */
    var endorsement by mutableStateOf<EndorsementEntity?>(null)
        private set
    var endorsementWitnessedBy by mutableStateOf<String?>(null)
        private set
    val forEndorsement get() = endorsementId != null
    var ticked by mutableStateOf<Set<String>>(emptySet())
        private set
    var name by mutableStateOf("")
        private set
    var identifier by mutableStateOf("")
        private set
    var photo by mutableStateOf<ByteArray?>(null)
        private set
    var photoFailed by mutableStateOf(false)
        private set
    var consent by mutableStateOf(false)
        private set
    /** Set once Save is tapped, so errors show only then; warnings show at once. */
    var showErrors by mutableStateOf(false)
        private set
    var refusal by mutableStateOf<SignRefusal?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set

    private var nameTyped = false
    private var profileName = ""
    private var profileNumbers = emptyList<String>()

    val picks = witness.observePicks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            profileName = s.pilotName
            profileNumbers = s.pilotIdentifiers.map { it.value }.filter { it.isNotBlank() }
            endorsementId?.let { id ->
                endorsement = endorsements.get(id)?.takeIf { it.deletedAt == null }
                endorsementWitnessedBy = endorsement?.witnessSignoffId?.let { witness.signoff(it)?.witnessName }
            }
            candidates = witness.candidates(flightId, date).map { f ->
                WitnessCandidate(
                    f,
                    repo.glider(f.gliderId)?.registration ?: "",
                    witness.status(f),
                    f.witnessSignoffId?.let { witness.signoff(it)?.witnessName },
                )
            }
            ticked = candidates.filter { flightId != null || it.status == null }.map { it.flight.id }.toSet()
            prefillName()
            loaded = true
        }
    }

    val check: WitnessCheck
        get() = WitnessChecks.check(
            name, identifier, photo != null, profileName, profileNumbers,
            candidates.filter { it.flight.id in ticked }.map { WitnessedFlight(it.role, it.flight.picName) },
            needsFlights = !forEndorsement,
        )

    /** Whether Save can be tapped: consent, a photo, and something to witness. */
    val canSave get() = consent && photo != null && (if (forEndorsement) endorsement != null else ticked.isNotEmpty())

    private fun prefillName() {
        if (nameTyped) return
        name = WitnessChecks.studentPic(candidates.filter { it.flight.id in ticked }.map { WitnessedFlight(it.role, it.flight.picName) }) ?: ""
    }

    fun toggle(id: String) {
        ticked = if (id in ticked) ticked - id else ticked + id
        refusal = null
        prefillName()
    }

    fun changeName(text: String) { name = text; nameTyped = true; refusal = null }
    fun changeIdentifier(text: String) { identifier = text; refusal = null }
    fun pick(p: WitnessPick) { changeName(p.name); changeIdentifier(p.identifier ?: "") }
    fun changeConsent(on: Boolean) { consent = on; refusal = null }

    /** Where the camera app writes the photo; a fresh file each time. */
    fun captureUri() = photoStore.captureUri()

    /** Shrinks the camera app's photo. A cancelled or unreadable photo keeps any earlier one. */
    fun photoTaken(ok: Boolean) {
        if (!ok) { photoStore.discard(); return }
        viewModelScope.launch {
            val bytes = photoStore.process()
            photoFailed = bytes == null
            if (bytes != null) photo = bytes
        }
    }

    fun save() {
        showErrors = true
        val taken = photo
        if (!check.ok || taken == null) return
        viewModelScope.launch {
            val result = if (endorsementId != null) endorsements.sign(endorsementId, name, identifier, taken, consent)
            else witness.sign(ticked.toList(), name, identifier, taken, consent)
            when (val r = result) {
                is SignResult.Signed -> done = true
                is SignResult.Refused -> refusal = r.reason
            }
        }
    }

    override fun onCleared() = photoStore.discard()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WitnessSignScreen(flightId: String?, date: String?, endorsementId: String? = null, onDone: () -> Unit) {
    val vm: WitnessSignViewModel = viewModel(
        factory = vmFactory {
            WitnessSignViewModel(it.repository, it.witness, it.endorsements, it.settings, it.witnessPhoto, flightId, date, endorsementId)
        },
    )
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    if (!vm.loaded) return

    val picks by vm.picks.collectAsStateWithLifecycle()
    var noCamera by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.photoTaken(ok) }
    val check = vm.check

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (vm.forEndorsement) {
            Text(stringResource(R.string.witness_endorsement), style = MaterialTheme.typography.titleSmall)
            val e = vm.endorsement
            if (e == null) Text(stringResource(R.string.witness_no_endorsement_here))
            else Text(stringResource(R.string.witness_endorsement_line, isoDateShort(e.date), e.type))
            vm.endorsementWitnessedBy?.let { Text(stringResource(R.string.witness_already_by, it), style = MaterialTheme.typography.bodySmall) }
        } else {
            Text(stringResource(R.string.witness_flights), style = MaterialTheme.typography.titleSmall)
            if (vm.candidates.isEmpty()) Text(stringResource(R.string.witness_no_flights_here))
        }
        vm.candidates.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { vm.toggle(c.flight.id) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = c.flight.id in vm.ticked, onCheckedChange = { vm.toggle(c.flight.id) })
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.witness_flight_line,
                            c.flight.logNo, c.registration, Durations.formatHm(c.flight.durationMinutes), c.role.label(),
                        ),
                    )
                    c.witnessedBy?.let { Text(stringResource(R.string.witness_already_by, it), style = MaterialTheme.typography.bodySmall) }
                }
                WitnessMark(c.status)
            }
        }

        HorizontalDivider()
        OutlinedTextField(
            vm.name, vm::changeName, label = { Text(stringResource(R.string.witness_name)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            vm.identifier, vm::changeIdentifier, label = { Text(stringResource(R.string.witness_number)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        if (picks.isNotEmpty()) {
            Text(stringResource(R.string.witness_previous), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                picks.forEach { p ->
                    SuggestionChip(
                        onClick = { vm.pick(p) },
                        label = { Text(p.identifier?.let { stringResource(R.string.witness_pick, p.name, it) } ?: p.name) },
                    )
                }
            }
        }

        // The witness reads what is kept before the camera opens.
        Text(stringResource(R.string.witness_stored_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (vm.forEndorsement) R.string.witness_stored_text_endorsement else R.string.witness_stored_text),
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.photo?.let { PhotoThumbnail(it) }
            OutlinedButton(onClick = {
                noCamera = false
                try {
                    camera.launch(vm.captureUri())
                } catch (_: ActivityNotFoundException) {
                    noCamera = true
                }
            }) { Text(stringResource(if (vm.photo == null) R.string.witness_take_photo else R.string.witness_retake_photo)) }
        }
        if (vm.photoFailed) ErrorLine(stringResource(R.string.witness_photo_failed))
        if (noCamera) ErrorLine(stringResource(R.string.witness_no_camera))

        Row(Modifier.fillMaxWidth().clickable { vm.changeConsent(!vm.consent) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.consent, onCheckedChange = vm::changeConsent)
            Text(stringResource(if (vm.forEndorsement) R.string.witness_consent_endorsement else R.string.witness_consent), Modifier.weight(1f))
        }

        check.warnings.forEach { Text(it.text(), color = MaterialTheme.colorScheme.tertiary) }
        if (vm.showErrors) check.errors.forEach { ErrorLine(it.text()) }
        vm.refusal?.let { ErrorLine(refusalText(it)) }
        Button(onClick = vm::save, enabled = vm.canSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.witness_save))
        }
        // Save stays off until there is a photo, so say why.
        if (vm.photo == null && !vm.showErrors) {
            Text(msg(MsgKey.WITNESS_PHOTO_REQUIRED).text(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun refusalText(r: SignRefusal): String = when (r) {
    SignRefusal.NO_NAME -> msg(MsgKey.WITNESS_NAME_REQUIRED).text()
    SignRefusal.NO_FLIGHTS -> msg(MsgKey.WITNESS_NO_FLIGHTS).text()
    SignRefusal.NO_PHOTO -> msg(MsgKey.WITNESS_PHOTO_REQUIRED).text()
    SignRefusal.NO_CONSENT -> stringResource(R.string.witness_refused_consent)
    SignRefusal.PHOTO_TOO_LARGE -> stringResource(R.string.witness_refused_photo)
}

/**
 * The witness section of the flight form: who witnessed the flight and when, the photo and the seal status, with
 * Witness again, Remove photo and Remove witness mark. [flightDate] is the saved date, for "N days after the flight".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WitnessCard(flightId: String, flightDate: LocalDate?, onWitness: () -> Unit) {
    val app = appContainer()
    val signoff by remember(flightId) { app.witness.observeSignoffFor(flightId) }.collectAsStateWithLifecycle(null)
    val status by remember(flightId) { app.witness.observeStatuses().map { it[flightId] } }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var confirmPhoto by rememberSaveable { mutableStateOf(false) }
    var confirmMark by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_witness), style = MaterialTheme.typography.titleSmall)
        val s = signoff
        if (s == null) {
            Text(stringResource(R.string.witness_card_none))
            OutlinedButton(onClick = onWitness) { Text(stringResource(R.string.witness_action)) }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            s.photo?.let { PhotoThumbnail(it) }
            Column(Modifier.weight(1f)) {
                Text(
                    s.witnessIdentifier?.let { stringResource(R.string.witness_card_by_number, s.witnessName, it) }
                        ?: stringResource(R.string.witness_card_by, s.witnessName),
                )
                val zone = ZoneId.systemDefault()
                val signed = Instant.ofEpochMilli(s.signedAt).atZone(zone).toLocalDate().shortText()
                val days = flightDate?.let { WitnessChecks.daysAfter(it, s.signedAt, zone) } ?: 0
                Text(
                    if (days > 0) pluralStringResource(R.plurals.witness_card_signed_after, days.toInt(), signed, days.toInt())
                    else stringResource(R.string.witness_card_signed, signed),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.photoRemoved) Text(stringResource(R.string.witness_card_photo_removed), style = MaterialTheme.typography.bodySmall)
                WitnessMark(status)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onWitness) { Text(stringResource(R.string.witness_again)) }
            if (s.photo != null) OutlinedButton(onClick = { confirmPhoto = true }) { Text(stringResource(R.string.witness_remove_photo)) }
            OutlinedButton(onClick = { confirmMark = true }) { Text(stringResource(R.string.witness_remove_mark)) }
        }
        if (confirmPhoto) ConfirmDialog(stringResource(R.string.witness_remove_photo), stringResource(R.string.witness_remove_photo_text),
            onDismiss = { confirmPhoto = false }) { confirmPhoto = false; scope.launch { app.witness.removePhoto(s.id) } }
        if (confirmMark) ConfirmDialog(stringResource(R.string.witness_remove_mark), stringResource(R.string.witness_remove_mark_text),
            onDismiss = { confirmMark = false }) { confirmMark = false; scope.launch { app.witness.removeMark(flightId) } }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.witness_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PhotoThumbnail(bytes: ByteArray) {
    val image = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } ?: return
    Image(image, contentDescription = stringResource(R.string.witness_photo), modifier = Modifier.size(80.dp))
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
