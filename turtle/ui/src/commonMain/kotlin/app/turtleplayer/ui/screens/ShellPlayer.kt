package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayPauseButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellToast
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_close_player
import app.turtleplayer.ui.generated.resources.shell_devices
import app.turtleplayer.ui.generated.resources.shell_dial_0
import app.turtleplayer.ui.generated.resources.shell_dial_1
import app.turtleplayer.ui.generated.resources.shell_dial_2
import app.turtleplayer.ui.generated.resources.shell_dial_3
import app.turtleplayer.ui.generated.resources.shell_dial_4
import app.turtleplayer.ui.generated.resources.shell_familiar
import app.turtleplayer.ui.generated.resources.shell_fullscreen
import app.turtleplayer.ui.generated.resources.shell_less_like_this
import app.turtleplayer.ui.generated.resources.shell_less_toast
import app.turtleplayer.ui.generated.resources.shell_like
import app.turtleplayer.ui.generated.resources.shell_liked_toast
import app.turtleplayer.ui.generated.resources.shell_lyrics
import app.turtleplayer.ui.generated.resources.shell_more
import app.turtleplayer.ui.generated.resources.shell_new
import app.turtleplayer.ui.generated.resources.shell_next
import app.turtleplayer.ui.generated.resources.shell_pause
import app.turtleplayer.ui.generated.resources.shell_play
import app.turtleplayer.ui.generated.resources.shell_playing_from
import app.turtleplayer.ui.generated.resources.shell_previous
import app.turtleplayer.ui.generated.resources.shell_queue
import app.turtleplayer.ui.generated.resources.shell_repeat
import app.turtleplayer.ui.generated.resources.shell_shuffle
import app.turtleplayer.ui.generated.resources.shell_steer_title
import app.turtleplayer.ui.generated.resources.shell_unlike
import app.turtleplayer.ui.generated.resources.shell_unliked_toast
import app.turtleplayer.ui.generated.resources.shell_up_next
import app.turtleplayer.ui.generated.resources.shell_volume
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.turtle.DesktopPlayerBarSlot
import com.metrolist.shared.turtle.MiniPlayerSlot
import com.metrolist.shared.turtle.PlayerSlot
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.theme.rememberArtworkSeedColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Ground tinted with a share of the artwork's color: every song feels different, chrome stays calm. */
@Composable
private fun artworkTint(url: String?, amount: Float): Color {
    val ground = Shell.colors.ground
    val seed = rememberArtworkSeedColor(url)
    val target = if (seed == null) Shell.colors.surface2 else lerp(ground, seed, amount)
    val tint by animateColorAsState(target, tween(ShellMotion.TINT_MS, easing = ShellMotion.Emphasized))
    return tint
}

/** Playback position that keeps moving smoothly between controller updates while playing. */
@Composable
private fun rememberPositionMs(controller: PlaybackController, playing: Boolean, reported: Long): Long {
    var position by remember { mutableLongStateOf(reported) }
    LaunchedEffect(reported) { position = reported }
    LaunchedEffect(playing) {
        while (playing) {
            position = controller.currentPositionMs()
            delay(250)
        }
    }
    return position
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
private fun GlyphButton(glyph: ImageVector, label: String, onClick: () -> Unit, tint: Color, size: Dp = 44.dp, iconSize: Dp = 22.dp, dot: Boolean = false) {
    Column(Modifier.size(size).shellPressable(onClick, label = label), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(glyph, tint, iconSize)
        if (dot) Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(tint))
    }
}

