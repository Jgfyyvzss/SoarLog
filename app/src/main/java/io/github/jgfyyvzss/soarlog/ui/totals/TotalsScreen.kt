package io.github.jgfyyvzss.soarlog.ui.totals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.ui.label
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jgfyyvzss.soarlog.core.HoursDisplay
import io.github.jgfyyvzss.soarlog.core.HoursFormat
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.RoleTotal
import io.github.jgfyyvzss.soarlog.core.TotalsTable
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.endorsements.EndorsementsSection
import io.github.jgfyyvzss.soarlog.ui.flights.FilterBar

/** The pilot's own time per role, for the shared filter. Never aircraft time. Endorsements follow below, unfiltered. */
@Composable
fun TotalsScreen(onOpenEndorsement: (String) -> Unit, onNewEndorsement: () -> Unit) {
    val app = appContainer()
    val tableFlow = remember { app.totals.observe(app.filter.filter) }
    val table by tableFlow.collectAsStateWithLifecycle(null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FilterBar()
        val t = table ?: return@Column
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TotalsRow(stringResource(R.string.totals_role), stringResource(R.string.common_hours), stringResource(R.string.common_flights), header = true)
            HorizontalDivider()
            Role.entries.forEach { role ->
                val label = role.label() + if (role == Role.PIC && t.picIncludesInstruction) " *" else ""
                TotalsRow(label, t.rows.getValue(role), settings.hoursDisplay)
            }
            HorizontalDivider()
            TotalsRow(stringResource(R.string.totals_combined), t.combined, settings.hoursDisplay, bold = true)
            Text(" ")
            Notes(t)
        }
        EndorsementsSection(onOpen = onOpenEndorsement, onNew = onNewEndorsement)
    }
}

@Composable
private fun Notes(t: TotalsTable) {
    val style = MaterialTheme.typography.bodySmall
    if (!t.includesOpeningBalances) Text(stringResource(R.string.totals_filtered), style = style)
    if (t.picIncludesInstruction) Text(stringResource(R.string.totals_pic_includes), style = style)
}

@Composable
private fun TotalsRow(label: String, total: RoleTotal, display: HoursDisplay, bold: Boolean = false) =
    TotalsRow(label, HoursFormat.format(total.minutes, display), total.flights.toString(), bold = bold)

@Composable
private fun TotalsRow(label: String, hours: String, flights: String, header: Boolean = false, bold: Boolean = false) {
    val style = when {
        header -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.bodyLarge
    }
    val weight = if (bold || header) FontWeight.Bold else null
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.4f), style = style, fontWeight = weight)
        Text(hours, Modifier.weight(1f), style = style, fontWeight = weight, textAlign = TextAlign.End)
        Text(flights, Modifier.weight(1f), style = style, fontWeight = weight, textAlign = TextAlign.End)
    }
}
