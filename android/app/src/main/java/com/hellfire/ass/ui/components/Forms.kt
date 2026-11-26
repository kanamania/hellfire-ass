package com.hellfire.ass.ui.components

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.Fmt
import java.time.LocalDate
import java.time.LocalTime
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)

/** Muted explanatory paragraph (`.note`). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = Hf.colors.muted) {
    Text(text, modifier.padding(horizontal = 4.dp), style = Hf.type.rowSubtitle.copy(lineHeight = 18.sp), color = color)
}

/** Small uppercase group label used above setting groups and in sheets. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(horizontal = 4.dp), style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.24.sp),
        color = Hf.colors.muted)
}

/** Definition list in a card (`dl.kv`). */
@Composable
fun KvList(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    HfCard(modifier, radius = 18.dp) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) ListDivider(0.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, style = Hf.type.body.copy(fontSize = 13.5.sp), color = Hf.colors.muted)
                Text(v, Modifier.weight(1f), style = Hf.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = Hf.colors.onSurface, textAlign = TextAlign.End)
            }
        }
    }
}

/** Single-line styled text field with a floating label. */
@Composable
fun HfTextField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    singleLine: Boolean = true, password: Boolean = false, number: Boolean = false, enabled: Boolean = true,
    supporting: String? = null, minLines: Int = 1,
) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) },
        singleLine = singleLine, minLines = minLines, shape = RoundedCornerShape(14.dp),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = when {
            password -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
            number -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        supportingText = supporting?.let { { Text(it) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Hf.colors.primary, unfocusedBorderColor = Hf.colors.handle,
            focusedContainerColor = Hf.colors.surface, unfocusedContainerColor = Hf.colors.surface,
            focusedLabelColor = Hf.colors.primary, unfocusedLabelColor = Hf.colors.muted,
        ),
    )
}

/**
 * Text field with type-ahead suggestions shown inline below it (popups fight the keyboard inside bottom sheets).
 * Matches anywhere in a suggestion, case-insensitive; free text is always allowed.
 */
@Composable
fun AutocompleteField(value: String, onValueChange: (String) -> Unit, label: String, suggestions: List<String>, onPick: (String) -> Unit,
    modifier: Modifier = Modifier, max: Int = 5) {
    var focused by remember { mutableStateOf(false) }
    val query = value.trim()
    val matches = suggestions.distinct().filter { s -> !s.equals(query, ignoreCase = true) && (query.isEmpty() || s.contains(query, ignoreCase = true)) }
        .sortedWith(compareBy({ !it.startsWith(query, ignoreCase = true) }, { it.lowercase() })).take(max)
    Column(modifier.fillMaxWidth()) {
        HfTextField(value, onValueChange, label, Modifier.onFocusChanged { focused = it.isFocused })
        if (focused && matches.isNotEmpty()) HfCard(Modifier.padding(top = 4.dp), radius = 14.dp) {
            matches.forEachIndexed { i, m ->
                if (i > 0) ListDivider(14.dp)
                Text(m, Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(m) }.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    style = Hf.type.body, color = Hf.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Rounded "Group: Category ▾" style dropdown (`.dd`). */
@Composable
fun <T> Dropdown(label: String, value: T, options: List<T>, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 40.dp).clip(CircleShape).background(Hf.colors.surface).border(1.dp, Hf.colors.outline, CircleShape)
                .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = Hf.type.label, color = Hf.colors.muted)
            Text(display(value), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.onSurface, maxLines = 1)
            Icon(HfIcons.ChevronDown, null, Modifier.size(14.dp), tint = Hf.colors.muted)
        }
        DropdownMenu(open, { open = false }, containerColor = Hf.colors.surface) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option), style = Hf.type.body) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

/** Full-width labelled choice that opens a menu; for form sheets. */
@Composable
fun <T> ChoiceField(label: String, value: T?, options: List<T>, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
    placeholder: String? = null) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Hf.colors.handle, RoundedCornerShape(14.dp))
                .background(Hf.colors.surface).clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, style = Hf.type.caption.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value?.let(display) ?: (placeholder ?: androidx.compose.ui.res.stringResource(R.string.ui_choose)), Modifier.weight(1f), style = Hf.type.body,
                    color = if (value == null) Hf.colors.muted else Hf.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HfIcons.ChevronDown, null, Modifier.size(16.dp), tint = Hf.colors.muted)
            }
        }
        DropdownMenu(open, { open = false }, containerColor = Hf.colors.surface) {
            options.forEach { option -> DropdownMenuItem(text = { Text(display(option), style = Hf.type.body) }, onClick = { onSelect(option); open = false }) }
        }
    }
}

