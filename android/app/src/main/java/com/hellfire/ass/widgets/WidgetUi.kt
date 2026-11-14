package com.hellfire.ass.widgets

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hellfire.ass.MainActivity
import com.hellfire.ass.ui.theme.DarkHfColors
import com.hellfire.ass.ui.theme.HfColors
import com.hellfire.ass.ui.theme.LightHfColors

/** Widget colours from the §3 tokens; GlanceTheme follows the system light/dark setting. */
object WidgetColors {
    private fun scheme(c: HfColors, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.primaryPressed,
        secondaryContainer = c.fill, onSecondaryContainer = c.onSurface, surface = c.surface, onSurface = c.onSurface,
        surfaceVariant = c.fill, onSurfaceVariant = c.muted, background = c.surface, onBackground = c.onSurface,
        error = c.danger, onError = c.onPrimary, outline = c.outline,
    )
    val providers = ColorProviders(light = scheme(LightHfColors, false), dark = scheme(DarkHfColors, true))
    private fun pair(pick: (HfColors) -> Color) = ColorProvider(day = pick(LightHfColors), night = pick(DarkHfColors))
    val success = pair { it.success }; val successBg = pair { it.successContainer }
    val warning = pair { it.warning }; val warningBg = pair { it.warningContainer }
    val danger = pair { it.danger }; val dangerBg = pair { it.dangerContainer }
    val recording = pair { Color(0xFFB91C1C) }
    fun dark(context: Context) = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    fun tokens(context: Context) = if (dark(context)) DarkHfColors else LightHfColors
    /** Category colours from §8 (primary, orange, blue, teal, grey). */
    fun categories(context: Context): List<Int> = tokens(context).let { listOf(it.primary, it.seriesOrange, it.seriesBlue, it.seriesTeal, it.faint).map(Color::toArgb) }
}

/** Deep link into the app; the activity routes it (see Nav.open). Nothing is sent or changed by opening it. */
fun openApp(context: Context, link: String): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).setData(Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

@Composable
fun WidgetCard(link: String?, padding: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(22.dp).padding(padding.dp)
            .then(if (link != null) GlanceModifier.clickable(openApp(context, link)) else GlanceModifier),
        content = content,
    )
}

@Composable
fun Head(title: String, meta: String? = null) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        if (meta != null) Text(meta, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
    }
}

@Composable
fun Label(text: String) = Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)

@Composable
fun Meta(text: String, maxLines: Int = 1) = Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = maxLines)

@Composable
fun Body(text: String, modifier: GlanceModifier = GlanceModifier, bold: Boolean = false, size: Int = 13, maxLines: Int = 1) =
    Text(text, modifier, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), maxLines = maxLines)

@Composable
fun Big(text: String, color: ColorProvider = GlanceTheme.colors.onSurface, size: Int = 22) =
    Text(text, style = TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight.Bold), maxLines = 1)

