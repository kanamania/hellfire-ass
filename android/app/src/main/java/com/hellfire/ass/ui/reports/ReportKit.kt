package com.hellfire.ass.ui.reports

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.reports.Csv
import com.hellfire.ass.reports.DateRange
import com.hellfire.ass.reports.ExportSpec
import com.hellfire.ass.reports.PdfReport
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** The date range every report opens with; set on the hub or from any report's date chip. */
object ReportRange {
    var preset by mutableStateOf("Month")
    var range by mutableStateOf(DateRange.month(Fmt.today()))
    fun set(p: String, custom: DateRange? = null) {
        preset = p
        range = when (p) { "Today" -> DateRange.today(Fmt.today()); "Week" -> DateRange.week(Fmt.today()); "Month" -> DateRange.month(Fmt.today()); else -> custom ?: range }
    }
}

data class ActiveFilter(val label: String, val remove: () -> Unit)

/**
 * Shared report layout (§6): top bar with Filter/Export, chips (date range, active filters, + Filter),
 * Chart/Table switch remembered per report, optional Group dropdown, then chart or table content.
 */
@Composable
fun ReportScaffold(
    id: String, title: String, breadcrumb: String,
    filters: List<ActiveFilter> = emptyList(),
    filterSheet: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    export: ExportSpec? = null,
    group: Pair<String, List<String>>? = null, onGroup: (String) -> Unit = {},
    chart: (@Composable ColumnScope.() -> Unit)?,
    table: @Composable ColumnScope.() -> Unit,
    calendar: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val app = LocalEnv.current.app
    val prefs = remember { app.getSharedPreferences("reports", 0) }
    var view by remember { mutableStateOf(if (chart == null) "Table" else prefs.getString("view_$id", "Chart")!!) }
    var sheet by remember { mutableStateOf("") }
    PushedPage(title, breadcrumb = breadcrumb, actions = {
        if (filterSheet != null) IconCircleButton(HfIcons.Filter, "Filter", { sheet = "filter" })
        if (export != null) IconCircleButton(HfIcons.Export, "Export", { sheet = "export" })
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HfChip(ReportRange.range.label, { sheet = "range" }, style = ChipStyle.Selected)
            filters.forEach { HfChip(it.label, it.remove, style = ChipStyle.Filter) }
            if (filterSheet != null) HfChip("Filter", { sheet = "filter" }, style = ChipStyle.Add)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chart != null) SegmentedControl(listOfNotNull("Chart", "Calendar".takeIf { calendar != null }, "Table"), view,
                { view = it; prefs.edit().putString("view_$id", it).apply() }, compact = true,
                label = { if (it == "Calendar") stringResource(R.string.ui_calendar) else it })
            Spacer(Modifier.weight(1f))
            if (group != null && (view == "Table" || chart == null)) Dropdown("Group", group.first, group.second, { it }, onGroup)
        }
        when {
            view == "Chart" && chart != null -> chart()
            view == "Calendar" && calendar != null -> calendar()
            else -> table()
        }
    }
    when (sheet) {
        "range" -> RangeSheet { sheet = "" }
        "filter" -> filterSheet?.let { content -> HfBottomSheet({ sheet = "" }) { content { sheet = "" } } }
        "export" -> export?.let { ExportSheet(it) { sheet = "" } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeSheet(startCustom: Boolean = false, onDone: () -> Unit) {
    var custom by remember { mutableStateOf(startCustom) }
    if (custom) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(onDismissRequest = { custom = false }, confirmButton = {
            TextButton({
                val s = state.selectedStartDateMillis; val e = state.selectedEndDateMillis ?: s
                if (s != null && e != null) {
                    fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    ReportRange.set("Custom", DateRange.of(d(s), d(e)))
                }
                custom = false; onDone()
            }) { Text("Use these dates") }
        }, dismissButton = { TextButton({ custom = false; if (startCustom) onDone() }) { Text(stringResource(R.string.ui_cancel)) } }) {
            DateRangePicker(state, Modifier.heightIn(max = 520.dp), title = { Text("Choose dates", Modifier.padding(16.dp)) })
        }
        return
    }
    HfBottomSheet(onDone) {
        Text("Date range", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        SegmentedControl(listOf("Today", "Week", "Month", "Custom"), ReportRange.preset, { p -> if (p == "Custom") custom = true else { ReportRange.set(p); onDone() } }, label = { if (it == "Today") stringResource(R.string.ui_today) else it })
        Note("${ReportRange.range.label} · East Africa Time. Every report uses this range.")
    }
}

@Composable
private fun ExportSheet(spec: ExportSpec, onDone: () -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf("pdf") }
    var ids by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (format == "pdf") "application/pdf" else "text/csv")) { uri ->
        if (uri != null) scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        when (format) {
                            "pdf" -> PdfReport.write(spec, out)
                            "csv" -> out.write(Csv.table(spec).toByteArray())
                            else -> out.write(Csv.records(spec, ids).toByteArray())
                        }
                    }
                }
                "Saved where you chose. Nothing was uploaded."
            } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Export failed. Check the destination and free space." }
        }
    }
    HfBottomSheet(onDone) {
        Text("Export this view", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        Text("${spec.title} · ${spec.subtitle}", style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        OptionCard("PDF report", "Chart, this table, filters and totals", format == "pdf", { format = "pdf" })
        OptionCard("CSV · this table", "${spec.rows.size} rows" + if (spec.totals != null) " plus totals" else "", format == "csv", { format = "csv" })
        OptionCard("CSV · every record", "${spec.recordRows.size} rows with ${spec.records.take(5).joinToString(", ").lowercase()}", format == "all", { format = "all" })
        if (spec.sourceColumn != null) HfCard { SwitchRow("Include source record IDs", ids, { ids = it }) }
        Note("You pick where to save it. Nothing is uploaded.")
        status?.let { Banner(it, Tone.Info, null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.ui_cancel), onDone, Modifier.weight(1f))
            PrimaryButton(if (format == "pdf") "Export PDF" else "Export CSV", {
                val stamp = TaskTime.display(System.currentTimeMillis()).replace(' ', '_').replace(':', '-')
                save.launch("HellFire-${spec.title.replace(' ', '-')}-$stamp.${if (format == "pdf") "pdf" else "csv"}")
            }, Modifier.weight(1f))
        }
    }
}

/** KPI tiles in the two-column grid. */
@Composable
fun KpiGrid(tiles: List<Triple<String, String, String?>>) {
    TwoColumnGrid(tiles.map { (k, v, d) -> val tile: @Composable (Modifier) -> Unit = { m -> KpiTile(k, v, m, d) }; tile }, gap = 10.dp)
}

/** Filter sheet chip group with toggles. */
@Composable
fun FilterGroup(label: String, options: List<String>, selected: Set<String>, display: (String) -> String = { it }, onChange: (Set<String>) -> Unit) {
    GroupLabel(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> HfChip(display(o), { onChange(if (o in selected) selected - o else selected + o) }, style = if (o in selected) ChipStyle.Selected else ChipStyle.Outline) }
    }
}

fun pct(v: Int?): String = when { v == null -> "—"; v > 0 -> "+$v%"; v < 0 -> "${Fmt.MINUS}${-v}%"; else -> "0%" }
