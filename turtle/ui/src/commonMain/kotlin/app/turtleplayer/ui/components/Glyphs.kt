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
    val ArrowLeft = vector("arrow_left", "M19 12H5M11 6l-6 6 6 6", filled = false, stroke = 2f)
    val ThumbUp = vector("thumb_up", "M7 10v11H4V10zM7 10l4-8a2.5 2.5 0 0 1 2.5 2.5V9h5.5a2 2 0 0 1 2 2.3l-1.4 8A2 2 0 0 1 17.6 21H7", filled = false)
    val ThumbDown = vector("thumb_down", "M7 14V3H4v11zM7 14l4 8a2.5 2.5 0 0 0 2.5-2.5V15h5.5a2 2 0 0 0 2-2.3l-1.4-8A2 2 0 0 0 17.6 3H7", filled = false)
    val PlaylistAdd = vector("playlist_add", "M4 6h11M4 12h11M4 18h7M18 13v8M14 17h8", filled = false, stroke = 2f)
    val Palette = vector("palette", "M12 3a9 9 0 0 0 0 18c1.1 0 1.7-.8 1.7-1.6 0-.5-.2-.8-.4-1.1-.2-.3-.4-.6-.4-1 0-.9.7-1.6 1.6-1.6H16a5 5 0 0 0 5-5c0-4.1-4-7.7-9-7.7zM7.5 12.5a1.2 1.2 0 1 0 0-.1zM9.5 8a1.2 1.2 0 1 0 0-.1zM14.5 8a1.2 1.2 0 1 0 0-.1zM17 11.5a1.2 1.2 0 1 0 0-.1z", filled = false)
    val Info = vector("info", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5.5M12 7.6v.2", filled = false, stroke = 2f)
    val Lock = vector("lock", "M7.5 11V8a4.5 4.5 0 0 1 9 0v3M5.5 11h13a.5.5 0 0 1 .5.5v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a.5.5 0 0 1 .5-.5z", filled = false)
    val Turntable = vector("turntable", "M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM10.5 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM10.5 11.2a.8.8 0 1 0 0 1.6.8.8 0 0 0 0-1.6zM18 6.5v7.5l-2.5 2.5", filled = false)
    val FilterSweep = vector("filter_sweep", "M3 8h8c3 0 4 2.5 5 5.5s2.5 4.5 5 4.5", filled = false, stroke = 2.2f)
    val BassWave = vector("bass_wave", "M2 12c1.7-6 3.3-6 5 0s3.3 6 5 0 3.3-6 5 0 3.3 6 5 0", filled = false, stroke = 2.2f)
    val BassCut = vector("bass_cut", "M2 12c1.7-6 3.3-6 5 0s3.3 6 5 0 3.3-6 5 0 3.3 6 5 0M4 20L20 4", filled = false, stroke = 2f)
    val Hype = vector("hype", "M6 13l6-6 6 6M6 19l6-6 6 6", filled = false, stroke = 2.2f)
    val Chill = vector("chill", "M6 5l6 6 6-6M6 11l6 6 6-6", filled = false, stroke = 2.2f)
    val Crossfader = vector("crossfader", "M3 12h18M10 7h4v10h-4z", filled = false, stroke = 2f)
    val TempoUp = vector("tempo_up", "M9 3h6l3.5 18h-13zM12 17l4.5-8M19 3v6M16 6h6", filled = false)
    val TempoDown = vector("tempo_down", "M9 3h6l3.5 18h-13zM12 17l4.5-8M16 6h6", filled = false)
    val Power = vector("power", "M12 3v8M7 6.5a7 7 0 1 0 10 0", filled = false, stroke = 2f)
    val ChevronRight =vector("chevron_right", "M9 6l6 6-6 6", filled = false, stroke = 2f)
    val ChevronUpDown = vector("chevron_up_down", "M8 9l4-4 4 4M8 15l4 4 4-4", filled = false, stroke = 2f)
    val SkipBack = vector("skip_back", "M4 12a8 8 0 1 0 8-8 8.6 8.6 0 0 0-6 2.5L4 8.5M4 4v4.5h4.5", filled = false)
    val SkipForward = vector("skip_forward", "M20 12a8 8 0 1 1-8-8 8.6 8.6 0 0 1 6 2.5L20 8.5M20 4v4.5h-4.5", filled = false)
    val Book = vector("book", "M5 5.5A2.5 2.5 0 0 1 7.5 3H19v14H7.5A2.5 2.5 0 0 0 5 19.5zM5 19.5A2.5 2.5 0 0 0 7.5 22H19v-5M9 7h6", filled = false)
    val Podcast = vector("podcast", "M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM6 10.5a6 6 0 0 0 12 0M12 16.5V21M9 21h6", filled = false)
    val NowPlayingView = vector("now_playing_view", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM15 5v14", filled = false)
    val History = vector("history", "M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3.5 4v4.5H8M12 7.5V12l3 2", filled = false)
    val Stats = vector("stats", "M5 20V11M12 20V4M19 20v-6", filled = false, stroke = 2.2f)
    val Artist = vector("artist", "M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4.5 20.5c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5", filled = false)
    val Album = vector("album", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", filled = false)
    val Radio = vector("radio", "M12 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zM8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8", filled = false)
    val Download = vector("download", "M12 4v11M7 10l5 5 5-5M5 20h14", filled = false, stroke = 2f)
    val Share = vector("share", "M12 15V4M8 8l4-4 4 4M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6", filled = false)
    val Sleep = vector("sleep", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z", filled = false)
    val Speaker = vector("speaker", "M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM12 11a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 6.5v.5", filled = false)
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