enum class ChipTone { Neutral, Accent, Warn, Crit }

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.Neutral, action: Action? = null) {
    val (bg, fg) = when (tone) {
        ChipTone.Neutral -> GlanceTheme.colors.secondaryContainer to GlanceTheme.colors.onSurface
        ChipTone.Accent -> GlanceTheme.colors.primaryContainer to GlanceTheme.colors.onPrimaryContainer
        ChipTone.Warn -> WidgetColors.warningBg to WidgetColors.warning
        ChipTone.Crit -> WidgetColors.dangerBg to WidgetColors.danger
    }
    Box(GlanceModifier.background(bg).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 3.dp).then(if (action != null) GlanceModifier.clickable(action) else GlanceModifier)) {
        Text(text, style = TextStyle(color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
fun FilledButton(text: String, action: Action, modifier: GlanceModifier = GlanceModifier) {
    Box(modifier.background(GlanceTheme.colors.primary).cornerRadius(18.dp).padding(horizontal = 14.dp, vertical = 8.dp).clickable(action),
        contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
fun TonalButton(text: String, action: Action) {
    Box(GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 5.dp).clickable(action)) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
fun Gap(h: Int = 6) = Spacer(GlanceModifier.height(h.dp))

/** Shown when encrypted storage cannot be opened from the widget (e.g. right after a reboot before unlock). */
@Composable
fun Unavailable(title: String, link: String) = WidgetCard(link) {
    Head(title)
    Gap()
    Meta("Open HellFire to load this.", maxLines = 2)
}

// ---------------- Bitmaps (Glance cannot draw shapes) ----------------

object WidgetBitmaps {
    private const val SCALE = 3f
    fun stackBar(context: Context, parts: List<Long>, widthDp: Int = 300, heightDp: Int = 10): Bitmap {
        val w = (widthDp * SCALE).toInt(); val h = (heightDp * SCALE).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val colors = WidgetColors.categories(context); val r = h / 2f
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawColor(WidgetColors.tokens(context).fill.toArgb())
        val total = parts.sum().coerceAtLeast(1)
        var x = 0f
        parts.forEachIndexed { i, v -> val pw = w * v.toFloat() / total
            c.drawRect(x, 0f, x + pw, h.toFloat(), Paint().apply { color = colors[i.coerceAtMost(colors.size - 1)] }); x += pw }
        return bmp
    }
    fun sparkline(context: Context, values: List<Long>, widthDp: Int = 140, heightDp: Int = 46): Bitmap {
        val t = WidgetColors.tokens(context)
        val w = (widthDp * SCALE).toInt(); val h = (heightDp * SCALE).toInt(); val pad = 4 * SCALE
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val grid = Paint().apply { color = t.fill.toArgb(); strokeWidth = SCALE }
        listOf(0.5f, 1f).forEach { f -> val y = h - pad - f * (h - 2 * pad); c.drawLine(0f, y, w.toFloat(), y, grid) }
        if (values.size < 2) return bmp
        val max = values.max().coerceAtLeast(1)
        val pts = values.mapIndexed { i, v -> (pad + i * (w - 2 * pad) / (values.size - 1)) to (h - pad - v.toFloat() / max * (h - 2 * pad)) }
        val line = Path().apply { moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { lineTo(it.first, it.second) } }
        val area = Path(line).apply { lineTo(pts.last().first, h - pad); lineTo(pts[0].first, h - pad); close() }
        c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.primaryContainer.toArgb(); alpha = 180 })
        c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.primary.toArgb(); style = Paint.Style.STROKE; strokeWidth = 2 * SCALE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND })
        val (lx, ly) = pts.last()
        c.drawCircle(lx, ly, 3.5f * SCALE + SCALE, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.surface.toArgb() })
        c.drawCircle(lx, ly, 3.5f * SCALE, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.primary.toArgb() })
        return bmp
    }
    fun ring(context: Context, fraction: Float, label: String, warn: Boolean, sizeDp: Int = 72): Bitmap {
        val t = WidgetColors.tokens(context)
        val s = (sizeDp * SCALE).toInt(); val stroke = 8 * SCALE
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val box = RectF(stroke / 2, stroke / 2, s - stroke / 2, s - stroke / 2)
        c.drawArc(box, 0f, 360f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.fill.toArgb(); style = Paint.Style.STROKE; strokeWidth = stroke })
        c.drawArc(box, -90f, 360f * fraction.coerceIn(0f, 1f), false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (if (warn) t.warning else t.success).toArgb(); style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND })
        c.drawText(label, s / 2f, s / 2f + 4.5f * SCALE, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.onSurface.toArgb(); textSize = 13 * SCALE; textAlign = Paint.Align.CENTER; isFakeBoldText = true })
        return bmp
    }
}

@Composable
fun BitmapImage(bitmap: Bitmap, description: String, modifier: GlanceModifier) =
    Image(ImageProvider(bitmap), description, modifier, contentScale = androidx.glance.layout.ContentScale.FillBounds)
