package com.hellfire.ass.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.reports.Posted
import com.hellfire.ass.reports.spend
import com.hellfire.ass.tasks.TaskTime
import com.hellfire.ass.ui.Fmt
import com.hellfire.ass.ui.LocalNav
import com.hellfire.ass.ui.Screen
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Spent (expenses net of refunds, no fees) and received (income) for one calendar cell. Confirmed entries only. */
data class DayTotals(val spent: Long, val received: Long)

fun totalsByDay(items: List<Posted>): Map<LocalDate, DayTotals> = items.groupBy { java.time.Instant.ofEpochMilli(it.entry.occurredAt).atZone(TaskTime.zone).toLocalDate() }
    .mapValues { (_, v) -> DayTotals(v.sumOf(::spend), v.filter { it.kind == "INCOME" }.sumOf { it.entry.amount }) }

/** Week, Month and Year calendar of confirmed spending and income. Tapping a day or month lists its payments. */
@Composable
fun MoneyCalendar(items: List<Posted>) {
    val nav = LocalNav.current
    var scope by remember { mutableStateOf("Month") }
    var anchor by remember { mutableStateOf(Fmt.today()) }
    val days = remember(items) { totalsByDay(items) }
    val today = Fmt.today()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedControl(listOf("Week", "Month", "Year"), scope, { scope = it })
        val (first, last, title) = when (scope) {
            "Week" -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { m -> Triple(m, m.plusDays(6), "${Fmt.day(Fmt.startOf(m))} – ${Fmt.day(Fmt.startOf(m.plusDays(6)))} ${m.plusDays(6).year}") }
            "Month" -> YearMonth.from(anchor).let { ym -> Triple(ym.atDay(1), ym.atEndOfMonth(), ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))) }
            else -> Triple(LocalDate.of(anchor.year, 1, 1), LocalDate.of(anchor.year, 12, 31), anchor.year.toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Nav(HfIcons.Back, "Previous") { anchor = when (scope) { "Week" -> anchor.minusWeeks(1); "Month" -> anchor.minusMonths(1); else -> anchor.minusYears(1) } }
            Text(title, Modifier.weight(1f), style = Hf.type.cardTitle, color = Hf.colors.onSurface, textAlign = TextAlign.Center)
            Nav(HfIcons.ChevronRight, "Next") { anchor = when (scope) { "Week" -> anchor.plusWeeks(1); "Month" -> anchor.plusMonths(1); else -> anchor.plusYears(1) } }
        }
        val inPeriod = days.filterKeys { !it.isBefore(first) && !it.isAfter(last) }
        HfCard(padding = PaddingValues(12.dp)) {
            when (scope) {
                "Week" -> {
                    val max = (0..6).maxOf { inPeriod[first.plusDays(it.toLong())]?.spent ?: 0 }.coerceAtLeast(1)
                    (0..6).forEach { i ->
                        val d = first.plusDays(i.toLong()); val t = inPeriod[d]
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickableRow { nav.push(Screen.ReportCategory, "day:$d") }
                            .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.width(46.dp)) {
                                Text(d.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)), style = Hf.type.caption, color = if (d == today) Hf.colors.primary else Hf.colors.muted)
                                Text(d.dayOfMonth.toString(), style = Hf.type.figure.copy(fontSize = 17.sp), color = if (d == today) Hf.colors.primary else Hf.colors.onSurface)
                            }
                            Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(Hf.colors.track)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(((t?.spent ?: 0).toFloat() / max).coerceIn(0f, 1f)).clip(CircleShape).background(Hf.colors.seriesOrange))
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 76.dp)) {
                                Text(if ((t?.spent ?: 0) > 0) Fmt.outflow(t!!.spent) else "—", style = Hf.type.label.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface)
                                if ((t?.received ?: 0) > 0) Text("+" + Fmt.amount(t!!.received), style = Hf.type.caption.copy(fontFeatureSettings = "tnum"), color = Hf.colors.success)
                            }
                        }
                    }
                }
                "Month" -> {
                    val max = inPeriod.values.maxOfOrNull { it.spent }?.coerceAtLeast(1) ?: 1
                    Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), style = Hf.type.caption, color = Hf.colors.muted, textAlign = TextAlign.Center) } }
                    Spacer(Modifier.height(6.dp))
                    val lead = first.dayOfWeek.value - 1
                    val cells = List(lead) { null } + (0 until first.lengthOfMonth()).map { first.plusDays(it.toLong()) }
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            week.forEach { d ->
                                if (d == null) Spacer(Modifier.weight(1f)) else {
                                    val t = inPeriod[d]; val heat = ((t?.spent ?: 0).toFloat() / max).coerceIn(0f, 1f)
                                    Column(
                                        Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                                            .background(if (heat > 0f) Hf.colors.seriesOrange.copy(alpha = 0.12f + 0.45f * heat) else Hf.colors.subtle)
                                            .then(if (d == today) Modifier.border(1.5.dp, Hf.colors.primary, RoundedCornerShape(10.dp)) else Modifier)
                                            .clickableRow { nav.push(Screen.ReportCategory, "day:$d") }
                                            .semantics { contentDescription = "${d.dayOfMonth}: spent ${t?.spent ?: 0}, received ${t?.received ?: 0}" }
                                            .padding(vertical = 5.dp, horizontal = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(d.dayOfMonth.toString(), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (d == today) Hf.colors.primary else Hf.colors.onSurface)
                                        if ((t?.spent ?: 0) > 0) Text(Fmt.compact(t!!.spent), style = Hf.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal), color = Hf.colors.bodySecondary, maxLines = 1)
                                        if ((t?.received ?: 0) > 0) Text("+" + Fmt.compact(t!!.received), style = Hf.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal), color = Hf.colors.success, maxLines = 1)
                                    }
                                }
                            }
                            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                else -> {
                    val months = (1..12).map { m -> YearMonth.of(first.year, m) }
                    val totals = months.associateWith { ym -> inPeriod.filterKeys { YearMonth.from(it) == ym }.values.let { v -> DayTotals(v.sumOf { it.spent }, v.sumOf { it.received }) } }
                    val max = totals.values.maxOf { it.spent }.coerceAtLeast(1)
                    months.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { ym ->
                                val t = totals.getValue(ym); val heat = (t.spent.toFloat() / max).coerceIn(0f, 1f)
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(if (heat > 0f) Hf.colors.seriesOrange.copy(alpha = 0.10f + 0.40f * heat) else Hf.colors.subtle)
                                    .then(if (ym == YearMonth.from(today)) Modifier.border(1.5.dp, Hf.colors.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .clickableRow { nav.push(Screen.ReportCategory, "month:$ym") }.padding(10.dp)) {
                                    Text(ym.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)), style = Hf.type.label.copy(fontWeight = FontWeight.SemiBold), color = Hf.colors.onSurface)
                                    Text(if (t.spent > 0) Fmt.MINUS + Fmt.compact(t.spent) else "—", style = Hf.type.label.copy(fontFeatureSettings = "tnum"), color = Hf.colors.onSurface)
                                    Text(if (t.received > 0) "+" + Fmt.compact(t.received) else " ", style = Hf.type.caption.copy(fontWeight = FontWeight.Normal), color = Hf.colors.success)
                                }
                            }
                        }
                    }
                }
            }
        }
        val spent = inPeriod.values.sumOf { it.spent }; val received = inPeriod.values.sumOf { it.received }
        KpiGrid(listOf(Triple("Spent", Fmt.amount(spent), "TZS · confirmed only"), Triple("Received", Fmt.amount(received), "TZS · net ${Fmt.signed(received - spent)}")))
        Note("Darker days had more spending. Tap a day${if (scope == "Year") " or month" else ""} to see its payments. Fees and own transfers are left out.")
    }
}

@Composable
private fun Nav(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickableRow(onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = Hf.colors.onSurface)
    }
}
