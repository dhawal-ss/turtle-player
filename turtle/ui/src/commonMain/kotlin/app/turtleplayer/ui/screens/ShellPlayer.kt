package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayPauseButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellToast
import app.turtleplayer.ui.components.rememberHover
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
import app.turtleplayer.ui.generated.resources.shell_infinity_title
import app.turtleplayer.ui.generated.resources.shell_less_like_this
import app.turtleplayer.ui.generated.resources.shell_less_toast
import app.turtleplayer.ui.generated.resources.shell_like
import app.turtleplayer.ui.generated.resources.shell_liked_toast
import app.turtleplayer.ui.generated.resources.shell_lyrics
import app.turtleplayer.ui.generated.resources.shell_more
import app.turtleplayer.ui.generated.resources.shell_new
import app.turtleplayer.ui.generated.resources.shell_next
import app.turtleplayer.ui.generated.resources.shell_now_playing_label
import app.turtleplayer.ui.generated.resources.shell_now_playing_view
import app.turtleplayer.ui.generated.resources.shell_pause
import app.turtleplayer.ui.generated.resources.shell_play
import app.turtleplayer.ui.generated.resources.shell_playing_from
import app.turtleplayer.ui.generated.resources.shell_previous
import app.turtleplayer.ui.generated.resources.shell_queue
import app.turtleplayer.ui.generated.resources.shell_repeat
import app.turtleplayer.ui.generated.resources.shell_seek
import app.turtleplayer.ui.generated.resources.shell_shuffle
import app.turtleplayer.ui.generated.resources.shell_steer_title
import app.turtleplayer.ui.generated.resources.shell_unlike
import app.turtleplayer.ui.generated.resources.shell_unliked_toast
import app.turtleplayer.ui.generated.resources.shell_volume
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.turtle.DesktopPlayerBarSlot
import com.metrolist.shared.turtle.MiniPlayerSlot
import com.metrolist.shared.turtle.PlayerSlot
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.theme.rememberArtworkSeedColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Ground tinted with a share of the artwork's color: every song feels different, chrome stays calm. */
@Composable
internal fun artworkTint(url: String?, amount: Float, base: Color = Shell.colors.ground): Color {
    val ground = base
    val seed = rememberArtworkSeedColor(url)
    val target = if (seed == null) Shell.colors.surface2 else lerp(ground, seed, amount)
    val tint by animateColorAsState(target, tween(ShellMotion.TINT_MS, easing = ShellMotion.Emphasized))
    return tint
}

internal fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** The song's length, falling back to its metadata while the player has not reported one yet. */
private fun durationOf(reportedMs: Long, song: SongItem?): Long =
    reportedMs.takeIf { it > 0 } ?: song?.duration?.toLong()?.takeIf { it > 0 }?.times(1000L) ?: 0L

@Composable
internal fun GlyphButton(glyph: ImageVector, label: String, onClick: () -> Unit, tint: Color, size: Dp = 48.dp, iconSize: Dp = 22.dp, dot: Boolean = false) {
    Column(Modifier.size(size).shellPressable(onClick, label = label, focusShape = CircleShape), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(glyph, tint, iconSize)
        if (dot) Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(tint))
    }
}

/**
 * Thin bar with a knob: follows your finger with no delay, seeks on release or tap ([live] seeks
 * while dragging, for volume). The bar is drawn, not laid out, so the position tick only redraws it.
 * On desktop the knob appears and the fill turns lime on hover (H-Motion 08).
 */
