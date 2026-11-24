package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.SectionHeader
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_charts
import app.turtleplayer.ui.generated.resources.shell_community
import app.turtleplayer.ui.generated.resources.shell_explore_failed
import app.turtleplayer.ui.generated.resources.shell_explore_label
import app.turtleplayer.ui.generated.resources.shell_explore_loading
import app.turtleplayer.ui.generated.resources.shell_explore_offline
import app.turtleplayer.ui.generated.resources.shell_explore_retry
import app.turtleplayer.ui.generated.resources.shell_explore_subtitle
import app.turtleplayer.ui.generated.resources.shell_explore_title
import app.turtleplayer.ui.generated.resources.shell_explore_waiting
import app.turtleplayer.ui.generated.resources.shell_moods_genres
import app.turtleplayer.ui.generated.resources.shell_new_releases
import app.turtleplayer.ui.generated.resources.shell_popular_picks
import app.turtleplayer.ui.generated.resources.shell_popular_picks_source
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellShapes
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Tile colors for moods and genres; [genreTint] picks one per grid cell. */
internal val GenreTints = listOf(0xFF8A4FD1, 0xFFD9704A, 0xFF2E7E9E, 0xFFC2412E, 0xFF2E9E7A, 0xFF5B4BD8, 0xFFB7792B, 0xFFC4577A, 0xFF3E6FD8, 0xFF6B8E23, 0xFFAD3E8C).map { Color(it) }

/** Steps through [GenreTints] down a column; coprime to its size (11), like the step of 1 along a row. */
private const val GENRE_ROW_STEP = 4

/**
 * The tint for the genre tile at [row], [col]. Moving one tile right advances 1 through the palette
 * and one tile down advances [GENRE_ROW_STEP]; both are non-zero modulo the palette size, so a tile
 * never shares a hue with the one beside it or below it, at any column count up to the palette size.
 */
internal fun genreTint(row: Int, col: Int): Color = GenreTints[(row * GENRE_ROW_STEP + col) % GenreTints.size]

/**
 * Explore: where you wander outside your taste. Home stays "yours"; this page gathers YouTube Music's
 * moods and genres, new releases, charts and community playlists. Items open through upstream's
 * opener, exactly as from Home.
 */
