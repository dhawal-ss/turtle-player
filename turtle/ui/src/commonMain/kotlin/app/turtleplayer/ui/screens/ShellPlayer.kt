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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
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
import app.turtleplayer.ui.components.ShellSnackbar
import app.turtleplayer.ui.components.ShellSnackbarHost
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_inf_tune
import app.turtleplayer.ui.generated.resources.shell_inf_up_toast
import app.turtleplayer.ui.generated.resources.shell_inf_down_toast
import app.turtleplayer.ui.generated.resources.shell_inf_blend_familiar
import app.turtleplayer.ui.generated.resources.shell_inf_blend_new
import app.turtleplayer.ui.generated.resources.shell_inf_blend_actual
import app.turtleplayer.ui.generated.resources.shell_inf_blend_sparse
import app.turtleplayer.ui.generated.resources.shell_inf_blend_sparse_none
import app.turtleplayer.ui.generated.resources.shell_inf_resteered
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
import app.turtleplayer.ui.generated.resources.shell_open_player
import app.turtleplayer.ui.generated.resources.shell_pause
import app.turtleplayer.ui.generated.resources.shell_play
import app.turtleplayer.ui.generated.resources.shell_playing_from
import app.turtleplayer.ui.generated.resources.shell_previous
import app.turtleplayer.ui.generated.resources.shell_queue
import app.turtleplayer.ui.generated.resources.shell_repeat
import app.turtleplayer.ui.generated.resources.shell_repeat_state_all
import app.turtleplayer.ui.generated.resources.shell_repeat_state_one
import app.turtleplayer.ui.generated.resources.shell_seek
import app.turtleplayer.ui.generated.resources.shell_shuffle
import app.turtleplayer.ui.generated.resources.shell_state_off
import app.turtleplayer.ui.generated.resources.shell_state_on
import app.turtleplayer.ui.generated.resources.shell_steer_title
import app.turtleplayer.ui.generated.resources.shell_unlike
import app.turtleplayer.ui.generated.resources.shell_unliked_toast
import app.turtleplayer.ui.generated.resources.shell_undo
import app.turtleplayer.ui.generated.resources.shell_mute
import app.turtleplayer.ui.generated.resources.shell_unmute
import app.turtleplayer.ui.generated.resources.shell_volume
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellColors
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellTheme
import app.turtleplayer.ui.theme.readableTint
import com.metrolist.shared.generated.resources.playback_position_time
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
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.metrolist.shared.generated.resources.Res as SharedRes

/**
 * Ground tinted with a share of the artwork's color: every song feels different, chrome stays calm.
 * [texts] are the dimmest text colors drawn on it; a bright cover's tint is darkened until they read.
 */
@Composable
internal fun artworkTint(
    url: String?,
    amount: Float,
    base: Color = Shell.colors.ground,
    texts: List<Color> = secondaryTexts(Shell.colors),
): Color {
    val ground = base
    val seed = rememberArtworkSeedColor(url)
    val mixed = if (seed == null) Shell.colors.surface2 else lerp(ground, seed, amount)
    // Clamp the target, not the animated value: the animation then runs between two readable colors.
    val target = remember(mixed, texts) { readableTint(mixed, texts) }
    val tint by animateColorAsState(target, tween(ShellMotion.TINT_MS, easing = ShellMotion.Emphasized))
    return tint
}

/**
 * The text colors that can wash out on a tinted background: dimmed ink, ink2, index labels, and
 * accent labels, which a same-hue cover tint can swallow.
 */
private fun secondaryTexts(c: ShellColors) = listOf(c.ink.copy(alpha = 0.7f), c.ink2, c.index, c.accent)

internal fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val secs = (s % 60).toString().padStart(2, '0')
    if (s < 3600) return "${s / 60}:$secs"
    return "${s / 3600}:${(s / 60 % 60).toString().padStart(2, '0')}:$secs"
}

/** Repeat's glyph and what screen readers say it is set to. */
private fun repeatGlyph(mode: RepeatMode): ImageVector = if (mode == RepeatMode.ONE) Glyphs.RepeatOne else Glyphs.Repeat

