package io.github.jgfyyvzss.soarlog.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.FlightFilter
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.RoleTotal
import io.github.jgfyyvzss.soarlog.core.pdf.Field
import io.github.jgfyyvzss.soarlog.core.pdf.LogbookLayout
import io.github.jgfyyvzss.soarlog.core.pdf.PdfOptions
import io.github.jgfyyvzss.soarlog.core.pdf.TemplateRead
import io.github.jgfyyvzss.soarlog.data.FilterStore
import io.github.jgfyyvzss.soarlog.data.EndorsementRepository
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import io.github.jgfyyvzss.soarlog.data.pdf.LogbookPdfWriter
import io.github.jgfyyvzss.soarlog.data.pdf.PdfTemplates
import io.github.jgfyyvzss.soarlog.data.pdf.PdfTexts
import io.github.jgfyyvzss.soarlog.data.pdf.TemplateEntry
import io.github.jgfyyvzss.soarlog.ui.UiText
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.flights.filterChips
import io.github.jgfyyvzss.soarlog.ui.labelRes
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.uiText
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface PdfState {
    data object Idle : PdfState
    data class Working(val pages: Int, val of: Int) : PdfState
    data object Saved : PdfState
    /** Written to a cache file, ready for the share sheet. */
    data class ReadyToShare(val file: File) : PdfState
    data class Failed(val message: UiText) : PdfState
}

/**
 * The logbook PDF, from Settings. It prints the flights the Flights screen's filter shows, oldest first, with the
 * chosen template; with no filter, page 1 brings forward the opening balances.
 */