/** Setting row with a trailing switch. The whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Hf.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = Hf.colors.onSurface)
            if (subtitle != null) Text(subtitle, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        }
        Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = Hf.colors.primary, checkedThumbColor = Hf.colors.onPrimary,
            uncheckedTrackColor = Hf.colors.handle, uncheckedThumbColor = Hf.colors.surface, uncheckedBorderColor = Hf.colors.handle))
    }
}

/** Checkbox row (`.ck`). */
@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 44.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                .background(if (checked) Hf.colors.primary else Hf.colors.surface)
                .border(2.dp, if (checked) Hf.colors.primary else Hf.colors.faint, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(HfIcons.Check, null, Modifier.size(15.dp), tint = Hf.colors.onPrimary) }
        Text(text, style = Hf.type.body.copy(fontSize = 14.sp), color = Hf.colors.onSurface)
    }
}

/** Radio option card used in the export sheet (`.opt`). */
@Composable
fun OptionCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (selected) Hf.colors.primaryTint else Hf.colors.surface)
            .border(1.dp, if (selected) Hf.colors.primary else Hf.colors.outline, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape)
            .border(if (selected) 6.dp else 2.dp, if (selected) Hf.colors.primary else Hf.colors.faint, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title, style = Hf.type.rowTitle.copy(fontSize = 14.sp), color = Hf.colors.onSurface)
            Text(subtitle, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
        }
    }
}

// ---------- Data table ----------

data class TableColumn(val title: String, val numeric: Boolean = false, val width: Dp = 96.dp)

/** One table row. [sortKeys] align with columns and drive sorting; [onClick] opens the next level. */
data class TableRow(val key: String, val cells: List<String>, val sortKeys: List<Comparable<*>?> = cells,
    val highlight: Boolean = false, val onClick: (() -> Unit)? = null)

/**
 * Sortable table with a pinned first column, a totals row and horizontally scrolling remaining columns.
 * [sort] is (column index, ascending); tapping a header toggles it.
 */
@Composable
fun DataTable(
    columns: List<TableColumn>, rows: List<TableRow>, modifier: Modifier = Modifier,
    totals: List<String>? = null, sort: Pair<Int, Boolean>? = null, onSort: ((Pair<Int, Boolean>) -> Unit)? = null,
    firstWidth: Dp = 132.dp,
) {
    val c = Hf.colors
    @Suppress("UNCHECKED_CAST")
    val sorted = if (sort == null) rows else rows.sortedWith { a, b ->
        val x = a.sortKeys.getOrNull(sort.first) as Comparable<Any>?
        val y = b.sortKeys.getOrNull(sort.first) as Comparable<Any>?
        val r = when { x == null && y == null -> 0; x == null -> -1; y == null -> 1; else -> x.compareTo(y) }
        if (sort.second) r else -r
    }
    val rowHeight = 46.dp
    val shape = RoundedCornerShape(18.dp)
    fun header(i: Int): String = columns[i].title + if (sort?.first == i) (if (sort.second) " ↑" else " ↓") else ""
    @Composable
    fun Cell(text: String, width: Dp, numeric: Boolean, bold: Boolean, color: Color, bg: Color, height: Dp, isHeader: Boolean, col: Int) {
        Box(
            Modifier.width(width).height(height).background(bg)
                .then(if (isHeader && onSort != null) Modifier.clickable(role = Role.Button) {
                    onSort(if (sort?.first == col) col to !sort.second else col to columns[col].numeric.not())
                } else Modifier)
                .padding(horizontal = 10.dp),
            contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Text(text, style = if (isHeader) Hf.type.caption.copy(fontWeight = FontWeight.SemiBold)
                else Hf.type.body.copy(fontSize = 13.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontFeatureSettings = "tnum"),
                color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.outline, shape)) {
        Column(Modifier.width(firstWidth)) {
            Cell(header(0), firstWidth, false, false, if (sort?.first == 0) c.onSurface else c.muted, c.subtle, 38.dp, true, 0)
            sorted.forEach { row ->
                Box(Modifier.height(1.dp).fillMaxWidth().background(c.divider))
                Box(if (row.onClick != null) Modifier.clickable(role = Role.Button, onClick = row.onClick) else Modifier) {
                    Cell(row.cells[0], firstWidth, false, false, if (row.onClick != null) c.onSurface else c.onSurface,
                        if (row.highlight) c.primaryTint else c.surface, rowHeight, false, 0)
                }
            }
            if (totals != null) {
                Box(Modifier.height(1.dp).fillMaxWidth().background(c.divider))
                Cell(totals[0], firstWidth, false, true, c.onSurface, c.subtle, rowHeight, false, 0)
            }
        }
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Row { (1 until columns.size).forEach { i -> Cell(header(i), columns[i].width, columns[i].numeric, false,
                if (sort?.first == i) c.onSurface else c.muted, c.subtle, 38.dp, true, i) }
                Box(Modifier.width(30.dp).height(38.dp).background(c.subtle)) }
            sorted.forEach { row ->
                Box(Modifier.height(1.dp).width(columns.drop(1).fold(30.dp) { a, col -> a + col.width }).background(c.divider))
                Row((if (row.onClick != null) Modifier.clickable(role = Role.Button, onClick = row.onClick) else Modifier)
                    .semantics(mergeDescendants = true) {}) {
                    (1 until columns.size).forEach { i -> Cell(row.cells.getOrElse(i) { "" }, columns[i].width, columns[i].numeric, false, c.onSurface,
                        if (row.highlight) c.primaryTint else c.surface, rowHeight, false, i) }
                    Box(Modifier.width(30.dp).height(rowHeight).background(if (row.highlight) c.primaryTint else c.surface), contentAlignment = Alignment.CenterStart) {
                        if (row.onClick != null) Icon(HfIcons.ChevronRight, "Open", Modifier.size(16.dp), tint = c.faint)
                    }
                }
            }
            if (totals != null) {
                Box(Modifier.height(1.dp).width(columns.drop(1).fold(30.dp) { a, col -> a + col.width }).background(c.divider))
                Row { (1 until columns.size).forEach { i -> Cell(totals.getOrElse(i) { "" }, columns[i].width, columns[i].numeric, true, c.onSurface, c.subtle, rowHeight, false, i) }
                    Box(Modifier.width(30.dp).height(rowHeight).background(c.subtle)) }
            }
        }
    }
}