@Composable
private fun repeatState(mode: RepeatMode): String = stringResource(
    when (mode) {
        RepeatMode.OFF -> Res.string.shell_state_off
        RepeatMode.ONE -> Res.string.shell_repeat_state_one
        RepeatMode.ALL -> Res.string.shell_repeat_state_all
    },
)

@Composable
internal fun onOffState(on: Boolean): String = stringResource(if (on) Res.string.shell_state_on else Res.string.shell_state_off)

/** The song's length, falling back to its metadata while the player has not reported one yet. */
private fun durationOf(reportedMs: Long, song: SongItem?): Long =
    reportedMs.takeIf { it > 0 } ?: song?.duration?.toLong()?.takeIf { it > 0 }?.times(1000L) ?: 0L

@Composable
internal fun GlyphButton(glyph: ImageVector, label: String, onClick: () -> Unit, tint: Color, size: Dp = 48.dp, iconSize: Dp = 22.dp, dot: Boolean = false, state: String? = null) {
    Column(
        Modifier.size(size).shellPressable(onClick, label = label, focusShape = CircleShape)
            .then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(glyph, tint, iconSize)
        if (dot) Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(tint))
    }
}

/**
 * Thin bar with a knob: follows your finger with no delay, seeks on release or tap ([live] seeks
 * while dragging, for volume). The bar is drawn, not laid out, so the position tick only redraws it.
 * On desktop the knob appears and the fill turns lime on hover (H-Motion 08). With [spokenTime],
 * screen readers hear the elapsed and total time instead of a percentage.
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
    spokenTime: Boolean = false,
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
        SeekSemantics(label, fraction, { v -> seek((v * durationMs).toLong()) }, if (spokenTime) position else null, durationMs)
    }
}

/**
 * The seek bar's screen-reader node. It is its own composable so that the spoken time recomposes
 * only this node, and only when the second changes, never the bar.
 */
@Composable
private fun BoxScope.SeekSemantics(
    label: String,
    fraction: () -> Float,
    onSeek: (Float) -> Unit,
    position: (() -> Long)?,
    durationMs: Long,
) {
    val elapsed by remember(position) { derivedStateOf { position?.let { formatTime(it()) } } }
    val time = elapsed?.let { stringResource(SharedRes.string.playback_position_time, it, formatTime(durationMs)) }
    Box(
        Modifier.matchParentSize().semantics {
            contentDescription = label
            if (time != null) stateDescription = time
            progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
            setProgress { v -> onSeek(v.coerceIn(0f, 1f)); true }
        },
    )
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

/** How far the mini player must be swiped to skip (the 120 px it used to be on a 3x phone). */
private val SkipSwipe = 40.dp

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
    val tint = artworkTint(song.displayThumbnailUrl, 0.45f, texts = listOf(c.ink.copy(alpha = 0.75f)))
    app.turtleplayer.ui.theme.ShellPalettes.FollowCover(song.displayThumbnailUrl)
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val track = c.ink.copy(alpha = 0.25f)
    // Screen readers can't swipe: skipping is offered as actions on the player instead.
    val nextLabel = stringResource(Res.string.shell_next)
    val previousLabel = stringResource(Res.string.shell_previous)
    Box(
        slot.modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp).clip(RoundedCornerShape(8.dp)).background(tint)
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = drag.value
                        val threshold = SkipSwipe.toPx()
                        if (dx < -threshold) controller.skipToNext() else if (dx > threshold) controller.skipToPrevious()
                        scope.launch { drag.animateTo(0f, ShellMotion.spring()) }
                    },
                ) { _, amount -> scope.launch { drag.snapTo(drag.value + amount) } }
            }
            .shellPressable(slot.onExpand, onClickLabel = stringResource(Res.string.shell_open_player))
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { controller.skipToNext(); true },
                    CustomAccessibilityAction(previousLabel) { controller.skipToPrevious(); true },
                )
            }
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
            val addToPlaylist = com.metrolist.shared.ui.player.LocalPlayerPlatform.current.rememberPlaylistAdder()
            AddToPlaylistButton(song, { addToPlaylist(listOf(it)) }, c.ink, size = 48.dp, iconSize = 20.dp)
            LikeButton(song, SongLikeState.isLiked(song.id), size = 48.dp, visual = 40.dp, onToggled = rememberLikeSnackbar(song))
            PlayPauseButton(state.isPlaying, controller::togglePlayPause, 48.dp, Color.Transparent, c.ink, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
        }
    }
}