class PdfExportViewModel(
    private val repo: LogbookRepository,
    private val endorsements: EndorsementRepository,
    private val settings: SettingsRepository,
    filterStore: FilterStore,
    private val templates: PdfTemplates,
    private val writer: LogbookPdfWriter,
    private val cacheDir: File,
) : ViewModel() {
    val filter = filterStore.filter
    var entries by mutableStateOf<List<TemplateEntry>>(emptyList())
        private set
    var selectedId by mutableStateOf(PdfTemplates.BUILT_IN.first())
    var flightCount by mutableStateOf<Int?>(null)
        private set
    var state by mutableStateOf<PdfState>(PdfState.Idle)
        private set
    var message by mutableStateOf<UiText?>(null)
        private set
    private var job: Job? = null

    init {
        viewModelScope.launch { entries = templates.all() }
        viewModelScope.launch { filter.collect { f -> flightCount = repo.countFlights(f) } }
    }

    fun importTemplate(uri: android.net.Uri) = viewModelScope.launch {
        message = when (val r = templates.import(uri)) {
            is TemplateRead.Ok -> {
                entries = templates.all()
                entries.firstOrNull { !it.builtIn && it.template.name == r.template.name }?.let { selectedId = it.id }
                uiText(R.string.pdf_template_imported, r.template.name)
            }
            is TemplateRead.Refused -> UiText.Message(r.message)
        }
    }

    fun removeTemplate(id: String) = viewModelScope.launch {
        templates.remove(id)
        entries = templates.all()
        if (selectedId == id) selectedId = PdfTemplates.BUILT_IN.first()
    }

    /** Writes to [open]'s stream, or to a cache file for sharing when [open] is null. */
    fun export(texts: PdfTexts, open: (() -> OutputStream?)?) {
        val entry = entries.firstOrNull { it.id == selectedId } ?: return
        val f = filter.value
        job?.cancel()
        message = null
        state = PdfState.Working(0, 0)
        job = viewModelScope.launch {
            val shareFile = File(cacheDir, "pdf/logbook.pdf")
            try {
                val s = settings.settings.first()
                val balances = if (f.isActive) null else repo.observeOpeningBalances().first().mapNotNull { b ->
                    Role.entries.firstOrNull { it.name == b.role }?.let { it to RoleTotal(b.minutes, b.flights) }
                }.toMap()
                val layout = LogbookLayout(entry.template, PdfOptions(s.instructionCountsAsPic, s.recordTimes, s.recordLandingPlace, balances))
                val count = repo.countFlights(f)
                val out = if (open != null) open() else shareFile.also { it.parentFile?.mkdirs() }.outputStream()
                if (out == null) {
                    state = PdfState.Failed(uiText(R.string.pdf_failed))
                    return@launch
                }
                // Endorsements are always included, whatever the flight filter (docs/Endorsements.md).
                val forPdf = endorsements.forPdf(withWitness = s.witnessSignoff)
                out.use {
                    writer.write(
                        layout, repo.pdfFlights(f, withWitness = s.witnessSignoff), count, s.hoursDisplay, texts,
                        forPdf, endorsements::image, it,
                    ) { done, of -> state = PdfState.Working(done, of) }
                }
                state = if (open != null) PdfState.Saved else PdfState.ReadyToShare(shareFile)
            } catch (e: CancellationException) {
                state = PdfState.Idle
                throw e
            } catch (e: Exception) {
                state = PdfState.Failed(uiText(R.string.pdf_failed))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        state = PdfState.Idle
    }

    fun shared() { state = PdfState.Idle }
}

@Composable
fun PdfExportScreen() {
    val app = appContainer()
    val context = LocalContext.current
    val vm: PdfExportViewModel = viewModel(
        factory = vmFactory {
            PdfExportViewModel(it.repository, it.endorsements, it.settings, it.filter, PdfTemplates(it), LogbookPdfWriter(it), it.cacheDir)
        },
    )
    val filter by vm.filter.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    val gliders by remember { app.repository.observeGliders() }.collectAsStateWithLifecycle(emptyList())
    val airfields by remember { app.repository.observeAirfields() }.collectAsStateWithLifecycle(emptyList())
    val clubs by remember { app.repository.observeClubs() }.collectAsStateWithLifecycle(emptyList())
    val summary = filterChips(filter, gliders, airfields, clubs).joinToString(" · ") { it.first }.takeIf { filter.isActive }
    val texts = pdfTexts(settings, summary)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.export(texts) { context.contentResolver.openOutputStream(uri, "wt") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importTemplate) }
    val state = vm.state

    // Sharing hands the cache file to the system share sheet through the app's file provider.
    LaunchedEffect(state) {
        if (state is PdfState.ReadyToShare) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", state.file)
            val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null))
            vm.shared()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.pdf_intro), style = MaterialTheme.typography.bodyMedium)
        val count = vm.flightCount
        if (count != null) {
            Text(
                if (summary == null) pluralStringResource(R.plurals.pdf_flights_unfiltered, count, count)
                else pluralStringResource(R.plurals.pdf_flights_filtered, count, count, summary),
            )
        }
        Text(stringResource(R.string.pdf_filter_hint), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text(stringResource(R.string.pdf_template), style = MaterialTheme.typography.titleMedium)
        vm.entries.forEach { e ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.selectedId = e.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = vm.selectedId == e.id, onClick = { vm.selectedId = e.id })
                Text(templateName(e), Modifier.weight(1f))
                if (!e.builtIn) TextButton(onClick = { vm.removeTemplate(e.id) }) { Text(stringResource(R.string.pdf_remove_template)) }
            }
        }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pdf_import_template))
        }
        vm.message?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        when (state) {
            is PdfState.Working -> {
                if (state.of == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { state.pages.toFloat() / state.of }, modifier = Modifier.fillMaxWidth())
                if (state.of > 0) Text(stringResource(R.string.pdf_working, state.pages, state.of))
                Button(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
            }
            else -> {
                val ready = (count ?: 0) > 0
                if (!ready) Text(stringResource(R.string.pdf_no_flights))
                OutlinedButton(onClick = { save.launch("soarlog-logbook-${LocalDate.now()}.pdf") }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pdf_save))
                }
                OutlinedButton(onClick = { vm.export(texts, null) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pdf_share))
                }
                if (state is PdfState.Saved) Text(stringResource(R.string.pdf_saved))
                if (state is PdfState.Failed) Text(state.message.text(), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** A built-in template's name in the pilot's language; an imported one's own name. */
@Composable
private fun templateName(e: TemplateEntry): String = when (e.id) {
    "simple" -> stringResource(R.string.pdf_template_simple)
    "compact" -> stringResource(R.string.pdf_template_compact)
    "gfa" -> stringResource(R.string.pdf_template_gfa)
    else -> e.template.name
}

/** All the PDF's wording, resolved now in the pilot's language. */
@Composable
private fun pdfTexts(s: Settings, filterSummary: String?): PdfTexts {
    val context = LocalContext.current
    val r = context.resources
    val headings = mapOf(
        Field.LOG_NO to R.string.flight_log_no, Field.DATE to R.string.common_date, Field.GLIDER to R.string.flight_glider,
        Field.REGISTRATION to R.string.pdf_col_registration, Field.GLIDER_TYPE to R.string.pdf_col_glider_type,
        Field.AIRFIELD to R.string.flight_airfield, Field.PLACE to R.string.pdf_col_place, Field.CLUB to R.string.flight_club,
        Field.LAUNCH_TYPE to R.string.flight_launch_type, Field.ROLE to R.string.totals_role, Field.PIC to R.string.flight_pic,
        Field.DURATION to R.string.flight_duration, Field.TIME to R.string.flight_duration, Field.LAUNCH_TIME to R.string.flight_launch,
        Field.LANDING_TIME to R.string.flight_landing, Field.LANDING_PLACE to R.string.flight_landing_place, Field.NOTES to R.string.flight_notes,
        Field.WITNESS to R.string.witness_filter,
    ).mapValues { (_, id) -> listOf(r.getString(id)) }
    val launchNames = mapOf(
        "aero" to stringResource(R.string.launch_type_aero), "winch" to stringResource(R.string.launch_type_winch),
        "self" to stringResource(R.string.launch_type_self), "auto" to stringResource(R.string.launch_type_auto),
    )
    val pilot = listOf(s.pilotName.trim()).filter { it.isNotEmpty() } +
        s.pilotIdentifiers.filter { it.value.isNotBlank() }.map { "${it.label.trim()} ${it.value.trim()}".trim() }
    return PdfTexts(
        title = stringResource(R.string.pdf_title),
        pilotLine = pilot.joinToString(" · "),
        headings = { f -> headings[f].orEmpty() },
        hours = stringResource(R.string.pdf_hours),
        minutes = stringResource(R.string.pdf_minutes),
        broughtForward = stringResource(R.string.pdf_brought_forward),
        pageTotal = stringResource(R.string.pdf_page_total),
        carriedForward = stringResource(R.string.pdf_carried_forward),
        flights = { n -> r.getQuantityString(R.plurals.home_flights, n, n) },
        filterSummary = filterSummary,
        filteredNote = stringResource(R.string.pdf_filtered_note),
        pageOf = { n, of -> r.getString(R.string.pdf_page_of, n, of) },
        generated = stringResource(R.string.pdf_generated, LocalDate.now().shortText()),
        role = { role -> r.getString(role.labelRes()) },
        launchType = { id, stored -> seededLaunchName(id, stored, launchNames) },
        date = { d -> d.shortText() },
        witness = { name, number -> if (number.isNullOrBlank()) name else r.getString(R.string.witness_pick, name, number) },
        endorsementsTitle = stringResource(R.string.endorsements_title),
        witnessedBy = { who -> r.getString(R.string.witness_card_by, who) },
    )
}

/** A seeded launch type keeps its translated name until the pilot renames it, as on screen. */
private fun seededLaunchName(id: String, stored: String, names: Map<String, String>): String {
    val seeded = io.github.jgfyyvzss.soarlog.core.Keys.seededLaunchTypes.firstOrNull {
        io.github.jgfyyvzss.soarlog.core.Keys.seededLaunchTypeId(it) == id && it == stored
    }
    return seeded?.let { names[it.lowercase()] } ?: stored
}