/** Thin bar with a knob: follows your finger with no delay, seeks on release or tap. */
@Composable
private fun ShellSeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, track: Color, fill: Color) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(modifier.height(20.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(durationMs) { detectTapGestures { o -> onSeek(((o.x / widthPx).coerceIn(0f, 1f) * durationMs).toLong()) } }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / widthPx).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { onSeek((it * durationMs).toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = (change.position.x / widthPx).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(track))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(fill))
            val knob by animateFloatAsState(if (dragging != null) 1.4f else 1f, ShellMotion.spring())
            Box(Modifier.offset { IntOffset((fraction * widthPx - 6.dp.toPx()).roundToInt(), 0) }.size(12.dp).scale(knob).clip(CircleShape).background(fill))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Mini player (Android)
// ---------------------------------------------------------------------------------------------

/** Spotify-familiar mini player, tinted by the artwork; swipe sideways to skip. */
@Composable
fun ShellMiniPlayer(slot: MiniPlayerSlot) = ShellTheme {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.state.collectAsState()
    val song = state.currentSong ?: return@ShellTheme
    val position = rememberPositionMs(controller, state.isPlaying, state.positionMs)
    val tint = artworkTint(song.displayThumbnailUrl, 0.45f)
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        slot.modifier.fillMaxWidth().padding(horizontal = 8.dp).height(60.dp).clip(RoundedCornerShape(10.dp)).background(tint)
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = drag.value
                        if (dx < -120f) controller.skipToNext() else if (dx > 120f) controller.skipToPrevious()
                        scope.launch { drag.animateTo(0f, ShellMotion.spring()) }
                    },
                ) { _, amount -> scope.launch { drag.snapTo(drag.value + amount) } }
            }
            .shellPressable(slot.onExpand),
    ) {
        Row(Modifier.fillMaxSize().offset { IntOffset((drag.value * 0.4f).roundToInt(), 0) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(song, transitionSpec = { (fadeIn(ShellMotion.emphasized()) + scaleIn(ShellMotion.emphasized(), 0.9f)) togetherWith fadeOut(ShellMotion.exit()) }) { s ->
                ShellArt(s.displayThumbnailUrl, Modifier.size(42.dp), shape = RoundedCornerShape(5.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = Shell.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val liked = SongLikeState.isLiked(song.id)
            LikeButton(song, liked, size = 40.dp)
            PlayPauseButton(state.isPlaying, controller::togglePlayPause, 40.dp, Color.Transparent, c.ink, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
        }
        val progress = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp).fillMaxWidth().height(2.dp).background(c.ink.copy(alpha = 0.25f)))
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp).fillMaxWidth(progress).height(2.dp).background(c.ink))
    }
}