/** "Added to Liked Songs · Undo" for like buttons that are too small to say it themselves. */
@Composable
internal fun rememberLikeSnackbar(song: SongItem): (Boolean) -> Unit {
    val likedText = stringResource(Res.string.shell_liked_toast)
    val unlikedText = stringResource(Res.string.shell_unliked_toast)
    val undo = stringResource(Res.string.shell_undo)
    return remember(song, likedText, unlikedText, undo) {
        { now -> ShellSnackbar.show(if (now) likedText else unlikedText, undo, onAction = { SongLikeState.toggleLike(song) }) }
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
    val vm = slot.viewModel
    // Retuning the Infinity Mix from Home trims Up Next too, so this is registered while collapsed as well.
    SideEffect { ShellInfinity.removeAt = vm::removeQueueIndex }
    // Composed while collapsed too, so the listening log keeps the queue order and playhead on phones.
    val handoff = LocalPlaybackController.current
    DisposableEffect(handoff) {
        TurtleBridge.attachController(handoff)
        onDispose { TurtleBridge.detachController(handoff) }
    }
    if (slot.collapsed) return@ShellTheme
    // Upstream's own back handler lives in the player we replace; without this, Back closes the app.
    NavigationBackHandler(enabled = true) { slot.onClose() }
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.rememberCalmState()
    val song = state.currentSong ?: return@ShellTheme
    val modes by remember(vm) { vm.uiState.map { PlayerModes(it.isShuffleOn, it.repeatMode, it.queueTitle) }.distinctUntilChanged() }
        .collectAsState(PlayerModes(false, 0, null))
    val live = rememberLivePosition(controller, state.isPlaying, song.id)
    val duration = durationOf(state.durationMs, song)
    val tint = artworkTint(song.displayThumbnailUrl, 0.32f, texts = listOf(c.ink.copy(alpha = 0.75f), c.accent))
    val liked = SongLikeState.isLiked(song.id)
    var toast by remember { mutableStateOf<String?>(null) }
    val likedText = stringResource(Res.string.shell_liked_toast)
    val unlikedText = stringResource(Res.string.shell_unliked_toast)
    val lessText = stringResource(Res.string.shell_less_toast)
    val infUpText = stringResource(Res.string.shell_inf_up_toast)
    val infDownText = stringResource(Res.string.shell_inf_down_toast)
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }
    // Lyrics, queue, more, sleep and devices are Shell's own surfaces: we never hand over to upstream's player.
    var surface by remember { mutableStateOf<PlayerSurface?>(null) }
    val services = rememberPhoneServices(slot)
    val source = (modes.queueTitle ?: ShellQueue.activeTitle(state))?.takeIf { it.isNotBlank() }
    val infinityOn = ShellQueue.activeTitle(state) == stringResource(Res.string.shell_infinity_title)

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
                    // Playing the Infinity Mix: tap its name to tune genres, moods and the blend.
                    if (source != null) Text(
                        source, style = Shell.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = if (infinityOn) Modifier.shellPressable({ ShellInfinity.sheetOpen = true }, label = stringResource(Res.string.shell_inf_tune)) else Modifier,
                    )
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
                        if (infinityOn) InfinityWhyLabel(s.id, Modifier.padding(top = 4.dp))
                    }
                }
                // Add to playlist is always one tap away.
                AddToPlaylistButton(song, services.addToPlaylist, c.ink.copy(alpha = 0.85f))
                if (infinityOn) {
                    // The Infinity Mix speaks thumbs: each one retunes what plays next.
                    InfinityThumbs(song, controller, c.ink.copy(alpha = 0.85f)) { up -> toast = if (up) infUpText else infDownText }
                } else {
                    GlyphButton(Glyphs.Less, stringResource(Res.string.shell_less_like_this), {
                        toast = lessText
                        ShellActions.lessLikeThis(controller, song)
                    }, c.ink.copy(alpha = 0.85f), iconSize = 24.dp)
                    LikeButton(song, liked, size = 48.dp) { now -> toast = if (now) likedText else unlikedText }
                }
            }
            Spacer(Modifier.height(14.dp))
            ShellSeekBar({ live.ms }, duration, { controller.seekTo(it); live.ms = it }, stringResource(Res.string.shell_seek), Modifier.fillMaxWidth(), track = c.ink.copy(alpha = 0.25f), fill = c.ink, spokenTime = true)
            Row(Modifier.fillMaxWidth()) {
                TimeText({ live.ms }, c.ink.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = Shell.type.index, color = c.ink.copy(alpha = 0.75f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_shuffle), vm::toggleShuffle, if (modes.shuffle) c.accent else c.ink, size = 52.dp, iconSize = 24.dp, dot = modes.shuffle, state = onOffState(modes.shuffle))
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, size = 60.dp, iconSize = 32.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 72.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, size = 60.dp, iconSize = 32.dp)
                // Upstream's view model reports repeat as 0 off, 1 one, 2 all.
                val repeat = when (modes.repeat) {
                    1 -> RepeatMode.ONE
                    2 -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
                GlyphButton(repeatGlyph(repeat), stringResource(Res.string.shell_repeat), vm::toggleRepeat, if (repeat != RepeatMode.OFF) c.accent else c.ink, size = 52.dp, iconSize = 24.dp, dot = repeat != RepeatMode.OFF, state = repeatState(repeat))
            }
            // Podcasts and audiobooks: speed, -15 / +30 and sleep (hidden for music).
            SpokenWordControls(song = song, controller = controller, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Devices, stringResource(Res.string.shell_devices), { surface = PlayerSurface.DEVICES }, if (state.castState.device != null) c.accent else c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyphs.Lyrics, stringResource(Res.string.shell_lyrics), { surface = PlayerSurface.LYRICS }, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
                GlyphButton(Glyphs.Queue, stringResource(Res.string.shell_queue), { surface = PlayerSurface.QUEUE }, c.ink.copy(alpha = 0.85f), iconSize = 20.dp)
            }
            SteerCard(tint)
            Spacer(Modifier.height(12.dp))
        }
        PhoneSurfaces(surface, { surface = it }, song, state, controller, services, live, tint) { toast = it }
        InfinitySheetHost()
        ShellToast(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
        // Queue undo and the like; over the queue it clears the slim transport.
        ShellSnackbarHost(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (surface == PlayerSurface.QUEUE || surface == PlayerSurface.LYRICS) 72.dp else 0.dp),
        )
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
    val controller = LocalPlaybackController.current
    // "Up next re-mixed" for a moment after a change, when the Infinity Mix is playing.
    var resteered by remember { mutableStateOf(0) }
    LaunchedEffect(resteered) { if (resteered > 0) { delay(2200); resteered = 0 } }
    val familiarPct = (app.turtleplayer.bridge.infinityFamiliarShare(stop / 4.0) * 100).roundToInt()
    // The card is lighter than the tint under it, so its captions need their own check.
    val card = remember(tint, c.ink, c.accent) { readableTint(lerp(tint, Color.White, 0.06f), listOf(c.ink.copy(alpha = 0.75f), c.accent)) }
    Column(modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(card).padding(14.dp)) {
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
                            scope.launch {
                                TurtleBridge.setDiscoveryDial(i / 4.0)
                                // Heard within a song: the mix's queued songs are rebuilt with the new blend.
                                if (i != stop && ShellInfinity.resteer(controller)) resteered++
                            }
                        }, label = stringResource(labels[i]), focusShape = segment, selected = i == stop),
                )
            }
        }
        if (captions) {
            // The blend this stop gives the Infinity Mix, so the dial's effect is a number, not a guess.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                AnimatedContent(familiarPct, transitionSpec = { quickFade }) { p ->
                    Text(stringResource(Res.string.shell_inf_blend_familiar, p), style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f))
                }
                Spacer(Modifier.weight(1f))
                AnimatedContent(resteered > 0, transitionSpec = { quickFade }) { show ->
                    if (show) Text(stringResource(Res.string.shell_inf_resteered), style = Shell.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                }
                Spacer(Modifier.weight(1f))
                AnimatedContent(100 - familiarPct, transitionSpec = { quickFade }) { p ->
                    Text(stringResource(Res.string.shell_inf_blend_new, p), style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.75f))
                }
            }
            BlendNotes(stop, controller)
        }
    }
}