@Composable
internal fun ShellExplorePage(home: HomeSlot?, listState: LazyListState, rise: RiseIn, modifier: Modifier = Modifier) {
    val c = Shell.colors
    Box(modifier.background(c.panel)) {
        if (home == null) {
            Text(stringResource(Res.string.shell_explore_waiting), style = Shell.type.body, color = c.ink2, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val s = home.uiState
        // YouTube can repeat an item within a section; keyed rows need each id once.
        val homeGenres = remember(s.moodsAndGenres) {
            s.moodsAndGenres.distinctBy { it.id to it.params }.map { BrowseItem(id = it.id, title = it.name, thumbnail = it.thumbnail, params = it.params) }
        }
        // Signed out, Home's feed carries Explore's content. Signed in it has no moods and genres (nor,
        // on its first page, charts or new releases): then Explore loads YouTube Music's Explore page
        // itself, and each section Home leaves empty comes from that load. The load is asked for only while
        // Home has no genres, but what it brought keeps filling Home's empty sections after: upstream can
        // append moods to a signed-in Home once its feed is paged to the end, and that must not take away
        // the new releases and charts Explore already shows.
        val ownLoad = homeGenres.isEmpty()
        val explore = rememberExploreState(ownLoad)
        val own = explore.data
        val genres = remember(homeGenres, own) { homeGenres.ifEmpty { own?.genres.orEmpty().distinctBy { it.id to it.params } } }
        val newReleases = remember(s.newReleases, own) { s.newReleases.ifEmpty { own?.newReleases.orEmpty() }.distinctBy { it.id } }
        val trending = remember(s.trending, own) { s.trending.ifEmpty { own?.trending.orEmpty() }.distinctBy { it.id }.take(10) }
        val albumCharts = remember(s.albumCharts, own) { s.albumCharts.ifEmpty { own?.albumCharts.orEmpty() }.distinctBy { it.id } }
        val community = remember(s.communityPlaylist, own) { s.communityPlaylist.ifEmpty { own?.community.orEmpty() }.distinctBy { it.id } }
        // Placeholders only while the load is asked for or running. When it fails (or finds nothing) the
        // page says so and offers Retry, rather than pulse placeholders forever.
        val ownPending = ownLoad && genres.isEmpty() && (explore.loading || (!explore.failed && explore.data == null))
        val ownFailed = ownLoad && genres.isEmpty() && !explore.loading && explore.failed
        // Held by the caller, so Back from a page opened here finds the list where it was.
        SettleRiseIn(rise, listState)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth > 1300.dp -> 6
                maxWidth > 1000.dp -> 5
                maxWidth > 760.dp -> 4
                else -> 2
            }
            // A cool, out-of-taste glow (the opposite of Home's lime): the page reads as "somewhere else".
            Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(lerp(c.panel, Color(0xFF3E6FD8), 0.22f), c.panel))))
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        IndexLabel(stringResource(Res.string.shell_explore_label))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(Res.string.shell_explore_title), style = Shell.type.display.copy(fontSize = Shell.type.display.fontSize * 1.6f, lineHeight = Shell.type.display.lineHeight * 1.6f), color = c.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(Res.string.shell_explore_subtitle), style = Shell.type.body, color = c.ink2)
                    }
                }
                when {
                    genres.isNotEmpty() -> item(key = "genres") { GenreGrid(genres, columns, home, rise) }
                    ownPending -> item(key = "genres_loading") { GenrePlaceholders(columns) }
                    ownFailed -> item(key = "failed") {
                        val offline = !s.error.isNullOrBlank()
                        ExploreFailed(if (offline) Res.string.shell_explore_offline else Res.string.shell_explore_failed) {
                            ShellExploreSource.source.load(true)
                        }
                    }
                }
                if (newReleases.isNotEmpty()) item(key = "new") { ExploreRow(stringResource(Res.string.shell_new_releases), newReleases, home) }
                if (trending.isNotEmpty()) item(key = "trending") { PopularPicks(trending, home, twoColumns = maxWidth > 1000.dp) }
                if (albumCharts.isNotEmpty()) item(key = "charts") { ExploreRow(stringResource(Res.string.shell_charts), albumCharts, home) }
                if (community.isNotEmpty()) item(key = "community") { ExploreRow(stringResource(Res.string.shell_community), community, home) }
            }
        }
    }
}

@Composable
private fun GenreGrid(genres: List<BrowseItem>, columns: Int, home: HomeSlot, rise: RiseIn) {
    val rows = remember(genres, columns) { genres.take(columns * 3).chunked(columns) }
    Column {
        Spacer(Modifier.height(28.dp))
        SectionHeader(stringResource(Res.string.shell_moods_genres))
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { row, tiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tiles.forEachIndexed { col, g ->
                        val id = "genre:${g.id}:${g.params}"
                        key(id) {
                            GenreTile(g, genreTint(row, col), { home.onItemClick(g) }, Modifier.weight(1f).riseIn(rise, id, row * columns + col))
                        }
                    }
                    repeat(columns - tiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Explore's own load ended with nothing to show: [message] and a Retry that loads again. */
@Composable
private fun ExploreFailed(message: StringResource, onRetry: () -> Unit) {
    val c = Shell.colors
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(message), style = Shell.type.body, color = c.ink2)
        // A button, not a chip (a chip reads as an unselected tab): a 48 dp target around the 32 dp pill.
        Box(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).shellPressable(onRetry, focusShape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.height(32.dp).clip(CircleShape).background(c.surface2).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.shell_explore_retry), style = Shell.type.label, color = c.ink)
            }
        }
    }
}

