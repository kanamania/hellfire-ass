package com.hellfire.ass.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons

val ScreenPadding = 20.dp

// ---------- Surfaces ----------

/** White card with a 1 dp outline. The highlighted variant has a 1.5 dp primary border. */
@Composable
fun HfCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    radius: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Hf.colors.surface)
            .border(if (highlighted) BorderStroke(1.5.dp, Hf.colors.primary) else BorderStroke(1.dp, Hf.colors.outline), shape)
            .padding(padding),
        content = content,
    )
}

/** Card holding [ListRow]s separated by [ListDivider]s. */
@Composable
fun ListCard(modifier: Modifier = Modifier, title: String? = null, count: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    HfCard(modifier, radius = 22.dp) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Hf.type.cardTitle, color = Hf.colors.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
                if (count != null) CountBadge(count)
            }
        }
        content()
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tileText: String? = null,
    tone: Tone = Tone.Primary,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    minHeight: Dp = 56.dp,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null || tileText != null) IconTile(icon = icon, text = tileText, tone = tone)
        Column(Modifier.weight(1f)) {
            Text(title, style = Hf.type.rowTitle, color = Hf.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (chevron) Icon(HfIcons.ChevronRight, null, Modifier.size(18.dp), tint = Hf.colors.faint)
    }
}

@Composable
fun ListDivider(inset: Dp = 68.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(Hf.colors.divider))
}

/** Rounded-square tile holding an 18 dp icon or a single glyph. */
@Composable
fun IconTile(icon: ImageVector? = null, text: String? = null, tone: Tone = Tone.Primary, size: Dp = 36.dp) {
    val (bg, fg) = tone.colors()
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        else if (text != null) Text(text, style = Hf.type.figure.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = fg)
    }
}

@Composable
fun CountBadge(count: Int) {
    Text(
        count.toString(),
        Modifier.clip(RoundedCornerShape(10.dp)).background(Hf.colors.primary).padding(horizontal = 8.dp, vertical = 2.dp),
        style = Hf.type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Hf.colors.onPrimary,
    )
}

// ---------- Tones and pills ----------

enum class Tone { Primary, Success, Warning, Danger, Info, Neutral }

@Composable
fun Tone.colors(): Pair<Color, Color> = with(Hf.colors) {
    when (this@colors) {
        Tone.Primary -> primaryContainer to primary
        Tone.Success -> successContainer to success
        Tone.Warning -> warningContainer to warning
        Tone.Danger -> dangerContainer to danger
        Tone.Info -> infoContainer to info
        Tone.Neutral -> fill to bodySecondary
    }
}

@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val (bg, fg) = tone.colors()
    Text(
        text,
        modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
        style = Hf.type.caption.copy(fontWeight = FontWeight.SemiBold),
        color = if (tone == Tone.Success) Hf.colors.onSuccessContainer else fg,
        maxLines = 1,
    )
}

/** Inline status banner, e.g. "Reply sent to Juma on WhatsApp". */
@Composable
fun Banner(text: String, tone: Tone = Tone.Success, icon: ImageVector? = HfIcons.Check, modifier: Modifier = Modifier) {
    val (bg, fg) = tone.colors()
    val textColor = if (tone == Tone.Success) Hf.colors.onSuccessContainer else fg
    Row(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = textColor)
        Text(com.hellfire.ass.ui.tr(text), style = Hf.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = textColor)
    }
}

// ---------- Buttons ----------

private val PillShape = CircleShape

@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, height: Dp = 44.dp,
) {
    Button(
        onClick, modifier.heightIn(min = height), enabled = enabled, shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.primary, contentColor = Hf.colors.onPrimary),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.fill, contentColor = Hf.colors.onSurface),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = PillShape,
        colors = ButtonDefaults.textButtonColors(contentColor = Hf.colors.muted),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) { ButtonContent(text, null) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(text, style = Hf.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
}

/** 44 dp outlined circle, used for top-bar actions. */
@Composable
fun IconCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Hf.colors.surface)
            .border(1.dp, Hf.colors.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = Hf.colors.onSurface) }
}