/**
 * Under the dial's aim: why a young profile hears mostly new music (too few favourites for the
 * target), and, only while the Infinity Mix is the queue playing, how its last batch came out. The
 * dial also steers radio and autoplay, so the actual blend is never shown for those.
 */
@Composable
private fun BlendNotes(
    stop: Int,
    controller: PlaybackController,
) {
    val c = Shell.colors
    val mix by TurtleBridge.infinity.collectAsState()
    val blend = mix.blend
    // Counted fresh off the UI thread whenever the stop or a new batch changes what is wanted.
    val favourites by produceState<Int?>(null, stop, blend) { value = TurtleBridge.infinityFavourites() }
    val wanted = kotlin.math.ceil(app.turtleplayer.bridge.infinityFamiliarShare(stop / 4.0) * INFINITY_BATCH).toInt()
    val state by controller.rememberCalmState()
    val playing = ShellQueue.activeTitle(state) == stringResource(Res.string.shell_infinity_title)
    val muted = c.ink.copy(alpha = 0.75f)
    favourites?.takeIf { it < wanted }?.let { n ->
        val text =
            if (n == 0) stringResource(Res.string.shell_inf_blend_sparse_none) else pluralStringResource(Res.plurals.shell_inf_blend_sparse, n, n)
        Text(text, style = Shell.type.bodySmall, color = muted, modifier = Modifier.padding(top = 6.dp))
    }
    if (playing && blend != null && blend.total > 0) {
        Text(
            stringResource(Res.string.shell_inf_blend_actual, blend.familiar, blend.total),
            style = Shell.type.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// Songs in the Infinity Mix's opening batch: the target is judged against it.
private const val INFINITY_BATCH = 12

// ---------------------------------------------------------------------------------------------
// Desktop player bar
// ---------------------------------------------------------------------------------------------

/** Spotify's desktop player bar layout in Shell's look. */
@Composable
fun ShellDesktopPlayerBar(slot: DesktopPlayerBarSlot) = ShellTheme {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    // Queue order and playhead for the listening log (skip vs. choosing another song, seeks).
    DisposableEffect(controller) {
        TurtleBridge.attachController(controller)
        onDispose { TurtleBridge.detachController(controller) }
    }
    val state by controller.rememberCalmState()
    val song = state.currentSong ?: slot.restoredSong
    val live = rememberLivePosition(controller, state.isPlaying, song?.id)
    val duration = durationOf(state.durationMs, song)
    var volume by remember(slot.volume) { mutableFloatStateOf(slot.volume) }
    val shuffled = slot.isShuffled()
    val repeat = slot.repeatMode()
    // Queue, lyrics and devices are Shell's own right-panel surfaces; upstream's drawers stay closed.
    // Any left open from an earlier session are closed once, so they never cover the page; after that,
    // upstream's Q and L shortcuts (which open its drawers) open Shell's queue and lyrics panel instead.
    app.turtleplayer.ui.theme.ShellPalettes.FollowCover(song?.displayThumbnailUrl)
    InfinitySheetHost()
    val bar by rememberUpdatedState(slot)
    val hasSong by rememberUpdatedState(song != null)
    LaunchedEffect(Unit) {
        var restored = true
        snapshotFlow { bar.isQueueOpen() to bar.isLyricsOpen() }.collect { (queueOpen, lyricsOpen) ->
            if (queueOpen) {
                bar.onToggleQueue()
                if (!restored) ShellDesktop.toggle(DesktopPanel.QUEUE, hasSong)
            }
            if (lyricsOpen) {
                bar.onToggleLyrics()
                if (!restored) ShellDesktop.toggle(DesktopPanel.LYRICS, hasSong)
            }
            restored = false
        }
    }
    val setVolume: (Float) -> Unit = { v ->
        volume = v.coerceIn(0f, 1f)
        slot.onVolumeChange(volume)
    }
    val currentVolume by rememberUpdatedState(volume)
    val currentSetVolume by rememberUpdatedState(setVolume)
    // The level to go back to on unmute.
    var unmutedVolume by remember { mutableFloatStateOf(slot.volume.takeIf { it > 0f } ?: DEFAULT_UNMUTE_VOLUME) }
    // Lit only while the surface is really on screen (docked or, in narrow windows, over the page).
    val panelShowing: (DesktopPanel) -> Boolean = { mode -> ShellDesktop.panelState.showing(mode, song != null) }
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
                com.metrolist.shared.turtle.TurtleUiSlots.desktopExtras?.addToPlaylist?.let { add ->
                    AddToPlaylistButton(song, add, c.ink2, size = 36.dp, iconSize = 18.dp)
                }
                if (ShellQueue.activeTitle(state) == stringResource(Res.string.shell_infinity_title)) {
                    InfinityThumbs(song, controller, c.ink2, size = 36.dp, iconSize = 18.dp)
                } else {
                    LikeButton(song, SongLikeState.isLiked(song.id), size = 36.dp, onToggled = rememberLikeSnackbar(song))
                }
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                slot.onToggleShuffle?.let { toggle ->
                    GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_shuffle), toggle, if (shuffled) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = shuffled, state = onOffState(shuffled))
                }
                GlyphButton(Glyphs.Previous, stringResource(Res.string.shell_previous), controller::skipToPrevious, c.ink, size = 36.dp, iconSize = 20.dp)
                PlayPauseButton(state.isPlaying, controller::togglePlayPause, 40.dp, c.ink, c.ground, playLabel = stringResource(Res.string.shell_play), pauseLabel = stringResource(Res.string.shell_pause))
                GlyphButton(Glyphs.Next, stringResource(Res.string.shell_next), controller::skipToNext, c.ink, size = 36.dp, iconSize = 20.dp)
                slot.onCycleRepeat?.let { cycle ->
                    GlyphButton(repeatGlyph(repeat), stringResource(Res.string.shell_repeat), cycle, if (repeat != RepeatMode.OFF) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = repeat != RepeatMode.OFF, state = repeatState(repeat))
                }
                // The Infinity Mix, one click from anywhere (H-Desktop's transport row).
                GlyphButton(Glyphs.Infinity, infinityTitle, {
                    if (infinityOn) controller.togglePlayPause() else ShellActions.playInfinity(controller, infinityTitle)
                }, if (infinityOn) c.accent else c.ink2, size = 36.dp, iconSize = 20.dp, dot = infinityOn, state = onOffState(infinityOn))
            }
            Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeText({ live.ms }, c.ink2)
                Spacer(Modifier.width(8.dp))
                ShellSeekBar({ live.ms }, duration, { controller.seekTo(it); live.ms = it }, stringResource(Res.string.shell_seek), Modifier.weight(1f), track = c.hairline, fill = c.ink, spokenTime = true)
                Spacer(Modifier.width(8.dp))
                Text(formatTime(duration), style = Shell.type.index, color = c.ink2)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PanelButton(Glyphs.NowPlayingView, stringResource(Res.string.shell_now_playing_view), DesktopPanel.NOW_PLAYING, panelShowing(DesktopPanel.NOW_PLAYING), song != null)
            PanelButton(Glyphs.Lyrics, stringResource(Res.string.shell_lyrics), DesktopPanel.LYRICS, panelShowing(DesktopPanel.LYRICS), song != null)
            PanelButton(Glyphs.Queue, stringResource(Res.string.shell_queue), DesktopPanel.QUEUE, panelShowing(DesktopPanel.QUEUE), song != null)
            PanelButton(Glyphs.Devices, stringResource(Res.string.shell_devices), DesktopPanel.DEVICES, panelShowing(DesktopPanel.DEVICES), song != null)
            // Spotify's volume: the icon mutes and unmutes, the wheel nudges it anywhere over the control.
            Row(
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                            if (dy == 0f) continue
                            currentSetVolume(currentVolume - dy * VOLUME_WHEEL_STEP)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val muted = volume <= 0f
                GlyphButton(
                    if (muted) Glyphs.VolumeMuted else Glyphs.Volume,
                    stringResource(if (muted) Res.string.shell_unmute else Res.string.shell_mute),
                    {
                        if (muted) {
                            setVolume(unmutedVolume)
                        } else {
                            unmutedVolume = volume
                            setVolume(0f)
                        }
                    },
                    c.ink2, size = 32.dp, iconSize = 18.dp,
                )
                Spacer(Modifier.width(2.dp))
                ShellSeekBar(
                    { (volume * 1000).toLong() }, 1000, { v -> setVolume(v / 1000f) }, stringResource(Res.string.shell_volume),
                    Modifier.width(96.dp), track = c.hairline, fill = c.ink, live = true,
                )
            }
            GlyphButton(Glyphs.Fullscreen, stringResource(Res.string.shell_fullscreen), slot.onToggleFullscreen, c.ink2, size = 36.dp, iconSize = 18.dp)
        }
    }
}

