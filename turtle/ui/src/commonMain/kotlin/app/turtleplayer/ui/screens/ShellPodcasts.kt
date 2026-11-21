package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_sp_continue
import app.turtleplayer.ui.generated.resources.shell_sp_left
import app.turtleplayer.ui.generated.resources.shell_sp_loading
import app.turtleplayer.ui.generated.resources.shell_sp_new_episodes
import app.turtleplayer.ui.generated.resources.shell_sp_podcasts_empty_body
import app.turtleplayer.ui.generated.resources.shell_sp_podcasts_empty_title
import app.turtleplayer.ui.generated.resources.shell_sp_popular_shows
import app.turtleplayer.ui.generated.resources.shell_sp_your_shows
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What Home's Podcasts filter shows. */
internal class PodcastShelves(
    val continueEpisodes: List<SongItem>,
    val newEpisodes: List<SongItem>,
    val followed: List<PodcastItem>,
    val popular: List<PodcastItem>,
) {
    val isEmpty: Boolean get() = continueEpisodes.isEmpty() && newEpisodes.isEmpty() && followed.isEmpty()
}

/** Home's podcast shelves, kept for the session so switching chips doesn't reload them. */
internal object ShellPodcastsHome {
    private const val NETWORK_REFRESH_MS = 10 * 60_000L

    var shelves by mutableStateOf<PodcastShelves?>(null)
        private set
    private var fetchedAt: TimeMark? = null

    /** Continue listening is local and always refreshed; the network shelves at most every ten minutes. */
    suspend fun refresh(homeShows: List<PodcastItem>) {
        val spoken = TurtleBridge.spoken
        val current = shelves
        val stale = fetchedAt?.let { it.elapsedNow().inWholeMilliseconds > NETWORK_REFRESH_MS } ?: true
        if (current != null && !stale) {
            shelves = PodcastShelves(spoken.continueEpisodes(), current.newEpisodes, current.followed, current.popular)
            return
        }
        coroutineScope {
            val followed = async { spoken.followedShows() }
            val resume = async { spoken.continueEpisodes() }
            val popular = async { spoken.popularShows() }
            val shows = followed.await()
            val fresh = spoken.newEpisodes(shows)
            val followedIds = shows.map { it.id }.toSet()
            shelves = PodcastShelves(
                continueEpisodes = resume.await(),
                newEpisodes = fresh,
                followed = shows,
                popular = (homeShows + popular.await()).distinctBy { it.id }.filterNot { it.id in followedIds }.take(24),
            )
        }
        fetchedAt = TimeSource.Monotonic.markNow()
    }
}

/**
 * Home's Podcasts filter: continue listening (episodes left halfway, which upstream resumes), new
 * episodes from followed shows, the shows themselves and popular shows to discover. Shows open
 * upstream's show route through Home's own callbacks; episodes play.
 */
@Composable
internal fun ShellPodcastsSection(slot: HomeSlot, modifier: Modifier = Modifier) {
    val playback = LocalPlaybackController.current
    LaunchedEffect(playback) { ShellSpoken.track(playback) }
    LaunchedEffect(Unit) { ShellPodcastsHome.refresh(slot.uiState.podcasts) }
    val shelves = ShellPodcastsHome.shelves
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val desktop = ShellPlatform.desktop
        val wide = maxWidth > 840.dp
        // Desktop windows wide enough for a grid show rows of cards instead of a scrolling row.
        val showColumns = if (desktop && maxWidth >= 900.dp) (maxWidth / 190.dp).toInt().coerceIn(4, 8) else 0
        val episodeColumns = if (desktop && maxWidth >= 900.dp) (maxWidth / 340.dp).toInt().coerceIn(2, 4) else 0
        Column {
            if (shelves == null) {
                PodcastsPlaceholder()
                return@Column
            }
            if (shelves.continueEpisodes.isNotEmpty()) {
                EpisodeShelf(stringResource(Res.string.shell_sp_continue), shelves.continueEpisodes, slot, episodeColumns, queueAll = false)
            }
            if (shelves.newEpisodes.isNotEmpty()) {
                EpisodeShelf(stringResource(Res.string.shell_sp_new_episodes), shelves.newEpisodes, slot, episodeColumns, queueAll = true)
            }
            if (shelves.followed.isNotEmpty()) {
                ShowShelf(stringResource(Res.string.shell_sp_your_shows), shelves.followed, slot, wide, showColumns)
            }
            if (shelves.isEmpty) PodcastsEmpty()
            if (shelves.popular.isNotEmpty()) {
                ShowShelf(stringResource(Res.string.shell_sp_popular_shows), shelves.popular, slot, wide, showColumns)
            }
        }
    }
}

