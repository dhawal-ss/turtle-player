package app.turtleplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_palette_shell
import app.turtleplayer.ui.generated.resources.shell_palette_dynamic
import app.turtleplayer.ui.generated.resources.shell_palette_mono
import app.turtleplayer.ui.generated.resources.shell_palette_catppuccin
import app.turtleplayer.ui.generated.resources.shell_palette_rosepine
import app.turtleplayer.ui.generated.resources.shell_palette_nord
import app.turtleplayer.ui.generated.resources.shell_palette_dracula
import app.turtleplayer.ui.generated.resources.shell_palette_gruvbox
import app.turtleplayer.ui.generated.resources.shell_palette_tokyonight
import app.turtleplayer.ui.generated.resources.shell_palette_everforest
import app.turtleplayer.ui.generated.resources.shell_palette_sunset
import com.metrolist.shared.ui.theme.rememberArtworkSeedColor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A named colour scheme, like Spicetify's (Comfy, Catppuccin, Dribbblish). Every Shell screen draws
 * from [ShellColors] tokens, so a palette re-skins the whole app, upstream's screens included.
 * [dynamic] palettes take their accent from the cover art of the song playing (Dribbblish Dynamic).
 */
class ShellPalette(val id: String, val name: StringResource, val colors: ShellColors, val dynamic: Boolean = false)

private fun palette(
    ground: Long, frame: Long, panel: Long, s1: Long, s2: Long, s3: Long, hairline: Long,
    ink: Long, ink2: Long, index: Long, accent: Long, onAccent: Long, danger: Long = 0xFFFF8A7A,
): ShellColors {
    val g = Color(ground)
    val a = Color(accent)
    return ShellColors(
        ground = g, frame = Color(frame), panel = Color(panel),
        surface1 = Color(s1), surface2 = Color(s2), surface3 = Color(s3),
        hairline = Color(hairline), hairlineSoft = lerp(Color(hairline), g, 0.5f),
        ink = Color(ink), ink2 = Color(ink2), index = Color(index),
        accent = a, onAccent = Color(onAccent), accentSoft = a.copy(alpha = 0.14f),
        accentContainer = lerp(g, a, 0.18f), danger = Color(danger),
    )
}

object ShellPalettes {
    val shell = ShellPalette("shell", Res.string.shell_palette_shell, ShellColors())

    // ink2 and index are lighter than the originals where needed: secondary and label text must
    // reach READABLE_CONTRAST on ground, panel, surface1 and surface2, and ink2 on surface3 too
    // (hovered rows, search fields). Index labels are never drawn on surface3.
    val all: List<ShellPalette> = listOf(
        shell,
        ShellPalette("dynamic", Res.string.shell_palette_dynamic, ShellColors(), dynamic = true),
        ShellPalette("mono", Res.string.shell_palette_mono, palette(0xFF0A0A0A, 0xFF000000, 0xFF111111, 0xFF121212, 0xFF1A1A1A, 0xFF242424, 0xFF2E2E2E, 0xFFF5F5F5, 0xFFA3A3A3, 0xFF8A8A8A, 0xFFFFFFFF, 0xFF0A0A0A)),
        ShellPalette("catppuccin", Res.string.shell_palette_catppuccin, palette(0xFF1E1E2E, 0xFF11111B, 0xFF181825, 0xFF1E1E2E, 0xFF313244, 0xFF45475A, 0xFF45475A, 0xFFCDD6F4, 0xFFAFB6CE, 0xFF969AAD, 0xFFCBA6F7, 0xFF11111B, 0xFFF38BA8)),
        ShellPalette("rosepine", Res.string.shell_palette_rosepine, palette(0xFF191724, 0xFF110F1A, 0xFF1F1D2E, 0xFF1F1D2E, 0xFF26233A, 0xFF403D52, 0xFF403D52, 0xFFE0DEF4, 0xFFABA8BE, 0xFF8D89A2, 0xFFEBBCBA, 0xFF191724, 0xFFEB6F92)),
        ShellPalette("nord", Res.string.shell_palette_nord, palette(0xFF2E3440, 0xFF242933, 0xFF3B4252, 0xFF3B4252, 0xFF434C5E, 0xFF4C566A, 0xFF4C566A, 0xFFECEFF4, 0xFFD8DEE9, 0xFFB5BCC9, 0xFF88C0D0, 0xFF2E3440, 0xFFBF616A)),
        ShellPalette("dracula", Res.string.shell_palette_dracula, palette(0xFF282A36, 0xFF191A21, 0xFF21222C, 0xFF21222C, 0xFF343746, 0xFF44475A, 0xFF44475A, 0xFFF8F8F2, 0xFFBDBFD0, 0xFF95A0C2, 0xFFFF79C6, 0xFF191A21, 0xFFFF5555)),
        ShellPalette("gruvbox", Res.string.shell_palette_gruvbox, palette(0xFF282828, 0xFF1D2021, 0xFF32302F, 0xFF32302F, 0xFF3C3836, 0xFF504945, 0xFF504945, 0xFFEBDBB2, 0xFFC6B8A1, 0xFFAEA08C, 0xFFFABD2F, 0xFF1D2021, 0xFFFB4934)),
        ShellPalette("tokyonight", Res.string.shell_palette_tokyonight, palette(0xFF1A1B26, 0xFF16161E, 0xFF1F2335, 0xFF1F2335, 0xFF24283B, 0xFF292E42, 0xFF3B4261, 0xFFC0CAF5, 0xFFA9B1D6, 0xFF868EB3, 0xFF7AA2F7, 0xFF16161E, 0xFFF7768E)),
        ShellPalette("everforest", Res.string.shell_palette_everforest, palette(0xFF2D353B, 0xFF232A2E, 0xFF343F44, 0xFF343F44, 0xFF3D484D, 0xFF475258, 0xFF4F5B58, 0xFFD3C6AA, 0xFFBDC5BE, 0xFFADB6B0, 0xFFA7C080, 0xFF232A2E, 0xFFE67E80)),
        ShellPalette("sunset", Res.string.shell_palette_sunset, palette(0xFF140E12, 0xFF0A0709, 0xFF1C1418, 0xFF1C1418, 0xFF261B21, 0xFF33242C, 0xFF3A2A33, 0xFFF6ECEF, 0xFFB9A6AE, 0xFF95808A, 0xFFFF8E5E, 0xFF140E12)),
    )

