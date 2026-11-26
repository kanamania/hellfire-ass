package com.hellfire.ass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hellfire.ass.R
import com.hellfire.ass.ui.UiLanguage

/** Design tokens from the UI handoff (§3). Light values are exact; dark values are derived at the same contrast targets. */
@Immutable
data class HfColors(
    val background: Color,
    val surface: Color,
    val surfaceDark: Color,
    val surfaceDarkInner: Color,
    val onSurfaceDark: Color,
    val mutedOnDark: Color,
    val onSurface: Color,
    val bodySecondary: Color,
    val muted: Color,
    val faint: Color,
    val outline: Color,
    val divider: Color,
    val handle: Color,
    val subtle: Color,
    val fill: Color,
    val track: Color,
    val inverse: Color,
    val onInverse: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryPressed: Color,
    val primaryContainer: Color,
    val primaryTint: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val seriesBlue: Color,
    val seriesOrange: Color,
    val seriesTeal: Color,
    val onTime: Color,
    val late: Color,
    val overdue: Color,
)

val LightHfColors = HfColors(
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceDark = Color(0xFF16181D),
    surfaceDarkInner = Color(0xFF23262D),
    onSurfaceDark = Color(0xFFFFFFFF),
    mutedOnDark = Color(0xFFB9C0C8),
    onSurface = Color(0xFF111418),
    bodySecondary = Color(0xFF3D444D),
    muted = Color(0xFF5B6470),
    faint = Color(0xFF8A929C),
    outline = Color(0xFFE4E7EB),
    divider = Color(0xFFEEF0F2),
    handle = Color(0xFFD3D8DE),
    subtle = Color(0xFFF7F8FA),
    fill = Color(0xFFF1F3F5),
    track = Color(0xFFE9ECEF),
    inverse = Color(0xFF111418),
    onInverse = Color(0xFFFFFFFF),
    primary = Color(0xFFC2410C),
    onPrimary = Color(0xFFFFFFFF),
    primaryPressed = Color(0xFF9A3412),
    primaryContainer = Color(0xFFFFEDE3),
    primaryTint = Color(0xFFFFF7F2),
    success = Color(0xFF15803D),
    successContainer = Color(0xFFE7F6EC),
    onSuccessContainer = Color(0xFF14532D),
    warning = Color(0xFF92400E),
    warningContainer = Color(0xFFFEF3C7),
    danger = Color(0xFF991B1B),
    dangerContainer = Color(0xFFFDE8E8),
    info = Color(0xFF1D4ED8),
    infoContainer = Color(0xFFE8F0FF),
    seriesBlue = Color(0xFF2563EB),
    seriesOrange = Color(0xFFEA580C),
    seriesTeal = Color(0xFF0F766E),
    onTime = Color(0xFF16A34A),
    late = Color(0xFFD97706),
    overdue = Color(0xFFB91C1C),
)

val DarkHfColors = HfColors(
    background = Color(0xFF0E1013),
    surface = Color(0xFF181B20),
    surfaceDark = Color(0xFF23262D),
    surfaceDarkInner = Color(0xFF2E323A),
    onSurfaceDark = Color(0xFFFFFFFF),
    mutedOnDark = Color(0xFFB9C0C8),
    onSurface = Color(0xFFECEEF1),
    bodySecondary = Color(0xFFC9CED5),
    muted = Color(0xFFA3ABB5),
    faint = Color(0xFF7D858F),
    outline = Color(0xFF2C3036),
    divider = Color(0xFF24272D),
    handle = Color(0xFF3A3F46),
    subtle = Color(0xFF1F2227),
    fill = Color(0xFF262A30),
    track = Color(0xFF22252B),
    inverse = Color(0xFFECEEF1),
    onInverse = Color(0xFF111418),
    primary = Color(0xFFFF9B66),
    onPrimary = Color(0xFF3A1300),
    primaryPressed = Color(0xFFFFB894),
    primaryContainer = Color(0xFF45200F),
    primaryTint = Color(0xFF2A1A12),
    success = Color(0xFF4ADE80),
    successContainer = Color(0xFF12301D),
    onSuccessContainer = Color(0xFF86EFAC),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A2A0A),
    danger = Color(0xFFF87171),
    dangerContainer = Color(0xFF3B1414),
    info = Color(0xFF93B4FF),
    infoContainer = Color(0xFF172446),
    seriesBlue = Color(0xFF60A5FA),
    seriesOrange = Color(0xFFFB923C),
    seriesTeal = Color(0xFF2DD4BF),
    onTime = Color(0xFF22C55E),
    late = Color(0xFFF59E0B),
    overdue = Color(0xFFEF4444),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(id: Int, weight: Int) = Font(
    id, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.inter, it) })
val SpaceGrotesk = FontFamily(listOf(500, 600, 700).map { variable(R.font.space_grotesk, it) })

/** Type scale from §3. Display styles use Space Grotesk with tabular figures. */
@Immutable
data class HfType(
    val screenTitle: TextStyle,
    val reportTitle: TextStyle,
    val bigFigure: TextStyle,
    val figure: TextStyle,
    val kpiValue: TextStyle,
    val cardTitle: TextStyle,
    val rowTitle: TextStyle,
    val rowSubtitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

private const val TABULAR = "tnum"

val HellFireType = HfType(
    screenTitle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.02).em),
    reportTitle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.02).em),
    bigFigure = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR),
    figure = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, fontFeatureSettings = TABULAR),
    kpiValue = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, fontFeatureSettings = TABULAR),
    cardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    rowTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
    rowSubtitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.5.sp),
    body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.5.sp),
)

private val LocalHfColors = staticCompositionLocalOf { LightHfColors }

object Hf {
    val colors: HfColors @Composable @ReadOnlyComposable get() = LocalHfColors.current
    val type: HfType get() = HellFireType
}

private fun Typography.withInter(): Typography {
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return copy(
        displayLarge = displayLarge.inter(), displayMedium = displayMedium.inter(), displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.copy(fontFamily = SpaceGrotesk), headlineMedium = headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = headlineSmall.copy(fontFamily = SpaceGrotesk),
        titleLarge = titleLarge.inter(), titleMedium = titleMedium.inter(), titleSmall = titleSmall.inter(),
        bodyLarge = bodyLarge.inter(), bodyMedium = bodyMedium.inter(), bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(), labelMedium = labelMedium.inter(), labelSmall = labelSmall.inter(),
    )
}

@Composable
fun HellFireTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val baseContext = androidx.compose.ui.platform.LocalContext.current
    val language = UiLanguage.code
    val localized = androidx.compose.runtime.remember(baseContext, language) { UiLanguage.context(baseContext) }
    val c = if (dark) DarkHfColors else LightHfColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer, onPrimaryContainer = c.primaryPressed,
        secondary = c.inverse, onSecondary = c.onInverse,
        secondaryContainer = c.fill, onSecondaryContainer = c.onSurface,
        background = c.background, onBackground = c.onSurface,
        surface = c.surface, onSurface = c.onSurface,
        surfaceVariant = c.fill, onSurfaceVariant = c.muted,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.fill,
        outline = c.handle, outlineVariant = c.divider,
        error = c.danger, onError = c.surface, errorContainer = c.dangerContainer, onErrorContainer = c.danger,
        scrim = Color(0xFF111418),
    )
    CompositionLocalProvider(LocalHfColors provides c, androidx.compose.ui.platform.LocalContext provides localized) {
        MaterialTheme(colorScheme = scheme, typography = Typography().withInter(), content = content)
    }
}
