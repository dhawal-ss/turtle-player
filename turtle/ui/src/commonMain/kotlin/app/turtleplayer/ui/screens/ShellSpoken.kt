package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.BookProgress
import app.turtleplayer.bridge.SpokenBook
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.SectionHeader
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_sp_back_15
import app.turtleplayer.ui.generated.resources.shell_sp_forward_30
import app.turtleplayer.ui.generated.resources.shell_sp_hours_minutes
import app.turtleplayer.ui.generated.resources.shell_sp_minutes
import app.turtleplayer.ui.generated.resources.shell_sp_sleep
import app.turtleplayer.ui.generated.resources.shell_sp_sleep_end
import app.turtleplayer.ui.generated.resources.shell_sp_sleep_off
import app.turtleplayer.ui.generated.resources.shell_sp_speed
import app.turtleplayer.ui.generated.resources.shell_sp_speed_value
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Spoken word in Shell: which items count as podcasts or books, per-book resume and the listener's
 * spoken-word speed. One tracker follows the app's playback controller (it lives as long as the app,
 * like the controller): it seeks a book to where the listener stopped, saves the position every few
 * seconds and on pause, and applies the spoken-word speed to podcasts and books only, putting music
 * back to 1×. Upstream already resumes podcast episodes, so only books are saved here.
 */
internal object ShellSpoken {
    val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f)
    val SLEEP_MINUTES = listOf(15, 30, 45, 60)
    private const val TICK_MS = 1_000L
    private const val SAVE_EVERY_MS = 10_000L
    private const val SEEK_WAIT_MS = 60_000L
    /** Positions this close to the start aren't worth a seek. */
    private const val MIN_RESUME_MS = 5_000L

    private val scope = MainScope()
    private var tracked: PlaybackController? = null
    private var job: Job? = null

    /** The speed the listener picked for spoken word (1× until chosen; restored from our database). */
    var speed by mutableFloatStateOf(1f)
        private set
    private var speedApplied = false

    private class PendingSeek(val songId: String, val positionMs: Long, val deadline: TimeMark)

    private var pendingSeek: PendingSeek? = null

    /** The book whose chapters are loading, so its card can show it. */
    var opening by mutableStateOf<String?>(null)
        private set

    /** A podcast episode or a book we opened: what spoken-word speed and resume apply to. */
    fun isSpokenWord(song: SongItem?): Boolean = song != null && TurtleBridge.spoken.isSpokenWord(song)

    /** When the player shows spoken-word controls: spoken word, or any long-form video (lectures, readings). */
    fun showsControls(song: SongItem?): Boolean =
        song != null && (isSpokenWord(song) || (song.isVideoSong && (song.duration ?: 0L) >= app.turtleplayer.bridge.TurtleSpoken.MIN_BOOK_SECONDS))

    /** Starts following [controller]; idempotent, so every Shell surface can call it. */
    fun track(controller: PlaybackController) {
        if (tracked === controller) return
        tracked = controller
        job?.cancel()
        scope.launch {
            TurtleBridge.spoken.load()
            speed = TurtleBridge.spoken.speed()
        }
        job = scope.launch { follow(controller) }
    }

    private suspend fun follow(controller: PlaybackController) {
        var lastId: String? = null
        var wasPlaying = false
        var lastSave = TimeSource.Monotonic.markNow()
        while (true) {
            val s = controller.state.value
            val song = s.currentSong
            if (song?.id != lastId) {
                lastId = song?.id
                applySpeed(controller, song)
            }
            pendingSeek?.let { p ->
                if (p.deadline.hasPassedNow()) {
                    pendingSeek = null
                } else if (song?.id == p.songId && s.durationMs > 0 && !s.isLoading) {
                    pendingSeek = null
                    controller.seekTo(p.positionMs)
                    lastSave = TimeSource.Monotonic.markNow()
                }
            }
            if (song != null && pendingSeek == null && TurtleBridge.spoken.bookOf(song.id) != null) {
                val paused = wasPlaying && !s.isPlaying
                if (paused || (s.isPlaying && lastSave.elapsedNow().inWholeMilliseconds >= SAVE_EVERY_MS)) {
                    val position = controller.currentPositionMs()
                    if (position > 0) TurtleBridge.spoken.record(song.id, position, s.durationMs)
                    lastSave = TimeSource.Monotonic.markNow()
                }
            }
            wasPlaying = s.isPlaying
            delay(TICK_MS)
        }
    }

    private fun applySpeed(controller: PlaybackController, song: SongItem?) {
        if (isSpokenWord(song)) {
            if (speed != 1f) {
                controller.setPlaybackSpeed(speed)
                speedApplied = true
            }
        } else if (speedApplied) {
            controller.setPlaybackSpeed(1f)
            speedApplied = false
        }
    }

    fun setSpeed(controller: PlaybackController, value: Float) {
        speed = value
        TurtleBridge.spoken.setSpeed(value)
        controller.setPlaybackSpeed(value)
        speedApplied = value != 1f
    }

    /** Settings' default speed: saved for spoken word, applied now only if spoken word is playing (music stays 1×). */
    fun setDefaultSpeed(controller: PlaybackController, value: Float) {
        if (isSpokenWord(controller.state.value.currentSong)) return setSpeed(controller, value)
        speed = value
        TurtleBridge.spoken.setSpeed(value)
    }

    /** Plays [book] from where the listener stopped (loading its chapters first for playlists). */
    fun playBook(controller: PlaybackController, book: SpokenBook, onFailed: () -> Unit = {}) {
        if (opening != null) return
        track(controller)
        opening = book.id
        scope.launch {
            try {
                TurtleBridge.spoken.load()
                val chapters = TurtleBridge.spoken.chapters(book)
                if (chapters.isEmpty()) {
                    onFailed()
                    return@launch
                }
                val (index, position) = TurtleBridge.spoken.open(book, chapters)
                start(controller, chapters, index.coerceIn(0, chapters.lastIndex), position, book.title, book.id)
            } finally {
                opening = null
            }
        }
    }

    fun resume(controller: PlaybackController, progress: BookProgress, onFailed: () -> Unit = {}) = playBook(controller, progress.toBook(), onFailed)

    private fun start(controller: PlaybackController, chapters: List<SongItem>, index: Int, positionMs: Long, title: String, bookId: String) {
        val chapter = chapters[index]
        val s = controller.state.value
        if (s.currentSong?.id == chapter.id) {
            // Already loaded: carry on rather than restarting it.
            if (!s.isPlaying) {
                if (positionMs > MIN_RESUME_MS) controller.seekTo(positionMs)
                controller.togglePlayPause()
            }
            return
        }
        pendingSeek = if (positionMs > MIN_RESUME_MS) PendingSeek(chapter.id, positionMs, TimeSource.Monotonic.markNow() + SEEK_WAIT_MS.milliseconds) else null
        ShellQueue.play(controller, chapters, title, index, sourceId = bookId)
    }

    /** Sleep timer through desktop's player state when present, else the controller (phones). */
    fun startSleep(controller: PlaybackController, minutes: Int?) {
        val extras = TurtleUiSlots.desktopExtras
        when {
            extras != null -> extras.startSleepTimer(minutes?.times(60_000L) ?: 0L, minutes == null)
            minutes == null -> controller.stopSleepTimerAfterCurrentSong(fadeOut = true)
            else -> controller.startSleepTimer(minutes * 60_000L, fadeOut = true)
        }
    }

    fun cancelSleep(controller: PlaybackController) {
        val extras = TurtleUiSlots.desktopExtras
        if (extras != null) extras.cancelSleepTimer() else controller.cancelSleepTimer()
    }
}

