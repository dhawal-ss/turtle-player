package app.turtleplayer.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/**
 * Shell's icon set: one consistent line style (1.8 px at 24 px), filled only where Spotify users
 * expect a solid glyph (play, pause, skip). Paths are on a 24×24 grid, matching the design canvas.
 */
object Glyphs {
    private fun vector(name: String, path: String, filled: Boolean, stroke: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Play = vector("play", "M8 5v14l11-7z", filled = true)
    val Pause = vector("pause", "M7 5h4v14H7zM13 5h4v14h-4z", filled = true)
    val Previous = vector("previous", "M6 5h2v14H6zM20 5v14L9 12z", filled = true)
    val Next = vector("next", "M16 5h2v14h-2zM4 5v14l11-7z", filled = true)
    val Home = vector("home", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z", filled = false)
    val HomeFilled = vector("home_filled", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z", filled = true)
    val Search = vector("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-5-5", filled = false, stroke = 2f)
    val Explore = vector("explore", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM15.5 8.5l-2 5-5 2 2-5z", filled = false)
    val Library = vector("library", "M4 4v16M9 4v16M14 5l5 15", filled = false)
    val Infinity = vector("infinity", "M7 9c-2.2 0-4 1.3-4 3s1.8 3 4 3c3.5 0 6.5-6 10-6 2.2 0 4 1.3 4 3s-1.8 3-4 3c-3.5 0-6.5-6-10-6z", filled = false, stroke = 2.2f)
    val Shuffle = vector("shuffle", "M3 6h3c4 0 6 12 10 12h5M3 18h3c1.6 0 2.8-1.9 4-4.2M14 10.2C15.2 7.9 16.4 6 18 6h3M18 3l3 3-3 3M18 15l3 3-3 3", filled = false, stroke = 2f)
    val Add = vector("add", "M12 2.5a9.5 9.5 0 1 0 0 19 9.5 9.5 0 0 0 0-19zM12 8v8M8 12h8", filled = false)
    val Less = vector("less", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM8 12h8", filled = false)
    val Check = vector("check", "M5 12l4 4 10-10", filled = false, stroke = 3f)
    val ChevronDown = vector("chevron_down", "M6 9l6 6 6-6", filled = false, stroke = 2f)
    val More = vector("more", "M5 10.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 0 0 0-3.6zM12 10.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 0 0 0-3.6zM19 10.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 0 0 0-3.6z", filled = true)
    val Queue = vector("queue", "M4 6h12M4 12h12M4 18h8M18 15v6M15 18h6", filled = false)
    val Devices = vector("devices", "M6 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM18 8v8M21 6v12", filled = false)
    val Close = vector("close", "M6 6l12 12M18 6L6 18", filled = false, stroke = 2.4f)
    val Repeat = vector("repeat", "M17 2l4 4-4 4M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 13v1a4 4 0 0 1-4 4H3", filled = false, stroke = 2f)
    val Lyrics = vector("lyrics", "M4 5h16v11H8l-4 4zM8 9h8M8 12h5", filled = false)
    val Fullscreen = vector("fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5", filled = false, stroke = 2f)
    val Volume = vector("volume", "M4 9v6h4l5 4V5L8 9zM16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11", filled = false)
    val Heart = vector("heart", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", filled = true)
    val ArrowRight = vector("arrow_right", "M5 12h14M13 6l6 6-6 6", filled = false, stroke = 2f)
    val NowPlayingView = vector("now_playing_view", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM15 5v14", filled = false)
    val History = vector("history", "M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3.5 4v4.5H8M12 7.5V12l3 2", filled = false)
    val Stats = vector("stats", "M5 20V11M12 20V4M19 20v-6", filled = false, stroke = 2.2f)
    val Together = vector("together", "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 20c0-3.3 2.9-6 6.5-6s6.5 2.7 6.5 6M16 4.6a3.5 3.5 0 0 1 0 6.3M18 14.3c2.1.7 3.5 2.9 3.5 5.7", filled = false)
}

@Composable
fun Glyph(vector: ImageVector, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(vector, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

@Composable
fun PlayGlyph(tint: Color, size: Dp) = Glyph(Glyphs.Play, tint, size)

@Composable
fun PauseGlyph(tint: Color, size: Dp) = Glyph(Glyphs.Pause, tint, size)

@Composable
fun CheckGlyph(tint: Color, size: Dp) = Glyph(Glyphs.Check, tint, size)