@Composable
private fun EpisodeShelf(title: String, episodes: List<SongItem>, slot: HomeSlot, columns: Int, queueAll: Boolean) {
    val desktop = ShellPlatform.desktop
    Column {
        Spacer(Modifier.height(24.dp))
        SpokenSectionTitle(title)
        Spacer(Modifier.height(12.dp))
        if (columns > 0) {
            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                episodes.take(columns * 2).chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { EpisodeCard(it, episodes, slot, queueAll, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 4.dp else 12.dp)) {
                items(episodes, key = { it.id }) { EpisodeCard(it, episodes, slot, queueAll, Modifier.width(300.dp)) }
            }
        }
    }
}

/** An episode as a wide card: art, title, show, time left and a progress line when started. */
@Composable
internal fun EpisodeCard(episode: SongItem, shelf: List<SongItem>, slot: HomeSlot, queueAll: Boolean, modifier: Modifier) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val calm by playback.rememberCalmState()
    val playing = calm.isPlaying && calm.currentSong?.id == episode.id
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface3 else c.surface2, ShellMotion.emphasized())
    val play = { playback.play(episode, if (queueAll) shelf else listOf(episode)) }
    val durationMs = (episode.duration ?: 0L) * 1000L
    val position = episode.playbackPositionMs ?: 0L
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .shellPressable(play, label = episode.title, onLongClick = slot.onItemLongClick?.let { f -> { f(episode) } })
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ShellArt(episode.thumbnail, Modifier.size(72.dp), shape = ShellShapes.Art)
            HoverPlayButton(hovered && !playing, 32.dp, play, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                episode.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (playing) c.accent else c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val show = episode.album?.name?.takeIf { it.isNotBlank() } ?: episode.artists.firstOrNull()?.name
            if (show != null) Text(show, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    Equalizer(height = 10.dp)
                    Spacer(Modifier.width(6.dp))
                }
                val meta = when {
                    durationMs > 0 && position > 0 -> stringResource(Res.string.shell_sp_left, spokenLength((durationMs - position).coerceAtLeast(0L) / 1000))
                    durationMs > 0 -> spokenLength(durationMs / 1000)
                    else -> null
                }
                if (meta != null) Text(meta, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1)
            }
            if (durationMs > 0 && position > 0) SpokenProgress(position.toFloat() / durationMs, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ShowShelf(title: String, shows: List<PodcastItem>, slot: HomeSlot, wide: Boolean, columns: Int) {
    val desktop = ShellPlatform.desktop
    Column {
        Spacer(Modifier.height(24.dp))
        SpokenSectionTitle(title)
        Spacer(Modifier.height(12.dp))
        if (columns > 0) {
            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shows.take(columns * 2).chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { ShowCard(it, slot, null, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            val art = spokenArt(wide)
            LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp)) {
                items(shows, key = { it.id }) { ShowCard(it, slot, art, Modifier.width(art)) }
            }
        }
    }
}

/** A show as Home's square card; opens upstream's show route (Shell's show page when installed). */
@Composable
private fun ShowCard(show: PodcastItem, slot: HomeSlot, art: Dp?, modifier: Modifier) {
    val c = Shell.colors
    val (hover, hovered) = rememberHover()
    Column(
        modifier.hoverable(hover)
            .shellPressable({ slot.onItemClick(show) }, label = show.title, onLongClick = slot.onItemLongClick?.let { f -> { f(show) } })
            .cardHover(hovered),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ShellArt(show.thumbnail, if (art != null) Modifier.size(art) else Modifier.fillMaxWidth().aspectRatio(1f), shape = ShellShapes.Art)
        Text(show.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        show.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Nothing followed or started yet: a short note with the podcast glyph (popular shows follow below). */
@Composable
private fun PodcastsEmpty() {
    val c = Shell.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()
            .clip(shape).background(c.surface1).border(1.dp, c.hairline, shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) { Glyph(Glyphs.Podcast, c.accent, 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.shell_sp_podcasts_empty_title), style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.ink)
            Text(stringResource(Res.string.shell_sp_podcasts_empty_body), style = Shell.type.bodySmall, color = c.ink2)
        }
    }
}

/** Pulsing placeholder rows while the shelves load. */
@Composable
internal fun PodcastsPlaceholder() {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val label = stringResource(Res.string.shell_sp_loading)
    Column(
        Modifier.padding(16.dp, 24.dp, 16.dp, 0.dp).semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(180.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { Box(Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2)) }
        }
        Box(Modifier.width(140.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) { Box(Modifier.weight(1f).aspectRatio(1f).clip(ShellShapes.Art).background(c.surface2)) }
        }
    }
}
