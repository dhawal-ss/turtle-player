package app.turtleplayer.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.turtleplayer.bridge.DjArc
import app.turtleplayer.bridge.DjEngine
import app.turtleplayer.mix.Familiarity
import app.turtleplayer.mix.PickReason
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.mix.VibeSettings
import app.turtleplayer.mix.brain.TransitionPlanner
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Renders Turtle DJ's setup and booth offscreen to PNGs (build/dj-snapshots), so the layout can be
 * reviewed without a device. Not an assertion test: it fails only if a screen throws while composing.
 */
class DjSnapshotTest {
    private val out = File("build/dj-snapshots").apply { mkdirs() }

    private fun song(id: String, title: String, artist: String, seconds: Long = 214) =
        SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = seconds)

    private class FakeController(song: SongItem?) : PlaybackController {
        override val state: StateFlow<PlaybackUiState> = MutableStateFlow(PlaybackUiState(currentSong = song, isPlaying = true, positionMs = 61_000, durationMs = 214_000))
        override fun play(song: SongItem, queue: List<SongItem>, songsOnly: Boolean) = Unit
        override fun shuffleQueue(songs: List<SongItem>, queueTitle: String?, songsOnly: Boolean) = Unit
        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun startRadio(song: SongItem) = Unit
        override fun playNext(songs: List<SongItem>) = Unit
        override fun addToQueue(songs: List<SongItem>) = Unit
    }

    private fun render(name: String, widthDp: Int, heightDp: Int, song: SongItem?, content: @Composable () -> Unit) {
        val density = 2f
        ImageComposeScene((widthDp * density).toInt(), (heightDp * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalPlaybackController provides FakeController(song)) {
                ShellTheme { Box(Modifier.fillMaxSize()) { content() } }
            }
        }.use { scene ->
            var t = 0L
            repeat(40) {
                scene.render(t)
                t += 16_000_000L
            }
            val image = scene.render(t)
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
            File(out, "$name.png").writeBytes(data.bytes)
        }
    }

    private fun session(mode: DjMode): DjSession {
        val s = DjSession(listOf("house", "dance", "pop"), DjArc.HouseParty, DjLength.M60, VibeSettings(0.55f, 0.4f, 0.6f, 0.3f, 0.5f), mode)
        val a = DjEngine.describe(song("a", "Midnight Drive", "Lumen Coast"), "house").copy(bpm = 124, bpmMeasured = true, familiarity = Familiarity.Favorite)
        val b = DjEngine.describe(song("b", "Glasshouse", "Mira Vale"), "house").copy(bpm = 126)
        val c = DjEngine.describe(song("c", "Neon Tides", "The Halfway Club"), "dance")
        val d = DjEngine.describe(song("d", "Afterglow (Extended Mix)", "Oskar Lind"), "house")
        val plan = TransitionPlanner.plan(a.facts(), b.facts(), s.vibe, emptyList(), Random(3))
        s.current = DjSlot(a, 0L, 0.5, reasons = listOf(PickReason.BuildingUp, PickReason.Favorite))
        s.upcoming += DjSlot(b, 200_000L, 0.6, reasons = listOf(PickReason.BuildingUp, PickReason.FreshFind, PickReason.TempoMatch), plan = plan)
        s.upcoming += DjSlot(c, 400_000L, 0.7, reasons = listOf(PickReason.PeakMoment))
        s.upcoming += DjSlot(d, 600_000L, 0.75, requested = true, reasons = listOf(PickReason.Requested))
        s.loading = false
        s.elapsedMs = 9 * 60_000L
        s.liveBpm = 124f
        s.liveBpmMeasured = true
        s.liveEnergy = 0.62f
        s.now = DjNow.OnAir(s.current!!)
        s.mixPointMs = 150_000L
        if (mode == DjMode.You) s.youStyle = TransitionStyle.EchoOut
        return s
    }

    @Test
    fun setupPhone() = render("setup-phone", 412, 2000, null) { TurtleDjPage(onClose = {}) }

    @Test
    fun boothAutoPhone() = session(DjMode.Auto).let { s -> render("booth-auto-phone", 412, 2600, s.current!!.track.song) { DjBooth(s, onExit = {}) } }

    @Test
    fun boothYouPhone() = session(DjMode.You).let { s -> render("booth-you-phone", 412, 3200, s.current!!.track.song) { DjBooth(s, onExit = {}) } }

    @Test
    fun boothAutoDesktop() = session(DjMode.Auto).let { s -> render("booth-auto-desktop", 1440, 1000, s.current!!.track.song) { DjBooth(s, onExit = {}) } }

    @Test
    fun boothMixing() = session(DjMode.Auto).let { s ->
        s.mixing = true
        s.mixStyle = TransitionStyle.EchoOut
        s.mixProgress = 0.45f
        s.now = DjNow.Mixing(TransitionStyle.EchoOut, s.upcoming.first(), manual = false)
        render("booth-mixing-phone", 412, 900, s.current!!.track.song) { DjBooth(s, onExit = {}) }
    }
}