/** Spotify's "+" that becomes a lime check, with a spring pop. */
@Composable
private fun LikeButton(song: SongItem, liked: Boolean, size: Dp, onToggled: (Boolean) -> Unit = {}) {
    val c = Shell.colors
    var pulse by remember { mutableStateOf(0) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            scale.animateTo(0.82f, tween(ShellMotion.TAP_MS, easing = ShellMotion.Emphasized))
            scale.animateTo(1f, ShellMotion.spring())
        }
    }
    Box(
        Modifier.size(size).scale(scale.value).shellPressable({ pulse++; val now = SongLikeState.toggleLike(song); onToggled(now) }, label = stringResource(if (liked) Res.string.shell_unlike else Res.string.shell_like)),
        contentAlignment = Alignment.Center,
    ) {
        if (liked) {
            Box(Modifier.size(size * 0.55f).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { Glyph(Glyphs.Check, c.onAccent, size * 0.32f) }
        } else {
            Glyph(Glyphs.Add, c.ink, size * 0.6f)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Now Playing (Android and iOS)
// ---------------------------------------------------------------------------------------------

/**
 * Spotify-familiar Now Playing in Shell's look. Lyrics, queue, devices and the overflow menu hand
 * over to upstream's full player (TurtleUiSlots.showUpstreamPlayer), so none of its features are lost;
 * Shell takes back over when the sheet collapses.
 */
@Composable
fun ShellNowPlaying(slot: PlayerSlot) = ShellTheme {
    SideEffect { if (slot.collapsed) TurtleUiSlots.showUpstreamPlayer = false }
    if (slot.collapsed) return@ShellTheme
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.state.collectAsState()
    val song = state.currentSong ?: return@ShellTheme
    val scope = rememberCoroutineScope()
    val position = rememberPositionMs(controller, state.isPlaying, state.positionMs)
    val tint = artworkTint(song.displayThumbnailUrl, 0.32f)
    val liked = SongLikeState.isLiked(song.id)
    var toast by remember { mutableStateOf<String?>(null) }
    val likedText = stringResource(Res.string.shell_liked_toast)
    val unlikedText = stringResource(Res.string.shell_unliked_toast)
    val lessText = stringResource(Res.string.shell_less_toast)
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }
    val handOver = { TurtleUiSlots.showUpstreamPlayer = true }

    Box(Modifier.fillMaxSize().background(tint)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.ChevronDown, stringResource(Res.string.shell_close_player), slot.onClose, c.ink, iconSize = 24.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    IndexLabel(stringResource(Res.string.shell_playing_from), color = c.ink.copy(alpha = 0.75f))
                    Text(state.queueTitle.orEmpty(), style = Shell.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                GlyphButton(Glyphs.More, stringResource(Res.string.shell_more), handOver, c.ink)
            }
            Spacer(Modifier.weight(0.6f))
            AnimatedContent(
                song,
                transitionSpec = { (slideInHorizontally(ShellMotion.emphasized()) { it / 8 } + fadeIn(ShellMotion.emphasized())) togetherWith fadeOut(ShellMotion.exit()) },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            ) { s ->
                val artScale by animateFloatAsState(if (state.isPlaying) 1f else 0.92f, ShellMotion.emphasized())
                ShellArt(s.displayThumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f).scale(artScale), shape = RoundedCornerShape(8.dp))
            }
            Spacer(Modifier.weight(0.6f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.92f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artists.joinToString(", ") { it.name }, style = Shell.type.body, color = c.ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.shellPressable({ song.artists.firstOrNull()?.id?.let(slot.onNavigateToArtist) }),
                    )
                }
                GlyphButton(Glyphs.Less, stringResource(Res.string.shell_less_like_this), {
                    toast = lessText
                    scope.launch { TurtleBridge.lessLikeThis(song); delay(450); controller.skipToNext() }
                }, c.ink.copy(alpha = 0.85f), iconSize = 24.dp)
                LikeButton(song, liked, size = 48.dp) { now -> toast = if (now) likedText else unlikedText }
            }
            Spacer(Modifier.height(14.dp))
            ShellSeekBar(position, state.durationMs, controller::seekTo, Modifier.fillMaxWidth(), track = c.ink.copy(alpha = 0.25f), fill = c.ink)
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(position), style = Shell.type.index, color = c.ink.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text(formatTime(state.durationMs), style = Shell.type.index, color = c.ink.copy(alpha = 0.75f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_shuffle), controller::toggleShuffle, if (state.isShuffled) c.accent else c.ink, size = 52.dp, iconSize = 24.dp, dot = state.isShuffled)
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, size = 60.dp, iconSize = 32.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 72.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, size = 60.dp, iconSize = 32.dp)
                GlyphButton(Glyphs.Repeat, stringResource(Res.string.shell_repeat), controller::toggleRepeat, if (state.repeatMode != RepeatMode.OFF) c.accent else c.ink, size = 52.dp, iconSize = 24.dp, dot = state.repeatMode != RepeatMode.OFF)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Devices, stringResource(Res.string.shell_devices), handOver, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyphs.Lyrics, stringResource(Res.string.shell_lyrics), handOver, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
                GlyphButton(Glyphs.Queue, stringResource(Res.string.shell_queue), handOver, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
            }
            SteerCard(tint)
            Spacer(Modifier.height(12.dp))
        }
        ShellToast(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** The Discovery dial: five stops from familiar to all new, snapping with a spring. */
@Composable
private fun SteerCard(tint: Color) {
    val c = Shell.colors
    val scope = rememberCoroutineScope()
    var stop by rememberSaveable { mutableStateOf(1) }
    val labels = listOf(Res.string.shell_dial_0, Res.string.shell_dial_1, Res.string.shell_dial_2, Res.string.shell_dial_3, Res.string.shell_dial_4)
    val fill by animateFloatAsState((stop + 1) / 5f, ShellMotion.spring())
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(ShellShapes.Card).background(lerp(tint, Color.White, 0.06f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.shell_steer_title), style = Shell.type.label.copy(fontWeight = FontWeight.Bold), color = c.ink, modifier = Modifier.weight(1f))
            AnimatedContent(stop, transitionSpec = { fadeIn(ShellMotion.emphasized()) togetherWith fadeOut(ShellMotion.exit()) }) { s -> IndexLabel(stringResource(labels[s]), color = c.accent) }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(c.accent))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(5) { i ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(Color.Transparent)
                            .shellPressable({ stop = i; scope.launch { TurtleBridge.setDiscoveryDial(i / 4.0) } }, label = stringResource(labels[i])),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(Res.string.shell_familiar), style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f))
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.shell_new), style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Desktop player bar
// ---------------------------------------------------------------------------------------------