@Composable
private fun ShellSeekBar(
    position: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    track: Color,
    fill: Color,
    live: Boolean = false,
) {
    val c = Shell.colors
    val seek by rememberUpdatedState(onSeek)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val (source, hovered) = rememberHover()
    val active = hovered || dragging != null
    val desktop = ShellPlatform.desktop
    val fillColor by animateColorAsState(if (active && desktop) c.accent else fill, ShellMotion.emphasized())
    val knob by animateFloatAsState(
        when {
            dragging != null -> 1.3f
            active || !desktop -> 1f
            else -> 0f
        },
        tween(ShellMotion.EXIT_MS, easing = ShellMotion.Emphasized),
    )
    val fraction = { dragging ?: if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f }
    Box(
        modifier.height(20.dp).hoverable(source)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { v -> seek((v.coerceIn(0f, 1f) * durationMs).toLong()); true }
            }
            .pointerInput(durationMs) { detectTapGestures { o -> seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) } }
            .pointerInput(durationMs, live) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { seek((it * durationMs).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    dragging = f
                    if (live) seek((f * durationMs).toLong())
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val knobColor = c.ink
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val h = 4.dp.toPx()
            val y = size.height / 2
            val start = h / 2
            val end = size.width - h / 2
            val x = start + (end - start) * fraction()
            drawLine(track, Offset(start, y), Offset(end, y), strokeWidth = h, cap = StrokeCap.Round)
            if (x > start) drawLine(fillColor, Offset(start, y), Offset(x, y), strokeWidth = h, cap = StrokeCap.Round)
            if (knob > 0f) drawCircle(knobColor, radius = 6.dp.toPx() * knob, center = Offset(x, y))
        }
    }
}

/** A time label that reads the live position itself, so only this text recomposes each tick. */
@Composable
private fun TimeText(ms: () -> Long, color: Color) {
    Text(formatTime(ms()), style = Shell.type.index, color = color)
}

/** Songs slide in from the side they came from: forward from the right, back from the left (H-Motion 04). */
private fun skipTransition(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(ShellMotion.emphasized()) { dir * it / 8 } + scaleIn(ShellMotion.emphasized(), 0.96f) + fadeIn(ShellMotion.emphasized())) togetherWith
        (slideOutHorizontally(ShellMotion.exit()) { -dir * it / 8 } + fadeOut(ShellMotion.exit()))
}

private val quickFade = fadeIn(tween(ShellMotion.EXIT_MS)) togetherWith fadeOut(tween(ShellMotion.EXIT_MS))

// ---------------------------------------------------------------------------------------------
// Mini player (Android)
// ---------------------------------------------------------------------------------------------

