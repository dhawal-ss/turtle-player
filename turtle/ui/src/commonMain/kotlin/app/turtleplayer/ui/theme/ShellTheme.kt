package app.turtleplayer.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import com.metrolist.shared.turtle.TurtleUiSlots
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.inter
import app.turtleplayer.ui.generated.resources.jetbrains_mono
import app.turtleplayer.ui.generated.resources.space_grotesk
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/** Shell color tokens: tonal layers on a near-black ground, one accent (see the design system sheet). */
@Immutable
data class ShellColors(
    val ground: Color = Color(0xFF0B0B0C),
    /** Desktop: the black frame between panels (title bar, gaps, player bar). */
    val frame: Color = Color(0xFF000000),
    /** Desktop: the rounded panels (library, content, now playing). */
    val panel: Color = Color(0xFF121214),
    val surface1: Color = Color(0xFF131315),
    val surface2: Color = Color(0xFF1A1A1D),
    val surface3: Color = Color(0xFF222226),
    val hairline: Color = Color(0xFF2C2C31),
    val hairlineSoft: Color = Color(0xFF1E1E22),
    val ink: Color = Color(0xFFF2F2F2),
    val ink2: Color = Color(0xFFA1A1A6),
    val index: Color = Color(0xFF8E8E93),
    /** Only for "yours" and "now". */
    val accent: Color = Color(0xFFD4F55A),
    val onAccent: Color = Color(0xFF0B0B0C),
    val accentSoft: Color = Color(0x24D4F55A),
    val accentContainer: Color = Color(0xFF2A3310),
    val danger: Color = Color(0xFFFF8A7A),
)

@Immutable
data class ShellType(
    val display: TextStyle,
    val headline: TextStyle,
    val sectionTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    /** Mono index labels: "002 / ON REPEAT". */
    val index: TextStyle,
)

/** Shell motion tokens: Material 3 curves plus one spring-like curve for delight. */
object ShellMotion {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Spring: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    const val TAP_MS = 90
    const val EMPHASIZED_MS = 300
    const val SPRING_MS = 360
    const val EXIT_MS = 150
    const val STAGGER_MS = 40
    const val TINT_MS = 600

    fun <T> emphasized(delay: Int = 0) = tween<T>(EMPHASIZED_MS, delay, Emphasized)
    fun <T> spring(delay: Int = 0) = tween<T>(SPRING_MS, delay, Spring)
    fun <T> exit() = tween<T>(EXIT_MS, 0, Exit)
}

object ShellShapes {
    val Art = RoundedCornerShape(6.dp)
    val Tile = RoundedCornerShape(6.dp)
    val Card = RoundedCornerShape(14.dp)
    val Sheet = RoundedCornerShape(22.dp)
    val Pill = CircleShape
    val Tag = RoundedCornerShape(8.dp)
}

private val LocalShellColors = staticCompositionLocalOf { ShellColors() }
private val LocalShellType = staticCompositionLocalOf<ShellType> { error("ShellTheme not set") }

object Shell {
    val colors: ShellColors @Composable get() = LocalShellColors.current
    val type: ShellType @Composable get() = LocalShellType.current
}

@Composable
private fun variable(resource: FontResource, weight: Int) =
    Font(resource, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@Composable
private fun shellType(): ShellType {
    val grotesk = FontFamily(variable(Res.font.space_grotesk, 500), variable(Res.font.space_grotesk, 700))
    val inter = FontFamily(variable(Res.font.inter, 400), variable(Res.font.inter, 500), variable(Res.font.inter, 600), variable(Res.font.inter, 700))
    val mono = FontFamily(variable(Res.font.jetbrains_mono, 500))
    return ShellType(
        display = TextStyle(fontFamily = grotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 32.sp, letterSpacing = (-0.035).em),
        headline = TextStyle(fontFamily = grotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em),
        sectionTitle = TextStyle(fontFamily = grotesk, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em),
        title = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp),
        body = TextStyle(fontFamily = inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        label = TextStyle(fontFamily = inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
        index = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em),
    )
}

/**
 * Shell's theme. Also provides a matching Material 3 dark scheme so upstream components drawn
 * inside our screens (menus, dialogs, sheets) blend in.
 */
@Composable
fun ShellTheme(colors: ShellColors = ShellThemes.active, content: @Composable () -> Unit) {
    val scheme = remember(colors) { shellMaterialScheme(colors, ShellPlatform.desktop) }
    CompositionLocalProvider(LocalShellColors provides colors, LocalShellType provides shellType()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * The active theme. Today only Shell's default; this is the seam where user themes (Spicetify-style
 * color schemes loaded from files) will plug in, so every screen already draws from swappable tokens.
 */
object ShellThemes {
    var active: ShellColors by mutableStateOf(ShellColors())
}

object ShellPlatform {
    /** True on desktop, where the app shows Shell's three-panel layout (the desktop app registers its library). */
    val desktop: Boolean get() = TurtleUiSlots.desktopLibrary != null
}

/**
 * The background a Shell screen paints. Phones use the ground; on desktop, screens sit inside a
 * rounded panel, so the layout provides the panel color.
 */
val LocalShellGround = compositionLocalOf<Color?> { null }

/**
 * Shell's palette as a full Material 3 dark scheme, applied to the whole app (turtle/HOOKS.md #29) so
 * upstream's screens, menus and dialogs share our look. On desktop, `surfaceContainer` is the black
 * frame (upstream uses it for the title bar, the gaps between panels and menus) and `surface` the panel.
 */
fun shellMaterialScheme(colors: ShellColors, desktop: Boolean): ColorScheme {
    val back = if (desktop) colors.frame else colors.ground
    return darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentContainer,
        onPrimaryContainer = colors.accent,
        inversePrimary = colors.accent,
        secondary = colors.ink2,
        onSecondary = colors.ground,
        secondaryContainer = colors.surface3,
        onSecondaryContainer = colors.ink,
        tertiary = colors.accent,
        onTertiary = colors.onAccent,
        tertiaryContainer = colors.surface3,
        onTertiaryContainer = colors.ink,
        background = back,
        onBackground = colors.ink,
        surface = if (desktop) colors.panel else colors.ground,
        onSurface = colors.ink,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.ink2,
        surfaceTint = Color.Transparent,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.ground,
        error = colors.danger,
        onError = colors.ground,
        errorContainer = Color(0xFF3A1A17),
        onErrorContainer = Color(0xFFFFB4A8),
        outline = colors.hairline,
        outlineVariant = colors.hairlineSoft,
        scrim = Color.Black,
        surfaceBright = colors.surface3,
        surfaceDim = back,
        surfaceContainerLowest = back,
        surfaceContainerLow = colors.surface1,
        surfaceContainer = if (desktop) colors.frame else colors.surface2,
        surfaceContainerHigh = if (desktop) colors.surface2 else colors.surface3,
        surfaceContainerHighest = colors.surface3,
    )
}