/** Removes the queue entry at [index] through upstream's player view model (phones). */
private fun PlayerViewModel.removeQueueIndex(index: Int) {
    uiState.value.queueEntryKeys.getOrNull(index)?.let { removeQueueEntry(it) }
}

/** Volume change per wheel notch, and the level unmute restores when nothing was muted from the bar. */
private const val VOLUME_WHEEL_STEP = 0.05f
private const val DEFAULT_UNMUTE_VOLUME = 0.5f

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
        removeQueueIndex = vm::removeQueueIndex,
        addToPlaylist = { addToPlaylist(listOf(it)) },
        share = { vm.shareSong(it) },
        download = { download(listOf(it)) },
        isDownloaded = { it in downloaded },
        openArtist = { slot.onClose(); slot.onNavigateToArtist(it) },
        openAlbum = { slot.onClose(); slot.onNavigateToAlbum(it) },
        startSleepTimer = { ms, endOfSong -> if (endOfSong) controller.stopSleepTimerAfterCurrentSong(false) else controller.startSleepTimer(ms, false) },
        cancelSleepTimer = controller::cancelSleepTimer,
        sleepTimerActive = { sleep },
        queueEditor = remember(vm, controller) { phoneQueueEditor(vm, controller) },
    )
}

/**
 * Upstream's own queue edits behind the player view model (entry keys, with its undo), which work on
 * Android and iOS; the controller's index-based removal is a no-op on Android. Indices are only
 * trusted while the view model's entries line up with the controller's queue.
 */
