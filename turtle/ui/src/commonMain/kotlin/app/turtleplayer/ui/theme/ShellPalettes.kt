package app.turtleplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.turtleplayer.bridge.TurtleBridge
import com.metrolist.shared.ui.theme.rememberArtworkSeedColor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A named colour scheme, like Spicetify's (Comfy, Catppuccin, Dribbblish). Every Shell screen draws
 * from [ShellColors] tokens, so a palette re-skins the whole app, upstream's screens included.
 * [dynamic] palettes take their accent from the cover art of the song playing (Dribbblish Dynamic).
 */
class ShellPalette(val id: String, val name: String, val colors: ShellColors, val dynamic: Boolean = false)

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
    val shell = ShellPalette("shell", "Shell", ShellColors())
    val all: List<ShellPalette> = listOf(
        shell,
        ShellPalette("dynamic", "Cover colour", ShellColors(), dynamic = true),
        ShellPalette("mono", "Mono", palette(0xFF0A0A0A, 0xFF000000, 0xFF111111, 0xFF121212, 0xFF1A1A1A, 0xFF242424, 0xFF2E2E2E, 0xFFF5F5F5, 0xFFA3A3A3, 0xFF8A8A8A, 0xFFFFFFFF, 0xFF0A0A0A)),
        ShellPalette("catppuccin", "Catppuccin", palette(0xFF1E1E2E, 0xFF11111B, 0xFF181825, 0xFF1E1E2E, 0xFF313244, 0xFF45475A, 0xFF45475A, 0xFFCDD6F4, 0xFFA6ADC8, 0xFF7F849C, 0xFFCBA6F7, 0xFF11111B, 0xFFF38BA8)),
        ShellPalette("rosepine", "Rosé Pine", palette(0xFF191724, 0xFF110F1A, 0xFF1F1D2E, 0xFF1F1D2E, 0xFF26233A, 0xFF403D52, 0xFF403D52, 0xFFE0DEF4, 0xFF908CAA, 0xFF6E6A86, 0xFFEBBCBA, 0xFF191724, 0xFFEB6F92)),
        ShellPalette("nord", "Nord", palette(0xFF2E3440, 0xFF242933, 0xFF3B4252, 0xFF3B4252, 0xFF434C5E, 0xFF4C566A, 0xFF4C566A, 0xFFECEFF4, 0xFFD8DEE9, 0xFF9AA3B5, 0xFF88C0D0, 0xFF2E3440, 0xFFBF616A)),
        ShellPalette("dracula", "Dracula", palette(0xFF282A36, 0xFF191A21, 0xFF21222C, 0xFF21222C, 0xFF343746, 0xFF44475A, 0xFF44475A, 0xFFF8F8F2, 0xFFBDBFD0, 0xFF6272A4, 0xFFFF79C6, 0xFF191A21, 0xFFFF5555)),
        ShellPalette("gruvbox", "Gruvbox", palette(0xFF282828, 0xFF1D2021, 0xFF32302F, 0xFF32302F, 0xFF3C3836, 0xFF504945, 0xFF504945, 0xFFEBDBB2, 0xFFBDAE93, 0xFFA89984, 0xFFFABD2F, 0xFF1D2021, 0xFFFB4934)),
        ShellPalette("tokyonight", "Tokyo Night", palette(0xFF1A1B26, 0xFF16161E, 0xFF1F2335, 0xFF1F2335, 0xFF24283B, 0xFF292E42, 0xFF3B4261, 0xFFC0CAF5, 0xFFA9B1D6, 0xFF565F89, 0xFF7AA2F7, 0xFF16161E, 0xFFF7768E)),
        ShellPalette("everforest", "Everforest", palette(0xFF2D353B, 0xFF232A2E, 0xFF343F44, 0xFF343F44, 0xFF3D484D, 0xFF475258, 0xFF4F5B58, 0xFFD3C6AA, 0xFF9DA9A0, 0xFF859289, 0xFFA7C080, 0xFF232A2E, 0xFFE67E80)),
        ShellPalette("sunset", "Sunset", palette(0xFF140E12, 0xFF0A0709, 0xFF1C1418, 0xFF1C1418, 0xFF261B21, 0xFF33242C, 0xFF3A2A33, 0xFFF6ECEF, 0xFFB9A6AE, 0xFF957F89, 0xFFFF8E5E, 0xFF140E12)),
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
            val accent = seed?.let(::vivid) ?: p.colors.accent
            val base = p.colors
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

/** A cover colour lifted into a readable accent: saturated, light enough for dark surfaces. */
private fun vivid(c: Color): Color {
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
    return Color.hsl(h, s.coerceIn(0.55f, 0.9f), 0.68f)
}
