package com.hellfire.ass.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Outline icons copied from the design SVGs: 24-unit viewBox, 1.8 stroke, round caps and joins. Tinted by `Icon`. */
object HfIcons {
    val ChevronRight = icon("m9 6 6 6-6 6")
    val ChevronDown = icon("m6 9 6 6 6-6")
    val Back = icon("m15 6-6 6 6 6")
    val Plus = icon("M12 5v14M5 12h14")
    val Check = icon("m5 12.5 4.5 4.5L19 7.5")
    val Close = icon("M7 7l10 10M17 7 7 17")
    val Home = icon("M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z")
    val Money = icon(rect(3f, 6f, 18f, 13f, 3f), "M3 10h18M16 14.5h2")
    val Spark = icon("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")
    val Task = icon(rect(4f, 4f, 16f, 16f, 4f), "m8.5 12 2.5 2.5 4.5-5")
    val Inbox = icon("M3 13h5l1.5 3h5L16 13h5", "M5.5 5h13L21 13v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-5z")
    val Filter = icon("M4 5h16l-6 8v5l-4 2v-7z")
    val Export = icon("M12 3v12M7 8l5-5 5 5M5 14v6h14v-6")
    val Download = icon("M12 4v11M7 10l5 5 5-5M5 20h14")
    val Chat = icon("M4 5h16v11H8l-4 4z")
    val ChatLines = icon("M4 5h16v11H8l-4 4z", "M8 9.5h8M8 12.5h5")
    val Mic = icon(rect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3")
    val Chip = icon(rect(7f, 7f, 10f, 10f, 2f), "M9 3v4M15 3v4M9 17v4M15 17v4M3 9h4M3 15h4M17 9h4M17 15h4")
    val Lock = icon(rect(5f, 10f, 14f, 10f, 2f), "M8 10V7a4 4 0 0 1 8 0v3")
    val Reports = icon("M4 20V10M10 20V4M16 20v-7M21 20H3")
    val ArrowIn = icon("M17 7 7 17M7 9v8h8")
    val ArrowOut = icon("M7 17 17 7M9 7h8v8")
    val ArrowUp = icon("M12 19V5M5 12l7-7 7 7")
    val Bolt = icon("M13 3 5 14h6l-1 7 8-11h-6z")
    val Alarm = icon(circle(12f, 13f, 7f), "M12 10v3l2 1.5M5 4 3 6M19 4l2 2")
    val Phone = icon(rect(7f, 3f, 10f, 18f, 2f), "M11 18h2")
    val Battery = icon(rect(3f, 7f, 16f, 10f, 2.5f), "M21 10.5v3M7 10v4M10.5 10v4")
    val Mail = icon(rect(3f, 5f, 18f, 14f, 2f), "m3 7 9 6 9-6")
    val Cloud = icon("M7 18a4 4 0 0 1-.5-8A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z")
    val Bell = icon("M6 9a6 6 0 1 1 12 0c0 6 2 7 2 7H4s2-1 2-7", "M10 20a2 2 0 0 0 4 0")
    val Settings = icon("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f))
    val Waveform = icon("M4 12h2M8 8v8M12 5v14M16 8v8M20 12h-2")
    val Food = icon("M4 11h16M6 11V9a6 6 0 0 1 12 0v2M5 11l1 8h12l1-8")
    val Bank = icon("M3 10 12 4l9 6M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20h18")
    val Key = icon(circle(8f, 15f, 4f), "m11 12 8-8M16 7l2 2")
    val Info = icon(circle(12f, 12f, 9f), "M12 7.5v5M12 16v.5")
    val Search = icon(circle(11f, 11f, 6.5f), "m16 16 4 4")
}

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

private fun icon(vararg paths: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
