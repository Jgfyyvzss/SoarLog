package io.github.jgfyyvzss.soarlog.ui.endorsements

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import io.github.jgfyyvzss.soarlog.data.EndorsementEntity
import io.github.jgfyyvzss.soarlog.data.EndorsementImage
import io.github.jgfyyvzss.soarlog.data.EndorsementRefusal
import io.github.jgfyyvzss.soarlog.data.EndorsementRepository
import io.github.jgfyyvzss.soarlog.data.EndorsementSave
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.flights.WitnessMark
import io.github.jgfyyvzss.soarlog.ui.isoDateShort
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Endorsements section below the Totals grid (docs/Endorsements.md): every live endorsement, newest first. The
 * shared filter does not apply here, so a filtered grid still shows every endorsement.
 */
@Composable
fun EndorsementsSection(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val app = appContainer()
    val endorsements by remember { app.endorsements.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val statuses by remember { app.endorsements.observeStatuses() }.collectAsStateWithLifecycle(emptyMap())
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.endorsements_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onNew) { Text(stringResource(R.string.endorsements_add)) }
        }
        HorizontalDivider()
        if (endorsements.isEmpty()) {
            Text(stringResource(R.string.endorsements_none), style = MaterialTheme.typography.bodySmall)
        }
        endorsements.forEach { e ->
            EndorsementRow(e, if (settings.witnessSignoff) statuses[e.id] else null) { onOpen(e.id) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EndorsementRow(e: EndorsementEntity, status: SealStatus?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(e)
        Column(Modifier.weight(1f)) {
            Text(e.type, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(isoDateShort(e.date), style = MaterialTheme.typography.bodySmall)
        }
        WitnessMark(status)
    }
}

/** A small picture of the page, read and decoded only for this row; "No image" when there is none. */
@Composable
private fun Thumbnail(e: EndorsementEntity) {
    val app = appContainer()
    val thumb by produceState<ImageBitmap?>(null, e.id, e.imageSha256) {
        value = if (e.imageSha256 == null) null else app.endorsements.image(e.id)?.let { decodeSmall(it, THUMB_PX) }
    }
    val t = thumb
    if (t != null) {
        Image(t, contentDescription = stringResource(R.string.endorsement_image), modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.endorsement_no_image), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val THUMB_PX = 160

/** Decodes at a reduced sample size, so a thumbnail never holds the full image. */
private suspend fun decodeSmall(bytes: ByteArray, maxSide: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * The endorsement being added or changed. The image is kept here until Save, so leaving the screen changes nothing.
 * Changing a witnessed endorsement whose seal still matches asks first, as for flights.
 */
class EndorsementEditViewModel(
    private val repo: EndorsementRepository,
    private val store: EndorsementImage,
    val id: String?,
) : ViewModel() {
    var type by mutableStateOf("")
        private set
    var date by mutableStateOf(LocalDate.now())
        private set
    var image by mutableStateOf<ByteArray?>(null)
        private set
    /** True once the pilot took, chose or removed an image. */
    var imageChanged by mutableStateOf(false)
        private set
    /** The saved row has a hash but no image: it was left out of the backup it came from. */
    var imageLeftOut by mutableStateOf(false)
        private set
    var imageFailed by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var typeMissing by mutableStateOf(false)
        private set
    var refusal by mutableStateOf<EndorsementRefusal?>(null)
        private set
    var witnessWarning by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(id == null)
        private set
    var done by mutableStateOf(false)
        private set

    private var savedSha: String? = null

    init {
        if (id != null) {
            viewModelScope.launch {
                repo.get(id)?.let { e ->
                    type = e.type
                    date = runCatching { LocalDate.parse(e.date) }.getOrDefault(LocalDate.now())
                    savedSha = e.imageSha256
                    image = repo.image(id)
                    imageLeftOut = e.imageSha256 != null && image == null
                }
                loaded = true
            }
        }
    }

    fun changeType(text: String) { type = text; typeMissing = false; refusal = null }
    fun changeDate(d: LocalDate) { date = d }

    fun captureUri(): Uri = store.captureUri()

    fun photoTaken(ok: Boolean) {
        if (!ok) { store.discard(); return }
        prepare { store.process() }
    }

    fun picked(uri: Uri?) {
        if (uri != null) prepare { store.fromPicked(uri) }
    }

    private fun prepare(read: suspend () -> ByteArray?) {
        preparing = true
        viewModelScope.launch {
            val bytes = read()
            preparing = false
            imageFailed = bytes == null
            if (bytes != null) {
                image = bytes
                imageChanged = true
                imageLeftOut = false
                refusal = null
            }
        }
    }

    fun removeImage() {
        image = null
        imageChanged = true
        imageLeftOut = false
    }

    fun save() = save(confirmed = false)

    private fun save(confirmed: Boolean) {
        if (type.isBlank()) { typeMissing = true; return }
        viewModelScope.launch {
            if (!confirmed && id != null) {
                val sha = if (imageChanged) image?.let(WitnessSeal::sha256Hex) else savedSha
                repo.sealWouldBreak(id, type, date, sha)?.let { witnessWarning = it; return@launch }
            }
            when (val r = repo.save(id, type, date, imageChanged, image)) {
                is EndorsementSave.Saved -> done = true
                is EndorsementSave.Refused -> refusal = r.reason
            }
        }
    }

    fun confirmWitnessChange() {
        witnessWarning = null
        save(confirmed = true)
    }

    fun dismissWitnessWarning() { witnessWarning = null }

    fun delete() {
        val existing = id ?: return
        viewModelScope.launch {
            repo.delete(existing)
            done = true
        }
    }

    override fun onCleared() = store.discard()
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EndorsementEditScreen(id: String?, onDone: () -> Unit, onWitness: (String) -> Unit) {
    val vm: EndorsementEditViewModel = viewModel(factory = vmFactory { EndorsementEditViewModel(it.endorsements, it.endorsementImage, id) })
    val app = appContainer()
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    if (!vm.loaded) return

    var pickDate by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf(false) }
    var noCamera by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.photoTaken(ok) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.picked(uri) }
    val preview by produceState<ImageBitmap?>(null, vm.image) { value = vm.image?.let { decodeSmall(it, PREVIEW_PX) } }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            vm.type, vm::changeType,
            label = { Text(stringResource(R.string.endorsement_type)) },
            placeholder = { Text(stringResource(R.string.endorsement_type_hint)) },
            isError = vm.typeMissing, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.typeMissing) ErrorLine(stringResource(R.string.endorsement_type_required))
        Box {
            OutlinedTextField(
                vm.date.shortText(), {}, readOnly = true, label = { Text(stringResource(R.string.common_date)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { pickDate = true })
        }

        Text(stringResource(R.string.endorsement_image), style = MaterialTheme.typography.titleSmall)
        val p = preview
        when {
            vm.preparing -> Text(stringResource(R.string.endorsement_preparing), style = MaterialTheme.typography.bodySmall)
            p != null -> Image(
                p, contentDescription = stringResource(R.string.endorsement_image),
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clickable { viewing = true },
                contentScale = ContentScale.Fit,
            )
            vm.imageLeftOut -> Text(stringResource(R.string.endorsement_image_left_out), style = MaterialTheme.typography.bodySmall)
            else -> Text(stringResource(R.string.endorsement_no_image), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !vm.preparing, onClick = {
                noCamera = false
                try {
                    camera.launch(vm.captureUri())
                } catch (_: ActivityNotFoundException) {
                    noCamera = true
                }
            }) { Text(stringResource(if (vm.image == null) R.string.endorsement_take_photo else R.string.endorsement_retake)) }
            OutlinedButton(enabled = !vm.preparing, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(if (vm.image == null) R.string.endorsement_choose else R.string.endorsement_choose_other)) }
            if (vm.image != null || vm.imageLeftOut) {
                OutlinedButton(enabled = !vm.preparing, onClick = vm::removeImage) { Text(stringResource(R.string.endorsement_remove_image)) }
            }
        }
        if (vm.imageFailed) ErrorLine(stringResource(R.string.endorsement_image_failed))
        if (noCamera) ErrorLine(stringResource(R.string.witness_no_camera))
        vm.refusal?.let { r ->
            ErrorLine(stringResource(if (r == EndorsementRefusal.NO_TYPE) R.string.endorsement_type_required else R.string.endorsement_image_refused))
        }

        if (settings.witnessSignoff) {
            HorizontalDivider()
            if (vm.id == null) {
                Text(stringResource(R.string.settings_witness), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.endorsement_witness_save_first), style = MaterialTheme.typography.bodySmall)
            } else {
                EndorsementWitnessCard(vm.id, onWitness = { onWitness(vm.id) })
            }
            HorizontalDivider()
        }

        Button(onClick = vm::save, enabled = !vm.preparing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_save))
        }
        if (vm.id != null) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.endorsement_delete))
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.changeDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }

    vm.witnessWarning?.let { witnessName ->
        AlertDialog(
            onDismissRequest = vm::dismissWitnessWarning,
            title = { Text(stringResource(R.string.endorsement_edit_title)) },
            text = { Text(stringResource(R.string.endorsement_edit_text, witnessName)) },
            confirmButton = { TextButton(onClick = vm::confirmWitnessChange) { Text(stringResource(R.string.witness_edit_confirm)) } },
            dismissButton = { TextButton(onClick = vm::dismissWitnessWarning) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.endorsement_delete_title)) },
            text = { Text(stringResource(R.string.endorsement_delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    val shown = vm.image
    if (viewing && shown != null) ImageViewer(shown, onClose = { viewing = false })
}

private const val PREVIEW_PX = 800

/** The image full screen, with pinch zoom and drag; tapping Close or Back returns. */
@Composable
private fun ImageViewer(bytes: ByteArray, onClose: () -> Unit) {
    val full by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            full?.let { img ->
                Image(
                    img, contentDescription = stringResource(R.string.endorsement_image), contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text(stringResource(R.string.endorsement_viewer_close), color = Color.White)
            }
        }
    }
}

/** Who witnessed the endorsement and when, the photo and the seal status, with Witness again and the removals. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndorsementWitnessCard(endorsementId: String, onWitness: () -> Unit) {
    val app = appContainer()
    val signoff by remember(endorsementId) { app.endorsements.observeSignoff(endorsementId) }.collectAsStateWithLifecycle(null)
    val statuses by remember { app.endorsements.observeStatuses() }.collectAsStateWithLifecycle(emptyMap())
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
            val photo = s.photo
            if (photo != null) {
                val img = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
                if (img != null) Image(img, contentDescription = stringResource(R.string.witness_photo), modifier = Modifier.size(80.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    s.witnessIdentifier?.let { stringResource(R.string.witness_card_by_number, s.witnessName, it) }
                        ?: stringResource(R.string.witness_card_by, s.witnessName),
                )
                val signed = Instant.ofEpochMilli(s.signedAt).atZone(ZoneId.systemDefault()).toLocalDate().shortText()
                Text(stringResource(R.string.witness_card_signed, signed), style = MaterialTheme.typography.bodySmall)
                if (s.photoRemoved) Text(stringResource(R.string.witness_card_photo_removed), style = MaterialTheme.typography.bodySmall)
                WitnessMark(statuses[endorsementId])
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onWitness) { Text(stringResource(R.string.witness_again)) }
            if (s.photo != null) OutlinedButton(onClick = { confirmPhoto = true }) { Text(stringResource(R.string.witness_remove_photo)) }
            OutlinedButton(onClick = { confirmMark = true }) { Text(stringResource(R.string.witness_remove_mark)) }
        }
        if (confirmPhoto) {
            ConfirmRemove(stringResource(R.string.witness_remove_photo), stringResource(R.string.endorsement_remove_photo_text), { confirmPhoto = false }) {
                confirmPhoto = false
                scope.launch { app.witness.removePhoto(s.id) }
            }
        }
        if (confirmMark) {
            ConfirmRemove(stringResource(R.string.witness_remove_mark), stringResource(R.string.endorsement_remove_mark_text), { confirmMark = false }) {
                confirmMark = false
                scope.launch { app.endorsements.removeMark(endorsementId) }
            }
        }
    }
}

@Composable
private fun ConfirmRemove(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.witness_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