private enum class SpokenPanel { NONE, SPEED, SLEEP }

/**
 * Spoken-word controls for the player: back 15 s, playback speed (0.8–2×), forward 30 s and a
 * sleep-timer shortcut; speed and sleep open a row of choices underneath. Self-gating: renders
 * nothing unless [song] is a podcast episode, a book or a long-form video, so it can sit in the
 * player unconditionally. [tint] colors the icons (the player's ink by default).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpokenWordControls(song: SongItem?, controller: PlaybackController, modifier: Modifier = Modifier, tint: Color = Shell.colors.ink) {
    LaunchedEffect(controller) { ShellSpoken.track(controller) }
    if (!ShellSpoken.showsControls(song)) return
    val c = Shell.colors
    val calm by controller.rememberCalmState()
    val extras = TurtleUiSlots.desktopExtras
    val sleepOn = if (extras != null) extras.sleepTimerActive() else calm.sleepTimerState.isActive
    var panel by remember(song?.id) { mutableStateOf(SpokenPanel.NONE) }
    val speed = ShellSpoken.speed
    val compact = !ShellPlatform.desktop

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(Glyphs.SkipBack, "15", stringResource(Res.string.shell_sp_back_15), tint) {
                controller.seekTo((controller.currentPositionMs() - 15_000L).coerceAtLeast(0L))
            }
            SpeedPill(speed, panel == SpokenPanel.SPEED) { panel = if (panel == SpokenPanel.SPEED) SpokenPanel.NONE else SpokenPanel.SPEED }
            SkipButton(Glyphs.SkipForward, "30", stringResource(Res.string.shell_sp_forward_30), tint) {
                val duration = controller.state.value.durationMs
                val target = controller.currentPositionMs() + 30_000L
                controller.seekTo(if (duration > 0) minOf(target, duration - 1_000L) else target)
            }
            val sleepLabel = stringResource(Res.string.shell_sp_sleep)
            Box(
                Modifier.size(48.dp).shellPressable({ panel = if (panel == SpokenPanel.SLEEP) SpokenPanel.NONE else SpokenPanel.SLEEP }, label = sleepLabel, focusShape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Glyphs.Sleep, if (sleepOn) c.accent else tint, 22.dp)
                if (sleepOn) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(4.dp).clip(CircleShape).background(c.accent))
            }
        }
        AnimatedVisibility(
            panel != SpokenPanel.NONE,
            enter = expandVertically(ShellMotion.emphasized()) + fadeIn(ShellMotion.emphasized()),
            exit = shrinkVertically(ShellMotion.exit()) + fadeOut(ShellMotion.exit()),
        ) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (panel == SpokenPanel.SPEED) {
                    ShellSpoken.SPEEDS.forEach { v ->
                        ShellChip(stringResource(Res.string.shell_sp_speed_value, speedText(v)), v == speed, {
                            ShellSpoken.setSpeed(controller, v)
                            panel = SpokenPanel.NONE
                        })
                    }
                } else if (panel == SpokenPanel.SLEEP) {
                    ShellSpoken.SLEEP_MINUTES.forEach { m ->
                        ShellChip(stringResource(Res.string.shell_sp_minutes, m), false, {
                            ShellSpoken.startSleep(controller, m)
                            panel = SpokenPanel.NONE
                        })
                    }
                    ShellChip(stringResource(Res.string.shell_sp_sleep_end), false, {
                        ShellSpoken.startSleep(controller, null)
                        panel = SpokenPanel.NONE
                    })
                    if (sleepOn) {
                        ShellChip(stringResource(Res.string.shell_sp_sleep_off), true, {
                            ShellSpoken.cancelSleep(controller)
                            panel = SpokenPanel.NONE
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SkipButton(glyph: androidx.compose.ui.graphics.vector.ImageVector, seconds: String, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).shellPressable(onClick, label = label, focusShape = CircleShape), contentAlignment = Alignment.Center) {
        Glyph(glyph, tint, 30.dp)
        Text(seconds, style = Shell.type.index.copy(fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold), color = tint, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SpeedPill(speed: Float, open: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val bg by animateColorAsState(if (open) c.surface3 else Color.Transparent, ShellMotion.emphasized())
    val label = stringResource(Res.string.shell_sp_speed)
    Box(
        Modifier.height(32.dp).clip(CircleShape).background(bg).border(1.dp, if (speed != 1f) c.accent else c.hairline, CircleShape)
            .shellPressable(onClick, label = label, focusShape = CircleShape).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.shell_sp_speed_value, speedText(speed)),
            style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (speed != 1f) c.accent else c.ink,
            maxLines = 1,
        )
    }
}

/** "1", "1.5", "0.8": no trailing zeros. */
internal fun speedText(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

/** "42 min" or "1 h 12 min" for [seconds] of listening. */
@Composable
internal fun spokenLength(seconds: Long): String {
    val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
    return if (minutes >= 60) {
        stringResource(Res.string.shell_sp_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    } else {
        stringResource(Res.string.shell_sp_minutes, minutes.toInt())
    }
}

/** A thin progress line (lime on a hairline track), for episodes and books in progress. */
@Composable
internal fun SpokenProgress(fraction: Float, modifier: Modifier = Modifier) {
    val c = Shell.colors
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(c.accent))
    }
}

/** Section titles for the spoken-word shelves: Home's sizes (24 sp on desktop, the shared header on phones). */
@Composable
internal fun SpokenSectionTitle(title: String, modifier: Modifier = Modifier) {
    if (ShellPlatform.desktop) {
        Text(
            title, style = Shell.type.sectionTitle.copy(fontSize = 24.sp, lineHeight = 28.sp), color = Shell.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    } else {
        SectionHeader(title, modifier = modifier)
    }
}

/** Card width for spoken-word rows: Home's art sizes. */
internal fun spokenArt(wide: Boolean): Dp = if (wide) 168.dp else 136.dp
