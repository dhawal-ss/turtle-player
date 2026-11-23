package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_lyrics_back_to_current
import app.turtleplayer.ui.generated.resources.shell_lyrics_loading
import app.turtleplayer.ui.generated.resources.shell_lyrics_none
import app.turtleplayer.ui.generated.resources.shell_lyrics_none_hint
import app.turtleplayer.ui.generated.resources.shell_lyrics_not_synced
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where the active line rests, as a fraction of the viewport height from the top. */
private const val ANCHOR = 0.3f

/** How long auto-follow stays paused after the user scrolls the lyrics themselves. */
private const val FOLLOW_PAUSE_MS = 4_000L

private const val PAST_ALPHA = 0.45f
private const val UPCOMING_ALPHA = 0.55f
private const val INACTIVE_SCALE = 0.98f

/** The unfilled part of a word-synced active line, before the sweep reaches it. */
private const val SWEEP_BASE_ALPHA = 0.5f

/**
 * Shell's lyrics: Spotify's lyrics card in Shell's look. Synced lyrics follow playback, keeping the
 * active line about 30% from the top; word-synced lines fill word by word, the current word in lime.
 * Unsynced lyrics are plain text. [position] is read only in derived state and in draw lambdas, so
 * the list recomposes when the line changes, not on every playback tick.
 */