// ---------- Headers ----------

/** Top-level tab title with optional overline (e.g. the date on Home) and trailing actions. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, overline: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (overline != null) Text(overline, style = Hf.type.label, color = Hf.colors.muted)
            Text(title, style = Hf.type.screenTitle, color = Hf.colors.onSurface, modifier = Modifier.semantics { heading() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Top bar for pushed screens: back arrow, title with optional breadcrumb, trailing actions. */
@Composable
fun PushedTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, breadcrumb: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.offset(x = (-10).dp).size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { Icon(HfIcons.Back, null, Modifier.size(22.dp), tint = Hf.colors.onSurface) }
        Column(Modifier.weight(1f).offset(x = (-10).dp)) {
            if (breadcrumb != null) Text(breadcrumb, style = Hf.type.rowSubtitle, color = Hf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = if (breadcrumb != null) Hf.type.reportTitle else Hf.type.screenTitle, color = Hf.colors.onSurface,
                modifier = Modifier.semantics { heading() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Hf.type.cardTitle, color = Hf.colors.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, style = Hf.type.label, color = Hf.colors.muted)
        if (action != null && onAction != null) Text(
            action,
            Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 6.dp, vertical = 12.dp),
            style = Hf.type.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = Hf.colors.primary,
        )
    }
}

// ---------- ActionCard ----------

/**
 * A proposal the owner must act on: context line, quoted source, proposed result, then buttons.
 * Used for payment candidates, extracted tasks, assistant proposals and message drafts.
 */
@Composable
fun ActionCard(
    modifier: Modifier = Modifier,
    context: String? = null,
    contextIcon: ImageVector? = null,
    contextTone: Tone = Tone.Success,
    quote: String? = null,
    highlighted: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HfCard(modifier, highlighted = highlighted, padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (context != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (contextIcon != null) Icon(contextIcon, null, Modifier.size(16.dp), tint = contextTone.colors().second)
                Text(context, style = Hf.type.rowSubtitle.copy(fontWeight = FontWeight.Medium), color = Hf.colors.muted)
            }
            if (quote != null) Text(
                "“$quote”",
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Hf.colors.subtle).padding(horizontal = 12.dp, vertical = 10.dp),
                style = Hf.type.body.copy(fontSize = 13.5.sp, fontStyle = FontStyle.Italic), color = Hf.colors.bodySecondary,
            )
            content()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

// ---------- SegmentedControl ----------

@Composable
fun SegmentedControl(
    options: List<String>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false,
) {
    val pad = if (compact) 3.dp else 4.dp
    Row(
        modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())
            .clip(CircleShape).background(Hf.colors.track).padding(pad).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(pad),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                (if (compact) Modifier else Modifier.weight(1f))
                    .heightIn(min = if (compact) 34.dp else 38.dp)
                    .then(if (on) Modifier.shadow(1.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(if (on) Hf.colors.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(com.hellfire.ass.ui.tr(option), style = Hf.type.label.copy(fontSize = if (compact) 13.sp else 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = if (on) Hf.colors.onSurface else Hf.colors.muted)
            }
        }
    }
}

// ---------- Chips ----------

enum class ChipStyle { Outline, Selected, Filter, Add }

/** Outline chip; dark when selected; tinted with × for an active filter; dashed for "+ Filter". */
@Composable
fun HfChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: ChipStyle = ChipStyle.Outline) {
    val c = Hf.colors
    val (bg, fg, border) = when (style) {
        ChipStyle.Outline -> Triple(c.surface, c.onSurface, c.outline)
        ChipStyle.Selected -> Triple(c.inverse, c.onInverse, c.inverse)
        ChipStyle.Filter -> Triple(c.primaryContainer, c.primaryPressed, c.primaryContainer)
        ChipStyle.Add -> Triple(c.surface, c.muted, c.handle)
    }
    val label = when (style) { ChipStyle.Filter -> "Remove filter $text"; ChipStyle.Add -> "Add filter"; else -> null }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (style == ChipStyle.Add) Modifier.drawBehind {
                    val w = 1.dp.toPx()
                    drawRoundRect(border, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(size.height / 2), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                } else Modifier.border(1.dp, border, CircleShape)
            )
            .then(
                if (style == ChipStyle.Outline || style == ChipStyle.Selected)
                    Modifier.selectable(selected = style == ChipStyle.Selected, role = Role.Button, onClick = onClick)
                else Modifier.clickable(role = Role.Button, onClick = onClick)
            )
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (style == ChipStyle.Add) Icon(HfIcons.Plus, null, Modifier.size(15.dp), tint = fg)
        Text(text, style = Hf.type.label, color = fg, maxLines = 1)
        if (style == ChipStyle.Filter) Icon(HfIcons.Close, null, Modifier.size(14.dp), tint = fg)
    }
}