private fun phoneQueueEditor(vm: com.metrolist.shared.viewmodels.PlayerViewModel, controller: PlaybackController): ShellQueueEditor {
    fun keys(): List<Long>? = vm.uiState.value.queueEntryKeys.takeIf { it.size == controller.state.value.queue.size }
    return ShellQueueEditor(
        remove = remove@{ indices ->
            val keys = keys() ?: return@remove null
            val selected = indices.mapNotNullTo(HashSet()) { keys.getOrNull(it) }
            if (selected.isEmpty() || vm.removeQueueEntries(selected) == null) return@remove null
            val removals = vm.pendingQueueRemovals()
            QueueUndo(restore = { vm.restoreQueueItems(removals) }, discard = { vm.discardQueueUndo(removals) })
        },
        reorder = reorder@{ order ->
            val keys = keys() ?: return@reorder false
            order.size == keys.size && vm.reorderQueueEntries(order.map(keys::get))
        },
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
    onToast: (String) -> Unit,
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
        // The queue draws ink2 and index labels, which the player's own tint check leaves out.
        val backdrop = remember(tint, c) { readableTint(lerp(tint, Color.Black, 0.15f), secondaryTexts(c)) }
        Column(
            Modifier.fillMaxSize().background(backdrop).windowInsetsPadding(WindowInsets.statusBars)
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
            else -> ShellMoreActions(song, controller, services, onShow = onSurface, onDone = { onSurface(null) }, onToast = onToast)
        }
    }
}

/** A player-bar button that shows [mode] in the right panel; lime with a dot while it is showing. */
@Composable
private fun PanelButton(glyph: ImageVector, label: String, mode: DesktopPanel, on: Boolean, hasSong: Boolean) {
    val c = Shell.colors
    GlyphButton(glyph, label, { ShellDesktop.toggle(mode, hasSong) }, if (on) c.accent else c.ink2, size = 36.dp, iconSize = 18.dp, dot = on, state = onOffState(on))
}