/** Two pulsing rows of genre-sized tiles while YouTube Music's Explore data is still on its way. */
@Composable
private fun GenrePlaceholders(columns: Int) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val label = stringResource(Res.string.shell_explore_loading)
    Column {
        Spacer(Modifier.height(28.dp))
        SectionHeader(stringResource(Res.string.shell_moods_genres))
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.padding(horizontal = 16.dp).semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(columns) { Box(Modifier.weight(1f).height(108.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2)) }
                }
            }
        }
    }
}

@Composable
private fun GenreTile(item: BrowseItem, base: Color, onClick: () -> Unit, modifier: Modifier) {
    val (hover, hovered) = rememberHover()
    val tint by animateColorAsState(if (hovered) lerp(base, Color.White, 0.08f) else base, ShellMotion.emphasized())
    val turn by animateFloatAsState(if (hovered) 18f else 25f, ShellMotion.emphasized())
    Box(modifier.height(108.dp).clip(RoundedCornerShape(8.dp)).background(tint).hoverable(hover).shellPressable(onClick, label = item.title).padding(14.dp)) {
        Text(item.title, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.align(Alignment.BottomEnd).offset(22.dp, 16.dp).rotate(turn).size(70.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0x400B0B0C)),
        )
    }
}

/** A row of cards; [items] must already be unique by id (they key the row). */
@Composable
private fun ExploreRow(title: String, items: List<YTItem>, home: HomeSlot) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val songs = remember(items) { items.filterIsInstance<SongItem>() }
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(10.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { it.id }) { item ->
            val (hover, hovered) = rememberHover()
            val open: () -> Unit = { if (item is SongItem) playback.play(item, songs) else home.onItemClick(item) }
            Column(
                Modifier.hoverable(hover)
                    .shellPressable(open, label = item.title, onLongClick = home.onItemLongClick?.let { f -> { f(item) } })
                    .cardHover(hovered).width(168.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    ShellArt(item.displayThumbnailUrl, Modifier.size(168.dp), shape = if (item is ArtistItem) CircleShape else ShellShapes.Art)
                    if (item is SongItem) HoverPlayButton(hovered, 44.dp, { playback.play(item, songs) }, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                Text(item.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitleOf(item)?.takeIf { it.isNotBlank() }?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * YouTube Music's trending songs as unranked picks. Turtle receives them flattened and deduplicated,
 * with no chart issue, market or reporting period, so their positions are not a chart: no rank
 * numbers, and a line under the header says where the songs come from. Ranked charts wait for a
 * verified source (roadmap, "Publish an honest chart experience"). Lime marks the song that is playing.
 */
@Composable
private fun PopularPicks(songs: List<SongItem>, home: HomeSlot, twoColumns: Boolean) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    Spacer(Modifier.height(28.dp))
    SectionHeader(stringResource(Res.string.shell_popular_picks))
    Text(
        stringResource(Res.string.shell_popular_picks_source), style = Shell.type.bodySmall, color = c.ink2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
    )
    Spacer(Modifier.height(8.dp))
    val columns = remember(songs, twoColumns) { if (twoColumns) songs.chunked((songs.size + 1) / 2) else listOf(songs) }
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        columns.forEach { column ->
            Column(Modifier.weight(1f)) {
                column.forEach { song ->
                    key(song.id) {
                        val playing = home.isPlaying.value && home.currentSongId.value == song.id
                        val (hover, hovered) = rememberHover()
                        val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
                                .shellPressable({ playback.play(song, songs) }, label = song.title, onLongClick = home.onItemLongClick?.let { f -> { f(song) } })
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // No position column: the play and "now playing" marks sit on the artwork.
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                ShellArt(song.displayThumbnailUrl, Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
                                if (playing || hovered) {
                                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x990B0B0C)), contentAlignment = Alignment.Center) {
                                        if (playing) Equalizer(height = 12.dp) else Glyph(Glyphs.Play, Color.White, 16.dp)
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = Shell.type.body, color = if (playing) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            song.duration?.takeIf { it > 0 }?.let { Text(formatTime(it * 1000), style = Shell.type.bodySmall, color = c.ink2) }
                        }
                    }
                }
            }
        }
    }
}