/** Horizontally scrolling chip row with screen-edge padding. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

// ---------- KPI and evidence ----------

@Composable
fun KpiTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null, valueColor: Color = Color.Unspecified) {
    HfCard(modifier, radius = 16.dp, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = Hf.type.label.copy(fontSize = 12.sp), color = Hf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = Hf.type.kpiValue, color = if (valueColor == Color.Unspecified) Hf.colors.onSurface else valueColor, maxLines = 1)
        if (detail != null) Text(detail, style = Hf.type.caption.copy(fontWeight = FontWeight.Normal), color = Hf.colors.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Two-column grid of [KpiTile]s or similar equal-width items. */
@Composable
fun TwoColumnGrid(items: List<@Composable (Modifier) -> Unit>, modifier: Modifier = Modifier, gap: Dp = 12.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Dark card showing original SMS or message text in monospace. Read-only by design. */
@Composable
fun EvidenceCard(label: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Hf.colors.surfaceDark).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label.uppercase(), style = Hf.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.44.sp),
            color = Color(0xFF9AA3AD))
        Text(text, style = Hf.type.mono, color = Color(0xFFE5E8EB))
    }
}

// ---------- BottomSheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Hf.colors.surface,
        scrimColor = Color(0x73111418),
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Hf.colors.handle)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

// ---------- Composer ----------

/** Rounded text field with a send button, a separate dark mic button, and quick-suggestion chips above. */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask or tell HellFire…",
    suggestions: List<String> = emptyList(),
    onSuggestion: (String) -> Unit = {},
    onMic: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = Hf.colors
    Column(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (suggestions.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { suggestions.forEach { HfChip(it, { onSuggestion(it) }) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(50.dp).clip(CircleShape).background(c.surface).border(1.dp, c.outline, CircleShape)
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = Hf.type.body.copy(fontSize = 15.sp), color = c.muted, maxLines = 1)
                    BasicTextField(
                        value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = "Message" },
                        enabled = enabled, singleLine = true, textStyle = Hf.type.body.copy(fontSize = 15.sp, color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
                    )
                }
                val canSend = enabled && value.isNotBlank()
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (canSend) c.primary else c.fill)
                        .clickable(enabled = canSend, role = Role.Button, onClick = onSend)
                        .semantics { contentDescription = "Send" },
                    contentAlignment = Alignment.Center,
                ) { Icon(HfIcons.ArrowUp, null, Modifier.size(18.dp), tint = if (canSend) c.onPrimary else c.muted) }
            }
            if (onMic != null) Box(
                Modifier.size(50.dp).clip(CircleShape).background(c.inverse).clickable(enabled = enabled, role = Role.Button, onClick = onMic)
                    .semantics { contentDescription = "Speak" },
                contentAlignment = Alignment.Center,
            ) { Icon(HfIcons.Mic, null, Modifier.size(22.dp), tint = c.onInverse) }
        }
    }
}
