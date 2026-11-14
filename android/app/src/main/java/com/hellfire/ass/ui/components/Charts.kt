package com.hellfire.ass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.ui.theme.Hf

/** One named data series for the Canvas charts. No chart library is used (§1). */
data class Series(val name: String, val color: Color, val values: List<Long>)

@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(name, style = Hf.type.caption.copy(fontSize = 12.sp), color = Hf.colors.muted)
            }
        }
    }
}

/** Card with a chart title, optional legend and chart content. */
@Composable
fun ChartCard(title: String, modifier: Modifier = Modifier, legend: List<Pair<String, Color>> = emptyList(),
    trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    HfCard(modifier, padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Hf.type.rowTitle, color = Hf.colors.onSurface, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = Hf.type.label, color = Hf.colors.muted)
            }
            if (legend.isNotEmpty()) Legend(legend)
            content()
        }
    }
}

/** Vertical bars: side by side per group, or stacked when [stacked] is true. */
@Composable
fun BarChart(groups: List<String>, series: List<Series>, modifier: Modifier = Modifier, stacked: Boolean = false,
    height: Dp = 150.dp, description: String = "Bar chart") {
    val c = Hf.colors
    val max = if (stacked) groups.indices.maxOfOrNull { i -> series.sumOf { it.values.getOrElse(i) { 0 }.coerceAtLeast(0) } } ?: 0
        else series.maxOfOrNull { s -> s.values.maxOfOrNull { it } ?: 0 } ?: 0
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val baseline = size.height - 1.dp.toPx()
            drawLine(c.divider, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            drawLine(c.divider, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            if (groups.isEmpty() || max <= 0) return@Canvas
            val slot = size.width / groups.size
            val barArea = slot * 0.62f
            val radius = CornerRadius(4.dp.toPx())
            groups.indices.forEach { g ->
                val left = g * slot + (slot - barArea) / 2
                if (stacked) {
                    var top = baseline
                    series.forEach { s ->
                        val h = s.values.getOrElse(g) { 0 }.coerceAtLeast(0).toFloat() / max * (baseline - 4.dp.toPx())
                        if (h > 0) drawRoundRect(s.color, Offset(left, top - h), Size(barArea, h), radius)
                        top -= h
                    }
                } else {
                    val gap = 3.dp.toPx()
                    val w = (barArea - gap * (series.size - 1)) / series.size.coerceAtLeast(1)
                    series.forEachIndexed { i, s ->
                        val h = s.values.getOrElse(g) { 0 }.coerceAtLeast(0).toFloat() / max * (baseline - 4.dp.toPx())
                        if (h > 0) drawRoundRect(s.color, Offset(left + i * (w + gap), baseline - h), Size(w, h), radius)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            groups.forEach { Text(it, Modifier.weight(1f), style = Hf.type.caption.copy(fontSize = 11.sp), color = Hf.colors.muted,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Labelled horizontal bars, e.g. expenses by category. Labels and values are text, so they stay accessible. */
@Composable
fun HorizontalBars(items: List<Pair<String, Long>>, format: (Long) -> String, modifier: Modifier = Modifier,
    color: Color = Hf.colors.primary, labelWidth: Dp = 84.dp, onClick: ((String) -> Unit)? = null) {
    val max = items.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 24.dp)
                    .then(if (onClick != null) Modifier.clip(RoundedCornerShape(6.dp)).clickableRow { onClick(label) } else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, Modifier.width(labelWidth), style = Hf.type.label, color = Hf.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(8.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((value.toFloat() / max).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Text(format(value), Modifier.widthIn(min = 52.dp), style = Hf.type.label.copy(fontFeatureSettings = "tnum"),
                    color = Hf.colors.muted, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** Line chart with optional area fill and end dot. Each call has its own scale. */
@Composable
fun LineChart(values: List<Long>, modifier: Modifier = Modifier, color: Color = Hf.colors.primary, fill: Boolean = true,
    height: Dp = 120.dp, description: String = "Line chart") {
    val c = Hf.colors
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val pad = 4.dp.toPx()
        drawLine(c.divider, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), 1.dp.toPx())
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val span = (max - min).coerceAtLeast(1).toFloat()
        val points = values.mapIndexed { i, v ->
            Offset(pad + i * (size.width - 2 * pad) / (values.size - 1), size.height - pad - (v - min) / span * (size.height - 2 * pad))
        }
        val line = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
        if (fill) drawPath(Path().apply { addPath(line); lineTo(points.last().x, size.height - pad); lineTo(points[0].x, size.height - pad); close() },
            c.primaryContainer.copy(alpha = 0.7f))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.surface, 5.dp.toPx(), points.last())
        drawCircle(color, 3.5.dp.toPx(), points.last())
    }
}

/** Horizontal progress meter, e.g. AI spend against the cap. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Hf.colors.primary, description: String) {
    Box(modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Hf.colors.track).semantics { contentDescription = description }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(6.dp)).background(color))
    }
}

/** Single bar split into category parts. */
@Composable
fun StackBar(parts: List<Pair<Long, Color>>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val total = parts.sumOf { it.first.coerceAtLeast(0) }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Hf.colors.track)) {
        parts.filter { it.first > 0 }.forEach { (v, color) -> Box(Modifier.fillMaxHeight().weight(v.toFloat() / total).background(color)) }
        val rest = 1f - parts.sumOf { it.first.coerceAtLeast(0) }.toFloat() / total
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}

/** Summary line under charts: "23 on time · 4 late · …" with coloured dots. */
@Composable
fun DotSummary(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (text, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                Spacer(Modifier.width(6.dp))
                Text(text, style = Hf.type.label, color = Hf.colors.onSurface)
            }
        }
    }
}