/** Spotify-familiar mini player, tinted by the artwork; swipe sideways to skip. */
@Composable
fun ShellMiniPlayer(slot: MiniPlayerSlot) = ShellTheme {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.rememberCalmState()
    val song = state.currentSong ?: return@ShellTheme
    val live = rememberLivePosition(controller, state.isPlaying, song.id)
    val duration = durationOf(state.durationMs, song)
    val tint = artworkTint(song.displayThumbnailUrl, 0.45f)
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val track = c.ink.copy(alpha = 0.25f)
    Box(
        slot.modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp).clip(RoundedCornerShape(8.dp)).background(tint)
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = drag.value
                        if (dx < -120f) controller.skipToNext() else if (dx > 120f) controller.skipToPrevious()
                        scope.launch { drag.animateTo(0f, ShellMotion.spring()) }
                    },
                ) { _, amount -> scope.launch { drag.snapTo(drag.value + amount) } }
            }
            .shellPressable(slot.onExpand)
            // The progress line is drawn from the live position: no recomposition per tick.
            .drawBehind {
                val inset = 8.dp.toPx()
                val h = 2.dp.toPx()
                val y = size.height - h / 2
                val f = if (duration > 0) (live.ms.toFloat() / duration).coerceIn(0f, 1f) else 0f
                drawLine(track, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = h)
                drawLine(c.ink, Offset(inset, y), Offset(inset + (size.width - 2 * inset) * f, y), strokeWidth = h)
            },
    ) {
        Row(Modifier.fillMaxSize().offset { IntOffset((drag.value * 0.4f).roundToInt(), 0) }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(song, transitionSpec = { (fadeIn(ShellMotion.emphasized()) + scaleIn(ShellMotion.emphasized(), 0.9f)) togetherWith fadeOut(ShellMotion.exit()) }, contentKey = { it.id }) { s ->
                ShellArt(s.displayThumbnailUrl, Modifier.size(40.dp), shape = RoundedCornerShape(4.dp))
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(song, transitionSpec = { quickFade }, contentKey = { it.id }, modifier = Modifier.weight(1f)) { s ->
                Column {
                    Text(s.title, style = Shell.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LikeButton(song, SongLikeState.isLiked(song.id), size = 48.dp, visual = 40.dp)
            PlayPauseButton(state.isPlaying, controller::togglePlayPause, 48.dp, Color.Transparent, c.ink, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
        }
    }
}

/**
 * Spotify's "+" that becomes a lime check: a press dip, a spring past full size and a lime halo
 * that rings out (H-Motion 02). [size] is the touch target, [visual] the drawn button.
 */
@Composable
internal fun LikeButton(song: SongItem, liked: Boolean, size: Dp, visual: Dp = size, onToggled: (Boolean) -> Unit = {}) {
    val c = Shell.colors
    var pulse by remember { mutableStateOf(0) }
    val scale = remember { Animatable(1f) }
    val halo = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            launch { halo.snapTo(1f); halo.animateTo(0f, tween(ShellMotion.SPRING_MS, easing = ShellMotion.Emphasized)) }
            scale.animateTo(0.82f, tween(ShellMotion.TAP_MS, easing = ShellMotion.Emphasized))
            scale.animateTo(1.18f, tween(ShellMotion.SPRING_MS / 2, easing = ShellMotion.Emphasized))
            scale.animateTo(1f, ShellMotion.spring())
        }
    }
    Box(
        Modifier.size(size).shellPressable(
            { pulse++; val now = SongLikeState.toggleLike(song); onToggled(now) },
            label = stringResource(if (liked) Res.string.shell_unlike else Res.string.shell_like),
            focusShape = CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val check = visual * 0.72f
        if (liked) {
            Box(
                Modifier.size(check).graphicsLayer { scaleX = 1f + halo.value * 0.5f; scaleY = 1f + halo.value * 0.5f; alpha = halo.value }
                    .clip(CircleShape).background(c.accent.copy(alpha = 0.18f)),
            )
        }
        Box(Modifier.size(visual).scale(scale.value), contentAlignment = Alignment.Center) {
            if (liked) {
                Box(Modifier.size(check).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { Glyph(Glyphs.Check, c.onAccent, check * 0.55f) }
            } else {
                Glyph(Glyphs.Add, c.ink, visual * 0.6f)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Now Playing (Android and iOS)
// ---------------------------------------------------------------------------------------------

/** What the phone player needs from upstream's view model, without its lyrics and colour ticks. */
private data class PlayerModes(val shuffle: Boolean, val repeat: Int, val queueTitle: String?)

/**
 * Spotify-familiar Now Playing in Shell's look. Lyrics, queue, devices and the overflow menu hand
 * over to upstream's full player (TurtleUiSlots.showUpstreamPlayer), so none of its features are lost;
 * Shell takes back over when the sheet collapses.
 */
@Composable
fun ShellNowPlaying(slot: PlayerSlot) = ShellTheme {
    SideEffect { if (slot.collapsed) TurtleUiSlots.showUpstreamPlayer = false }
    if (slot.collapsed) return@ShellTheme
    // Upstream's own back handler lives in the player we replace; without this, Back closes the app.
    NavigationBackHandler(enabled = true) { slot.onClose() }
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.rememberCalmState()
    val song = state.currentSong ?: return@ShellTheme
    val vm = slot.viewModel
    val modes by remember(vm) { vm.uiState.map { PlayerModes(it.isShuffleOn, it.repeatMode, it.queueTitle) }.distinctUntilChanged() }
        .collectAsState(PlayerModes(false, 0, null))
    val live = rememberLivePosition(controller, state.isPlaying, song.id)
    val duration = durationOf(state.durationMs, song)
    val tint = artworkTint(song.displayThumbnailUrl, 0.32f)
    val liked = SongLikeState.isLiked(song.id)
    var toast by remember { mutableStateOf<String?>(null) }
    val likedText = stringResource(Res.string.shell_liked_toast)
    val unlikedText = stringResource(Res.string.shell_unliked_toast)
    val lessText = stringResource(Res.string.shell_less_toast)
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }
    // Lyrics, queue, more, sleep and devices are Shell's own surfaces: we never hand over to upstream's player.
    var surface by remember { mutableStateOf<PlayerSurface?>(null) }
    val services = rememberPhoneServices(slot)
    val source = (modes.queueTitle ?: ShellQueue.activeTitle(state))?.takeIf { it.isNotBlank() }

    Box(Modifier.fillMaxSize().background(tint)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.ChevronDown, stringResource(Res.string.shell_close_player), slot.onClose, c.ink, iconSize = 24.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    // "Playing from" only makes sense with a source; a bare queue says "Now playing".
                    IndexLabel(stringResource(if (source != null) Res.string.shell_playing_from else Res.string.shell_now_playing_label), color = c.ink.copy(alpha = 0.75f))
                    if (source != null) Text(source, style = Shell.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                GlyphButton(Glyphs.More, stringResource(Res.string.shell_more), { surface = PlayerSurface.MORE }, c.ink)
            }
            // The art is the largest square that fits what height is left, centred in it, so tall
            // phones get full-width art and short screens or landscape keep every control.
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    song to state.currentQueueIndex,
                    transitionSpec = { skipTransition(targetState.second >= initialState.second) },
                    contentKey = { it.first.id },
                    contentAlignment = Alignment.Center,
                ) { (s, _) ->
                    val artScale by animateFloatAsState(if (state.isPlaying) 1f else 0.92f, ShellMotion.emphasized())
                    ShellArt(s.displayThumbnailUrl, Modifier.widthIn(max = 480.dp).aspectRatio(1f).scale(artScale), shape = RoundedCornerShape(8.dp), sizePx = 720)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(song, transitionSpec = { quickFade }, contentKey = { it.id }, modifier = Modifier.weight(1f)) { s ->
                    Column {
                        Text(s.title, style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.92f), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.artists.joinToString(", ") { it.name }, style = Shell.type.body, color = c.ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.shellPressable({ s.artists.firstOrNull()?.id?.let(slot.onNavigateToArtist) }),
                        )
                    }
                }
                GlyphButton(Glyphs.Less, stringResource(Res.string.shell_less_like_this), {
                    toast = lessText
                    ShellActions.lessLikeThis(controller, song)
                }, c.ink.copy(alpha = 0.85f), iconSize = 24.dp)
                LikeButton(song, liked, size = 48.dp) { now -> toast = if (now) likedText else unlikedText }
            }
            Spacer(Modifier.height(14.dp))
            ShellSeekBar({ live.ms }, duration, { controller.seekTo(it); live.ms = it }, stringResource(Res.string.shell_seek), Modifier.fillMaxWidth(), track = c.ink.copy(alpha = 0.25f), fill = c.ink)
            Row(Modifier.fillMaxWidth()) {
                TimeText({ live.ms }, c.ink.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = Shell.type.index, color = c.ink.copy(alpha = 0.75f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_shuffle), vm::toggleShuffle, if (modes.shuffle) c.accent else c.ink, size = 52.dp, iconSize = 24.dp, dot = modes.shuffle)
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, size = 60.dp, iconSize = 32.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 72.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, size = 60.dp, iconSize = 32.dp)
                GlyphButton(Glyphs.Repeat, stringResource(Res.string.shell_repeat), vm::toggleRepeat, if (modes.repeat != 0) c.accent else c.ink, size = 52.dp, iconSize = 24.dp, dot = modes.repeat != 0)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Devices, stringResource(Res.string.shell_devices), { surface = PlayerSurface.DEVICES }, if (state.castState.device != null) c.accent else c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyphs.Lyrics, stringResource(Res.string.shell_lyrics), { surface = PlayerSurface.LYRICS }, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
                GlyphButton(Glyphs.Queue, stringResource(Res.string.shell_queue), { surface = PlayerSurface.QUEUE }, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
            }
            SteerCard(tint)
            Spacer(Modifier.height(12.dp))
        }
        PhoneSurfaces(surface, { surface = it }, song, state, controller, services, live, tint)
        ShellToast(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** The Discovery dial: five stops from familiar to all new, snapping with a spring. */
@Composable
internal fun SteerCard(tint: Color, modifier: Modifier = Modifier, captions: Boolean = true) {
    val c = Shell.colors
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The engine's saved position, so every surface (phone, desktop panel) shows the same stop.
    val position by TurtleBridge.discoveryDial.collectAsState()
    val stop = (position * 4).roundToInt().coerceIn(0, 4)
    val labels = listOf(Res.string.shell_dial_0, Res.string.shell_dial_1, Res.string.shell_dial_2, Res.string.shell_dial_3, Res.string.shell_dial_4)
    val segment = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(lerp(tint, Color.White, 0.06f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.shell_steer_title), style = Shell.type.label.copy(fontWeight = FontWeight.Bold), color = c.ink, modifier = Modifier.weight(1f))
            AnimatedContent(stop, transitionSpec = { quickFade }) { s -> IndexLabel(stringResource(labels[s]), color = c.accent) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { i ->
                // Segments fill outward from "familiar", each one a beat after the last (H-Motion 06).
                val on = i <= stop
                val fill by animateColorAsState(if (on) c.accent else Color.Transparent, tween(ShellMotion.EXIT_MS, delayMillis = if (on) i * ShellMotion.STAGGER_MS else 0, easing = ShellMotion.Emphasized))
                val pop by animateFloatAsState(if (i == stop) 1f else 0.96f, ShellMotion.spring())
                Box(
                    Modifier.weight(1f).fillMaxHeight().scale(pop).clip(segment).background(fill).border(1.dp, c.ink.copy(alpha = if (on) 0f else 0.18f), segment)
                        .shellPressable({
                            if (i != stop) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            scope.launch { TurtleBridge.setDiscoveryDial(i / 4.0) }
                        }, label = stringResource(labels[i]), focusShape = segment),
                )
            }
        }
        if (captions) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(stringResource(Res.string.shell_familiar), style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.shell_new), style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f))
            }
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
    val state by controller.rememberCalmState()
    val song = state.currentSong ?: slot.restoredSong
    val live = rememberLivePosition(controller, state.isPlaying, song?.id)
    val duration = durationOf(state.durationMs, song)
    var volume by remember(slot.volume) { mutableFloatStateOf(slot.volume) }
    val shuffled = slot.isShuffled()
    val repeat = slot.repeatMode()
    // Queue, lyrics and devices are Shell's own right-panel surfaces; upstream's drawers stay closed.
    // Any left open from an earlier session are closed once, so they never cover the page.
    LaunchedEffect(Unit) {
        if (slot.isQueueOpen()) slot.onToggleQueue()
        if (slot.isLyricsOpen()) slot.onToggleLyrics()
    }
    val panel = ShellDesktop.panel.takeIf { ShellDesktop.nowPlayingOpen }
    val infinityTitle = stringResource(Res.string.shell_infinity_title)
    val infinityOn = ShellQueue.activeTitle(state) == infinityTitle
    SideEffect { ShellDesktop.restoredSong = slot.restoredSong }
    Row(Modifier.fillMaxWidth().height(80.dp).background(c.frame).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                ShellArt(song.displayThumbnailUrl, Modifier.size(56.dp).shellPressable({ ShellDesktop.show(DesktopPanel.NOW_PLAYING) }), shape = RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                AnimatedContent(song, transitionSpec = { quickFade }, contentKey = { it.id }, modifier = Modifier.weight(1f, fill = false)) { s ->
                    Column {
                        Text(s.title, style = Shell.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall.copy(fontSize = 12.sp), color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                LikeButton(song, SongLikeState.isLiked(song.id), size = 36.dp)
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                slot.onToggleShuffle?.let { toggle ->
                    GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_shuffle), toggle, if (shuffled) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = shuffled)
                }
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, size = 36.dp, iconSize = 20.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 40.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, size = 36.dp, iconSize = 20.dp)
                slot.onCycleRepeat?.let { cycle ->
                    GlyphButton(Glyphs.Repeat, stringResource(Res.string.shell_repeat), cycle, if (repeat != RepeatMode.OFF) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = repeat != RepeatMode.OFF)
                }
                // The Infinity Mix, one click from anywhere (H-Desktop's transport row).
                GlyphButton(Glyphs.Infinity, infinityTitle, {
                    if (infinityOn) controller.togglePlayPause() else ShellActions.playInfinity(controller, infinityTitle)
                }, if (infinityOn) c.accent else c.ink2, size = 36.dp, iconSize = 20.dp, dot = infinityOn)
            }
            Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeText({ live.ms }, c.ink2)
                Spacer(Modifier.width(8.dp))
                ShellSeekBar({ live.ms }, duration, { controller.seekTo(it); live.ms = it }, stringResource(Res.string.shell_seek), Modifier.weight(1f), track = c.hairline, fill = c.ink)
                Spacer(Modifier.width(8.dp))
                Text(formatTime(duration), style = Shell.type.index, color = c.ink2)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PanelButton(Glyphs.NowPlayingView, stringResource(Res.string.shell_now_playing_view), DesktopPanel.NOW_PLAYING, panel)
            PanelButton(Glyphs.Lyrics, stringResource(Res.string.shell_lyrics), DesktopPanel.LYRICS, panel)
            PanelButton(Glyphs.Queue, stringResource(Res.string.shell_queue), DesktopPanel.QUEUE, panel)
            PanelButton(Glyphs.Devices, stringResource(Res.string.shell_devices), DesktopPanel.DEVICES, panel)
            Glyph(Glyphs.Volume, c.ink2, 18.dp, contentDescription = stringResource(Res.string.shell_volume))
            Spacer(Modifier.width(6.dp))
            ShellSeekBar(
                { (volume * 1000).toLong() }, 1000, { v -> volume = v / 1000f; slot.onVolumeChange(volume) }, stringResource(Res.string.shell_volume),
                Modifier.width(96.dp), track = c.hairline, fill = c.ink, live = true,
            )
            GlyphButton(Glyphs.Fullscreen, stringResource(Res.string.shell_fullscreen), slot.onToggleFullscreen, c.ink2, size = 36.dp, iconSize = 18.dp)
        }
    }
}

/** Phone services: upstream's player view model and platform (downloads, playlists), in Shell's surfaces. */
@Composable
private fun rememberPhoneServices(slot: PlayerSlot): ShellPlayerServices {
    val vm = slot.viewModel
    val controller = LocalPlaybackController.current
    val platform = com.metrolist.shared.ui.player.LocalPlayerPlatform.current
    val addToPlaylist = platform.rememberPlaylistAdder()
    val download = platform.rememberSongDownloader()
    val downloaded = platform.downloadedSongIds()
    val lyrics by remember(vm) { vm.uiState.map { PhoneLyrics(it.lyrics, it.isLyricsLoading, it.lyricsOffsetMs, it.currentSong?.id) }.distinctUntilChanged() }
        .collectAsState(PhoneLyrics(emptyList(), false, 0, null))
    val sleep by remember(controller) { controller.state.map { it.sleepTimerState.isActive }.distinctUntilChanged() }.collectAsState(false)
    return ShellPlayerServices(
        lyrics = { lyrics.entries },
        lyricsLoading = { lyrics.loading },
        lyricsOffsetMs = { lyrics.offsetMs },
        ensureLyrics = { song ->
            val current = vm.uiState.value
            if (lyrics.songId != song.id || (current.lyrics.isEmpty() && !current.isLyricsLoading)) current.currentSong?.let { vm.loadLyrics(it) }
        },
        removeQueueIndex = { index -> vm.uiState.value.queueEntryKeys.getOrNull(index)?.let { vm.removeQueueEntry(it) } },
        addToPlaylist = { addToPlaylist(listOf(it)) },
        share = { vm.shareSong(it) },
        download = { download(listOf(it)) },
        isDownloaded = { it in downloaded },
        openArtist = { slot.onClose(); slot.onNavigateToArtist(it) },
        openAlbum = { slot.onClose(); slot.onNavigateToAlbum(it) },
        startSleepTimer = { ms, endOfSong -> if (endOfSong) controller.stopSleepTimerAfterCurrentSong(false) else controller.startSleepTimer(ms, false) },
        cancelSleepTimer = controller::cancelSleepTimer,
        sleepTimerActive = { sleep },
    )
}

private data class PhoneLyrics(val entries: List<com.metrolist.shared.model.LyricsEntry>, val loading: Boolean, val offsetMs: Int, val songId: String?)

/** Lyrics and queue take over the player full screen (as in Spotify); the rest are bottom sheets. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PhoneSurfaces(
    surface: PlayerSurface?,
    onSurface: (PlayerSurface?) -> Unit,
    song: SongItem,
    state: com.metrolist.shared.ui.playback.PlaybackUiState,
    controller: PlaybackController,
    services: ShellPlayerServices,
    live: LivePosition,
    tint: Color,
) {
    val c = Shell.colors
    val full = surface == PlayerSurface.LYRICS || surface == PlayerSurface.QUEUE
    androidx.compose.animation.AnimatedVisibility(
        full,
        enter = androidx.compose.animation.slideInVertically(ShellMotion.emphasized()) { it / 6 } + fadeIn(ShellMotion.emphasized()),
        exit = androidx.compose.animation.slideOutVertically(ShellMotion.exit()) { it / 6 } + fadeOut(ShellMotion.exit()),
        modifier = Modifier.matchParentSize(),
    ) {
        if (full) NavigationBackHandler(enabled = true) { onSurface(null) }
        // Remember which full surface is showing so the exit animation keeps its content.
        val shown = remember { arrayOf(PlayerSurface.LYRICS) }
        if (surface == PlayerSurface.LYRICS) shown[0] = PlayerSurface.LYRICS else if (surface == PlayerSurface.QUEUE) shown[0] = PlayerSurface.QUEUE
        Column(
            Modifier.fillMaxSize().background(lerp(tint, Color.Black, 0.15f)).windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp),
        ) {
            SurfaceHeader(song.title, song.artists.joinToString(", ") { it.name }) { onSurface(null) }
            if (shown[0] == PlayerSurface.LYRICS) {
                ShellLyricsFor(song, services, live, { controller.seekTo(it); live.ms = it }, Modifier.weight(1f).fillMaxWidth(), compact = false)
            } else {
                ShellQueueList(state, controller, services, Modifier.weight(1f).fillMaxWidth())
            }
            // A slim transport so lyrics and queue never strand you without controls.
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, iconSize = 24.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 56.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, iconSize = 24.dp)
            }
        }
    }
    val sheet = surface == PlayerSurface.MORE || surface == PlayerSurface.SLEEP || surface == PlayerSurface.DEVICES
    val lastSheet = remember { arrayOf(PlayerSurface.MORE) }
    if (sheet && surface != null) lastSheet[0] = surface
    ShellBottomSheet(sheet, onDismiss = { onSurface(null) }) {
        when (lastSheet[0]) {
            PlayerSurface.SLEEP -> ShellSleepTimer(services) { onSurface(null) }
            PlayerSurface.DEVICES -> ShellDevices(state, controller)
            else -> ShellMoreActions(song, controller, services, onShow = onSurface, onDone = { onSurface(null) })
        }
    }
}

/** A player-bar button that shows [mode] in the right panel; lime with a dot while it is showing. */
@Composable
private fun PanelButton(glyph: ImageVector, label: String, mode: DesktopPanel, showing: DesktopPanel?) {
    val c = Shell.colors
    val on = showing == mode
    GlyphButton(glyph, label, { ShellDesktop.toggle(mode) }, if (on) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = on)
}