@Composable
internal fun ShellLyricsView(
    entries: List<LyricsEntry>,
    loading: Boolean,
    position: () -> Long,
    offsetMs: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    compact: Boolean = false,
) {
    val metrics = lyricsMetrics(compact)
    when {
        entries.isEmpty() && loading -> LyricsSkeleton(metrics, modifier.padding(contentPadding))
        entries.isEmpty() -> LyricsEmpty(modifier.padding(contentPadding))
        else -> {
            val lines = remember(entries) { entries.map(::lineModel) }
            val synced = remember(entries) { LyricsUtils.hasPlaybackTiming(entries) }
            if (synced) {
                SyncedLyrics(entries, lines, metrics, position, offsetMs, onSeek, modifier, contentPadding)
            } else {
                UnsyncedLyrics(lines, metrics, modifier, contentPadding)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Model

/** A word-synced line's text with each main word's character range and timing (ms). */
@Immutable
private class Sweep(
    val starts: IntArray,
    val ends: IntArray,
    val startMs: LongArray,
    val endMs: LongArray,
)

/** What a line shows, computed once per lyrics load. */
@Immutable
private class LineModel(
    val time: Long,
    val text: String,
    val sweep: Sweep?,
    val background: String?,
    val romanized: String?,
    val translated: String?,
    val isBackground: Boolean,
) {
    val blank: Boolean get() = text.isBlank() && background == null
}

private fun lineModel(entry: LyricsEntry): LineModel {
    val main = entry.words?.filter { !it.isBackground }.orEmpty()
    val timed = main.any { !it.isLineTimed && it.endTime > it.startTime }
    var text = entry.text
    var sweep: Sweep? = null
    if (timed) {
        val builder = StringBuilder()
        val starts = IntArray(main.size)
        val ends = IntArray(main.size)
        val startMs = LongArray(main.size)
        val endMs = LongArray(main.size)
        main.forEachIndexed { i, word ->
            starts[i] = builder.length
            builder.append(word.text)
            ends[i] = builder.length
            if (word.hasTrailingSpace && i < main.lastIndex) builder.append(' ')
            startMs[i] = (word.startTime * 1000.0).roundToLong()
            endMs[i] = (word.endTime * 1000.0).roundToLong()
        }
        text = builder.toString()
        sweep = Sweep(starts, ends, startMs, endMs)
    }
    val background =
        entry.words
            ?.filter { it.isBackground }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: entry.backgroundText?.trim()?.takeIf { it.isNotBlank() }
    return LineModel(
        time = entry.time,
        text = text,
        sweep = sweep,
        background = background,
        romanized = entry.romanizedText?.trim()?.takeIf { it.isNotBlank() && it != text },
        translated = entry.translatedText?.trim()?.takeIf { it.isNotBlank() && it != text },
        isBackground = entry.isBackground,
    )
}

/** Index of the last main (non-background) entry whose time is at or before [at], or -1. */
private fun activeLine(entries: List<LyricsEntry>, main: IntArray, at: Long): Int {
    var lo = 0
    var hi = main.lastIndex
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (entries[main[mid]].time <= at) {
            found = main[mid]
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

private enum class LineState { PAST, ACTIVE, UPCOMING }

@Immutable
private class LyricsMetrics(
    val line: TextStyle,
    val sub: TextStyle,
    val background: TextStyle,
    val gap: Dp,
    val skeletonBar: Dp,
)

/**
 * Follows upstream's lyrics text size and line spacing settings as ratios of their defaults, so the
 * default settings keep Shell's own sizes.
 */
@Composable
private fun lyricsMetrics(compact: Boolean): LyricsMetrics {
    val type = Shell.type
    val settings = Settings.shared
    val textSize = settings.lyricsTextSize
    val lineSpacing = settings.lyricsLineSpacing
    return remember(type, compact, textSize, lineSpacing) {
        val size = textSize / settings.defaultValue(settings::lyricsTextSize)
        val spacing = lineSpacing / settings.defaultValue(settings::lyricsLineSpacing)
        val line =
            if (compact) {
                type.headline.copy(fontSize = 18.sp * size, lineHeight = 23.sp * size)
            } else {
                type.headline.copy(fontSize = type.headline.fontSize * 0.9f * size, lineHeight = type.headline.lineHeight * 0.9f * size)
            }
        val sub = (if (compact) type.bodySmall else type.body).let { it.copy(fontSize = it.fontSize * size, lineHeight = it.lineHeight * size) }
        LyricsMetrics(
            line = line,
            sub = sub,
            background = sub.copy(fontStyle = FontStyle.Italic),
            gap = (if (compact) 7.dp else 10.dp) * spacing,
            skeletonBar = (if (compact) 16.dp else 22.dp) * size,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Synced

@Composable
private fun SyncedLyrics(
    entries: List<LyricsEntry>,
    lines: List<LineModel>,
    metrics: LyricsMetrics,
    position: () -> Long,
    offsetMs: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val currentPosition by rememberUpdatedState(position)
    val currentOffset by rememberUpdatedState(offsetMs)
    val main = remember(entries) { entries.indices.filter { !entries[it].isBackground }.toIntArray() }
    // Changes only when the line changes, so reading playback here doesn't recompose per tick.
    val active by remember(entries) {
        derivedStateOf { activeLine(entries, main, currentPosition() + currentOffset) }
    }

    val listState = remember(entries) { LazyListState() }
    var following by remember(entries) { mutableStateOf(true) }
    var userScrolls by remember(entries) { mutableIntStateOf(0) }
    var placed by remember(entries) { mutableStateOf(false) }

    // Programmatic scrolls don't go through nested scroll; only the user's drags, flings and wheel do.
    val userScrollWatcher =
        remember(entries) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y != 0f) {
                        following = false
                        userScrolls++
                    }
                    return Offset.Zero
                }
            }
        }

    LaunchedEffect(userScrolls) {
        if (userScrolls == 0) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        delay(FOLLOW_PAUSE_MS)
        following = true
    }

    LaunchedEffect(active, following, listState) {
        if (!following || active < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val anchor = listState.layoutInfo.viewportSize.height * ANCHOR
        if (!placed) {
            listState.scrollToItem(active)
            listState.scrollBy(-anchor)
            placed = true
        } else {
            followLine(listState, active, anchor)
        }
    }

    BoxWithConstraints(modifier.nestedScroll(userScrollWatcher)) {
        val tail = if (constraints.hasBoundedHeight) maxHeight * 0.5f else 0.dp
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            items(lines.size, key = { it }, contentType = { if (lines[it].sweep != null) 1 else 0 }) { index ->
                val line = lines[index]
                val state = lineState(entries, index, active)
                SyncedLine(
                    line = line,
                    state = state,
                    metrics = metrics,
                    position = position,
                    offsetMs = offsetMs,
                    onClick =
                        if (line.blank) {
                            null
                        } else {
                            {
                                following = true
                                onSeek(LyricsUtils.lyricsSeekPositionForOffset(line.time, offsetMs))
                            }
                        },
                )
            }
            item(key = "tail") { Spacer(Modifier.height(tail)) }
        }

        AnimatedVisibility(
            visible = !following && active >= 0,
            enter = fadeIn(ShellMotion.emphasized()) + slideInVertically(ShellMotion.spring()) { it },
            exit = fadeOut(ShellMotion.exit()) + slideOutVertically(ShellMotion.exit()) { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
        ) {
            val label = stringResource(Res.string.shell_lyrics_back_to_current)
            Box(
                Modifier
                    .shellPressable(onClick = { following = true }, label = label, focusShape = CircleShape)
                    .clip(CircleShape)
                    .background(Shell.colors.surface3)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text(label, style = Shell.type.label, color = Shell.colors.ink) }
        }
    }
}

/** Background-vocal entries right after the active line are sung with it, so they light up too. */
private fun lineState(entries: List<LyricsEntry>, index: Int, active: Int): LineState =
    when {
        active < 0 -> LineState.UPCOMING
        index == active -> LineState.ACTIVE
        index < active -> LineState.PAST
        entries[index].isBackground && ((active + 1)..index).all { entries[it].isBackground } -> LineState.ACTIVE
        else -> LineState.UPCOMING
    }

/** Moves [index] to the anchor with Shell's emphasized curve; far jumps (seeks) land first, then settle. */
private suspend fun followLine(listState: LazyListState, index: Int, anchor: Float) {
    val spec = ShellMotion.emphasized<Float>()
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        val delta = (item.offset - info.viewportStartOffset) - anchor
        if (abs(delta) > 1f) listState.animateScrollBy(delta, spec)
    } else {
        listState.scrollToItem(index)
        listState.animateScrollBy(-anchor, spec)
    }
}

@Composable
private fun SyncedLine(
    line: LineModel,
    state: LineState,
    metrics: LyricsMetrics,
    position: () -> Long,
    offsetMs: Int,
    onClick: (() -> Unit)?,
) {
    val c = Shell.colors
    val fade by animateFloatAsState(
        when (state) {
            LineState.PAST -> PAST_ALPHA
            LineState.ACTIVE -> 1f
            LineState.UPCOMING -> UPCOMING_ALPHA
        },
        ShellMotion.emphasized(),
    )
    val zoom by animateFloatAsState(if (state == LineState.ACTIVE) 1f else INACTIVE_SCALE, ShellMotion.emphasized())
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = fade
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0f, 0.5f)
            }.then(if (onClick != null) Modifier.shellPressable(onClick, label = line.text.ifBlank { line.background }) else Modifier)
            .padding(vertical = metrics.gap),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            // An instrumental gap: the dots are decoration, not words to read out.
            line.blank -> Text("•  •  •", style = metrics.line, color = c.ink, modifier = Modifier.clearAndSetSemantics {})
            line.isBackground -> Text(line.text, style = metrics.background, color = c.ink2)
            line.sweep != null && state == LineState.ACTIVE ->
                SweepText(line.text, line.sweep, metrics.line, position, offsetMs)
            line.text.isNotBlank() -> Text(line.text, style = metrics.line, color = c.ink)
        }
        SubLines(line, metrics)
    }
}

@Composable
private fun SubLines(line: LineModel, metrics: LyricsMetrics) {
    val c = Shell.colors
    if (line.background != null && !line.isBackground) Text(line.background, style = metrics.background, color = c.ink2)
    if (line.romanized != null) Text(line.romanized, style = metrics.sub, color = c.ink2)
    if (line.translated != null) Text(line.translated, style = metrics.sub, color = c.ink2)
}

/**
 * The active word-synced line. Laid out once; the karaoke fill is painted in the draw phase from
 * [position], so playback ticks only redraw: sung words in ink, the current word sweeping in lime.
 */
@Composable
private fun SweepText(text: String, sweep: Sweep, style: TextStyle, position: () -> Long, offsetMs: Int) {
    val c = Shell.colors
    val layout = remember(text, style) { arrayOfNulls<TextLayoutResult>(1) }
    Text(
        text,
        style = style,
        color = c.ink.copy(alpha = SWEEP_BASE_ALPHA),
        onTextLayout = { layout[0] = it },
        modifier =
            Modifier.drawWithContent {
                drawContent()
                val result = layout[0] ?: return@drawWithContent
                drawSweep(result, sweep, position() + offsetMs, c.ink, c.accent)
            },
    )
}

private fun DrawScope.drawSweep(layout: TextLayoutResult, sweep: Sweep, at: Long, ink: Color, accent: Color) {
    var done = 0f
    var fill = 0f
    for (i in sweep.starts.indices) {
        val start = sweep.startMs[i]
        val end = sweep.endMs[i]
        if (at >= end && at >= start) {
            done = sweep.ends[i].toFloat()
            fill = done
            continue
        }
        if (at >= start && end > start) {
            val f = ((at - start).toFloat() / (end - start)).coerceIn(0f, 1f)
            fill = sweep.starts[i] + f * (sweep.ends[i] - sweep.starts[i])
        }
        break
    }
    if (done > 0f) fillChars(layout, 0f, done, ink)
    if (fill > done) fillChars(layout, done, fill, accent)
}

/** Repaints characters [from, to) (fractional at the edges) of [layout] in [color], line by line. */
private fun DrawScope.fillChars(layout: TextLayoutResult, from: Float, to: Float, color: Color) {
    val length = layout.layoutInput.text.length
    if (length == 0) return
    for (line in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        if (to <= lineStart || from >= lineEnd) continue
        val x0 = if (from <= lineStart) 0f else xAt(layout, from, length)
        val x1 = if (to >= lineEnd) size.width else xAt(layout, to, length)
        if (x1 <= x0) continue
        clipRect(x0, layout.getLineTop(line), x1, layout.getLineBottom(line)) {
            drawText(textLayoutResult = layout, color = color)
        }
    }
}

private fun xAt(layout: TextLayoutResult, char: Float, length: Int): Float {
    val i = char.toInt().coerceIn(0, length - 1)
    val box = layout.getBoundingBox(i)
    return box.left + (char - i).coerceIn(0f, 1f) * box.width
}

// ---------------------------------------------------------------------------------------------
// Unsynced, loading, empty

@Composable
private fun UnsyncedLyrics(lines: List<LineModel>, metrics: LyricsMetrics, modifier: Modifier, contentPadding: PaddingValues) {
    val c = Shell.colors
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "label") {
            IndexLabel(stringResource(Res.string.shell_lyrics_not_synced), Modifier.padding(bottom = 12.dp))
        }
        items(lines.size, key = { it }) { index ->
            val line = lines[index]
            if (line.blank) {
                Spacer(Modifier.height(metrics.gap * 2))
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = metrics.gap / 2), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (line.isBackground) {
                        Text(line.text, style = metrics.background, color = c.ink2)
                    } else if (line.text.isNotBlank()) {
                        Text(line.text, style = metrics.line, color = c.ink)
                    }
                    SubLines(line, metrics)
                }
            }
        }
    }
}

private val SkeletonWidths = floatArrayOf(0.92f, 0.7f, 0.84f, 0.56f, 0.78f, 0.64f)

@Composable
private fun LyricsSkeleton(metrics: LyricsMetrics, modifier: Modifier) {
    val label = stringResource(Res.string.shell_lyrics_loading)
    val pulse = rememberInfiniteTransition()
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(metrics.gap * 1.6f),
    ) {
        SkeletonWidths.forEachIndexed { i, width ->
            val a by pulse.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        tween(900, easing = ShellMotion.Emphasized),
                        RepeatMode.Reverse,
                        StartOffset(i * ShellMotion.STAGGER_MS * 3),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .height(metrics.skeletonBar)
                    .graphicsLayer { alpha = a }
                    .clip(RoundedCornerShape(6.dp))
                    .background(Shell.colors.surface2),
            )
        }
    }
}

@Composable
private fun LyricsEmpty(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.shell_lyrics_none),
                style = Shell.type.title.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = Shell.colors.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.shell_lyrics_none_hint),
                style = Shell.type.bodySmall,
                color = Shell.colors.ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}