/** Footer under tables: left info, right hint. */
@Composable
fun TableFooter(left: String, right: String? = null, onRight: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, Modifier.weight(1f), style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal), color = Hf.colors.muted)
        if (right != null) Text(right,
            if (onRight != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onRight).padding(horizontal = 6.dp, vertical = 10.dp) else Modifier,
            style = Hf.type.caption.copy(fontSize = 12.sp, fontWeight = if (onRight != null) FontWeight.SemiBold else FontWeight.Normal),
            color = if (onRight != null) Hf.colors.primary else Hf.colors.muted)
    }
}

/** Generic confirmation dialog in the app style. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Hf.colors.surface, shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = Hf.type.cardTitle.copy(fontSize = 18.sp)) },
        text = { Text(text, style = Hf.type.body, color = Hf.colors.bodySecondary) },
        confirmButton = { TextButton(onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) Hf.colors.danger else Hf.colors.primary)) {
            Text(confirm, style = Hf.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)) } },
        dismissButton = { TextButton(onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Hf.colors.muted)) {
            Text(stringResource(R.string.ui_cancel), style = Hf.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)) } },
    )
}

/** Card section wrapper used to restyle older form screens: grouped white card with padding and spacing. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HfCard(modifier, padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = Hf.type.cardTitle, color = Hf.colors.onSurface)
            content()
        }
    }
}

/** Date and time are picked separately and always interpreted in East Africa Time. */
@Composable
fun DateTimeField(value: String, onValueChange: (String) -> Unit, label: String? = null) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableIntStateOf(0) }
    val current = Fmt.zoned(TaskTime.parse(value))
    val onChange by rememberUpdatedState(onValueChange)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupLabel(label ?: androidx.compose.ui.res.stringResource(R.string.ui_date_and_time_east_africa_time))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(current.toLocalDate().toString(), Modifier.semantics { contentDescription = "Choose date: ${current.toLocalDate()}" })
            }
            OutlinedButton(onClick = { picking = 2 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(Fmt.time(current.toInstant().toEpochMilli()), Modifier.semantics { contentDescription = "Choose time: ${Fmt.time(current.toInstant().toEpochMilli())} East Africa Time" })
            }
        }
    }
    if (picking != 0) DisposableEffect(picking) {
        val dialog = if (picking == 1) android.app.DatePickerDialog(context, { _, year, month, day ->
            onChange(TaskTime.display(LocalDate.of(year, month + 1, day).atTime(current.toLocalTime()).atZone(TaskTime.zone).toInstant().toEpochMilli()))
        }, current.year, current.monthValue - 1, current.dayOfMonth).apply {
            datePicker.minDate = 0
        } else android.app.TimePickerDialog(context, { _, hour, minute ->
            onChange(TaskTime.display(current.toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(TaskTime.zone).toInstant().toEpochMilli()))
        }, current.hour, current.minute, true)
        dialog.setOnDismissListener { picking = 0 }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