    private val byId = all.associateBy { it.id }

    /** The palette the listener chose (Shell's own until restored). */
    var selected: ShellPalette by mutableStateOf(shell)
        private set

    private val scope = MainScope()

    /** Applies the saved palette; called once at startup. */
    fun restore() {
        scope.launch {
            val id = runCatching { TurtleBridge.uiPref(PREF) }.getOrNull() ?: return@launch
            byId[id]?.let(::apply)
        }
    }

    fun choose(p: ShellPalette) {
        apply(p)
        scope.launch { runCatching { TurtleBridge.setUiPref(PREF, p.id) } }
    }

    private fun apply(p: ShellPalette) {
        selected = p
        ShellThemes.active = p.colors
    }

    /** With a dynamic palette, makes the cover's colour the accent. Call from surfaces that know the song. */
    @Composable
    fun FollowCover(artUrl: String?) {
        val p = selected
        if (!p.dynamic) return
        val seed = rememberArtworkSeedColor(artUrl)
        LaunchedEffect(seed, p) {
            val base = p.colors
            val accent = seed?.let { vivid(it, base.surface3) } ?: p.colors.accent
            if (ShellThemes.active.accent != accent) {
                ShellThemes.active = base.copy(
                    accent = accent,
                    onAccent = if (accent.luminance() > 0.45f) Color(0xFF0B0B0C) else Color(0xFFF7F7F7),
                    accentSoft = accent.copy(alpha = 0.14f),
                    accentContainer = lerp(base.ground, accent, 0.18f),
                )
            }
        }
    }

    private const val PREF = "palette"
}

/** A cover colour lifted into a readable accent: saturated, light enough to read as text [on] a surface. */
private fun vivid(c: Color, on: Color): Color {
    val r = c.red
    val g = c.green
    val b = c.blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 0.04f) return Color(0xFFD4F55A) // A grey cover: keep Shell's lime.
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r -> ((g - b) / d).mod(6f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    val sat = s.coerceIn(0.55f, 0.9f)
    // Blues and violets are dark at the same lightness, so they need a little more.
    var light = 0.68f
    while (light < 0.9f && contrast(Color.hsl(h, sat, light), on) < READABLE_CONTRAST) light += 0.01f
    return Color.hsl(h, sat, light)
}

/** WCAG AA for normal-size text: what secondary and label text must reach on its background. */
internal const val READABLE_CONTRAST = 4.5f

/** WCAG contrast ratio of [text], blended by its alpha, on an opaque [background]. */
internal fun contrast(text: Color, background: Color): Float {
    val t = text.compositeOver(background).luminance() + 0.05f
    val b = background.luminance() + 0.05f
    return max(t, b) / min(t, b)
}

/**
 * [background] darkened just enough for each of [texts] (light, as in every Shell palette) to reach
 * [READABLE_CONTRAST], so a bright cover tint never washes out the labels drawn on it.
 */
internal fun readableTint(background: Color, texts: List<Color>): Color {
    fun reads(bg: Color) = texts.all { contrast(it, bg) >= READABLE_CONTRAST }
    if (reads(background)) return background
    // The least darkening that reads; black always does for light text.
    var lo = 0f
    var hi = 1f
    repeat(10) {
        val mid = (lo + hi) / 2f
        if (reads(lerp(background, Color.Black, mid))) hi = mid else lo = mid
    }
    return lerp(background, Color.Black, hi)
}