/** Spotify's desktop player bar layout in Shell's look. */
@Composable
fun ShellDesktopPlayerBar(slot: DesktopPlayerBarSlot) = ShellTheme {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.state.collectAsState()
    val song = state.currentSong ?: slot.restoredSong
    val position = rememberPositionMs(controller, state.isPlaying, state.positionMs)
    var volume by remember { mutableFloatStateOf(slot.volume) }
    Row(Modifier.fillMaxWidth().height(88.dp).background(c.ground).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                ShellArt(song.displayThumbnailUrl, Modifier.size(56.dp).shellPressable(slot.onExpand), shape = RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(song.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                LikeButton(song, SongLikeState.isLiked(song.id), size = 36.dp)
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_shuffle), controller::toggleShuffle, if (state.isShuffled) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = state.isShuffled)
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, size = 36.dp, iconSize = 20.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 40.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, size = 36.dp, iconSize = 20.dp)
                GlyphButton(Glyphs.Repeat, stringResource(Res.string.shell_repeat), controller::toggleRepeat, if (state.repeatMode != RepeatMode.OFF) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = state.repeatMode != RepeatMode.OFF)
            }
            Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position), style = Shell.type.index, color = c.ink2)
                Spacer(Modifier.width(8.dp))
                ShellSeekBar(position, state.durationMs, controller::seekTo, Modifier.weight(1f), track = c.hairline, fill = c.ink)
                Spacer(Modifier.width(8.dp))
                Text(formatTime(state.durationMs), style = Shell.type.index, color = c.ink2)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.Lyrics, stringResource(Res.string.shell_lyrics), slot.onToggleLyrics, c.ink2, size = 36.dp, iconSize = 18.dp)
            GlyphButton(Glyphs.Queue, stringResource(Res.string.shell_queue), slot.onToggleQueue, c.ink2, size = 36.dp, iconSize = 18.dp)
            GlyphButton(Glyphs.Devices, stringResource(Res.string.shell_devices), slot.onToggleDevices, c.ink2, size = 36.dp, iconSize = 18.dp)
            Glyph(Glyphs.Volume, c.ink2, 18.dp, contentDescription = stringResource(Res.string.shell_volume))
            Spacer(Modifier.width(6.dp))
            ShellSeekBar((volume * 1000).toLong(), 1000, { v -> volume = v / 1000f; slot.onVolumeChange(volume) }, Modifier.width(96.dp), track = c.hairline, fill = c.ink)
            GlyphButton(Glyphs.Fullscreen, stringResource(Res.string.shell_fullscreen), slot.onToggleFullscreen, c.ink2, size = 36.dp, iconSize = 18.dp)
        }
    }
}
