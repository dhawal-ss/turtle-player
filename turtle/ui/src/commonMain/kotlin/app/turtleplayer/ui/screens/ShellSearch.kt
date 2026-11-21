package app.turtleplayer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_albums
import app.turtleplayer.ui.generated.resources.shell_artists
import app.turtleplayer.ui.generated.resources.shell_browse_all
import app.turtleplayer.ui.generated.resources.shell_clear_search
import app.turtleplayer.ui.generated.resources.shell_filter_top
import app.turtleplayer.ui.generated.resources.shell_genre_1
import app.turtleplayer.ui.generated.resources.shell_genre_2
import app.turtleplayer.ui.generated.resources.shell_genre_3
import app.turtleplayer.ui.generated.resources.shell_genre_4
import app.turtleplayer.ui.generated.resources.shell_genre_5
import app.turtleplayer.ui.generated.resources.shell_genre_6
import app.turtleplayer.ui.generated.resources.shell_genre_7
import app.turtleplayer.ui.generated.resources.shell_genre_8
import app.turtleplayer.ui.generated.resources.shell_kind_album
import app.turtleplayer.ui.generated.resources.shell_kind_artist
import app.turtleplayer.ui.generated.resources.shell_kind_playlist
import app.turtleplayer.ui.generated.resources.shell_kind_song
import app.turtleplayer.ui.generated.resources.shell_playlists
import app.turtleplayer.ui.generated.resources.shell_recent_searches
import app.turtleplayer.ui.generated.resources.shell_search_hint
import app.turtleplayer.ui.generated.resources.shell_search_title
import app.turtleplayer.ui.generated.resources.shell_songs
import app.turtleplayer.ui.generated.resources.shell_top_result
import app.turtleplayer.ui.generated.resources.shell_vibe_1
import app.turtleplayer.ui.generated.resources.shell_vibe_2
import app.turtleplayer.ui.generated.resources.shell_vibe_3
import app.turtleplayer.ui.generated.resources.shell_vibe_4
import app.turtleplayer.ui.generated.resources.shell_videos
import app.turtleplayer.ui.generated.resources.shell_vibes_title
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.turtle.SearchSlot
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val GENRE_TINTS = listOf(0xFF3E6FD8, 0xFFC4577A, 0xFF8A4FD1, 0xFF2E7E9E, 0xFFB7792B, 0xFFC2412E, 0xFF3B3F8F, 0xFF2E9E7A).map { Color(it) }
private val GENRES = listOf(Res.string.shell_genre_1, Res.string.shell_genre_2, Res.string.shell_genre_3, Res.string.shell_genre_4, Res.string.shell_genre_5, Res.string.shell_genre_6, Res.string.shell_genre_7, Res.string.shell_genre_8)
private val VIBES = listOf(Res.string.shell_vibe_1, Res.string.shell_vibe_2, Res.string.shell_vibe_3, Res.string.shell_vibe_4)
private val FILTERS: List<Pair<SearchFilter?, StringResource>> = listOf(
    null to Res.string.shell_filter_top,
    SearchFilter.SONG to Res.string.shell_songs,
    SearchFilter.ARTIST to Res.string.shell_artists,
    SearchFilter.ALBUM to Res.string.shell_albums,
    SearchFilter.FEATURED_PLAYLIST to Res.string.shell_playlists,
)

/**
 * Shell's Search, in Spotify's familiar shape: a white search field, Browse all tiles before typing,
 * suggestions while typing, and after searching a top result followed by songs and card rows.
 * Results are upstream's search page, already re-ranked by our engine (HOOKS.md #20).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShellSearch(slot: SearchSlot) = ShellTheme {
    val c = Shell.colors
    val s = slot.uiState
    BoxWithConstraints(slot.modifier.fillMaxSize().background(LocalShellGround.current ?: c.ground)) {
        val wide = maxWidth > 840.dp
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        LaunchedEffect(nearEnd, s.selectedFilter) { if (nearEnd && s.selectedFilter != null && s.filteredResults?.continuation != null) slot.onLoadMore() }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = slot.contentPadding.calculateTopPadding() + 12.dp, bottom = slot.contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = if (ShellPlatform.desktop) 8.dp else 0.dp),
        ) {
            if (slot.showSearchBar) {
                item(key = "title") {
                    Text(stringResource(Res.string.shell_search_title), style = Shell.type.headline, color = c.ink, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "field") { SearchField(slot) }
            }

            when {
                !s.isQuerySubmitted && s.query.isBlank() -> {
                    if (s.searchHistory.isNotEmpty()) {
                        item(key = "recent") {
                            SectionTitle(stringResource(Res.string.shell_recent_searches))
                            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.searchHistory.take(8).forEach { q -> ShellChip(q, false, { slot.onHistoryItemClick(q) }) }
                            }
                        }
                    }
                    item(key = "vibes") {
                        SectionTitle(stringResource(Res.string.shell_vibes_title))
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VIBES.forEach { res -> val v = stringResource(res); ShellChip(v, false, { slot.onSearch(v) }) }
                        }
                    }
                    item(key = "browse") {
                        SectionTitle(stringResource(Res.string.shell_browse_all))
                        val cols = if (wide) 4 else 2
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GENRES.withIndex().chunked(cols).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { (i, res) -> val g = stringResource(res); BrowseTile(g, GENRE_TINTS[i], { slot.onSearch(g) }, Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                !s.isQuerySubmitted -> {
                    val suggestions = s.searchSuggestions
                    items(suggestions?.queries.orEmpty().take(6), key = { "q:$it" }) { q ->
                        Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().shellPressable({ slot.onSearch(q) }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(Glyphs.Search, c.ink2, 18.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(q, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(suggestions?.recommendedItems.orEmpty().take(5), key = { "r:${it.id}" }) { item -> Box(Modifier.widthIn(max = 760.dp)) { ResultRow(item, slot) } }
                }

                else -> {
                    item(key = "filters") {
                        Row(Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FILTERS.forEach { (f, res) -> ShellChip(stringResource(res), s.selectedFilter == f, { slot.onFilterSelected(f) }) }
                        }
                    }
                    if (s.selectedFilter != null) {
                        items(s.filteredResults?.items.orEmpty(), key = { "f:${it.id}" }) { item -> ResultRow(item, slot) }
                    } else {
                        val summaries = s.searchSummary?.summaries.orEmpty()
                        val top = summaries.firstOrNull { it.isTopResult }?.items?.firstOrNull()
                        val rest = summaries.filterNot { it.isTopResult }
                        val songsBeside = if (wide && top != null) rest.firstOrNull { sm -> sm.items.isNotEmpty() && sm.items.all { it is SongItem } } else null
                        if (top != null && songsBeside != null) {
                            // Spotify's desktop shape: the top result beside the first four songs.
                            item(key = "top") {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(0.42f)) {
                                        SectionTitle(stringResource(Res.string.shell_top_result))
                                        TopResultCard(top, slot, wide, Modifier.weight(1f))
                                    }
                                    Column(Modifier.weight(0.58f)) {
                                        SectionTitle(sectionTitle(songsBeside), start = 0.dp)
                                        songsBeside.items.take(4).forEach { item -> ResultRow(item, slot, compact = true) }
                                    }
                                }
                            }
                        } else if (top != null) {
                            item(key = "top") {
                                SectionTitle(stringResource(Res.string.shell_top_result))
                                TopResultCard(top, slot, wide)
                            }
                        }
                        rest.filter { it !== songsBeside }.forEach { summary ->
                            if (summary.items.all { it is SongItem }) {
                                item(key = "t:${summary.title}") { SectionTitle(sectionTitle(summary)) }
                                items(summary.items.take(6), key = { "s:${summary.title}:${it.id}" }) { item -> ResultRow(item, slot) }
                            } else {
                                item(key = "c:${summary.title}") {
                                    SectionTitle(sectionTitle(summary))
                                    CardRow(summary.items, slot, wide)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(slot: SearchSlot) {
    val c = Shell.colors
    val q = slot.uiState.query
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).background(c.ink).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Search, c.ground, 22.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (q.isEmpty()) Text(stringResource(Res.string.shell_search_hint), style = Shell.type.body, color = c.ground.copy(alpha = 0.6f))
            BasicTextField(
                value = q,
                onValueChange = slot.onQueryChange,
                singleLine = true,
                textStyle = Shell.type.body.copy(color = c.ground),
                cursorBrush = SolidColor(c.ground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { slot.onSearch(q) }),
                modifier = Modifier.fillMaxWidth().focusRequester(slot.focusRequester),
            )
        }
        if (q.isNotEmpty()) {
            Box(Modifier.size(36.dp).shellPressable({ slot.onQueryChange("") }, label = stringResource(Res.string.shell_clear_search)), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Close, c.ground, 16.dp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, start: Dp = 16.dp) {
    Text(text, style = Shell.type.label.copy(fontSize = Shell.type.body.fontSize * 1.15f, fontWeight = FontWeight.Bold), color = Shell.colors.ink, modifier = Modifier.padding(start, 22.dp, 16.dp, 10.dp))
}

@Composable
private fun sectionTitle(summary: SearchSummary): String = when (summary.title.lowercase()) {
    "songs" -> stringResource(Res.string.shell_songs)
    "artists" -> stringResource(Res.string.shell_artists)
    "albums" -> stringResource(Res.string.shell_albums)
    "playlists" -> stringResource(Res.string.shell_playlists)
    "videos" -> stringResource(Res.string.shell_videos)
    else -> summary.title
}

@Composable
private fun BrowseTile(name: String, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.height(96.dp).clip(RoundedCornerShape(8.dp)).background(tint).shellPressable(onClick).padding(12.dp)) {
        Text(name, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = Color.White)
        Box(Modifier.align(Alignment.BottomEnd).offset(20.dp, 14.dp).rotate(25f).size(62.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x590B0B0C)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultCard(item: YTItem, slot: SearchSlot, wide: Boolean, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface3 else c.surface2, ShellMotion.emphasized())
    Box(
        modifier.padding(start = 16.dp).then(if (wide) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().padding(end = 16.dp))
            .clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .combinedClickable(onClick = { slot.onItemClick(item) }, onLongClick = { slot.onItemLongClick(item) })
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShellArt(item.displayThumbnailUrl, Modifier.size(if (wide) 100.dp else 92.dp), shape = if (item is ArtistItem) CircleShape else ShellShapes.Art)
            Text(item.title, style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 1.3f), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(kindOf(item), subtitleOf(item)?.takeIf { it.isNotBlank() }).joinToString(" · "), style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item is SongItem) HoverPlayButton(hovered, 48.dp, { slot.onItemClick(item) }, Modifier.align(Alignment.BottomEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(item: YTItem, slot: SearchSlot, compact: Boolean = false) {
    val c = Shell.colors
    val playing = slot.isPlaying && slot.currentSongId == item.id
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
    val artSize = if (compact) 44.dp else 48.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 8.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
            .combinedClickable(onClick = { slot.onItemClick(item) }, onLongClick = { slot.onItemLongClick(item) })
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ShellArt(item.displayThumbnailUrl, Modifier.size(artSize), shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(4.dp))
            if (item is SongItem && hovered) {
                Box(Modifier.size(artSize).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Glyph(Glyphs.Play, Color.White, 20.dp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Shell.type.body, color = if (playing) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = if (compact) subtitleOf(item).orEmpty() else listOfNotNull(kindOf(item), subtitleOf(item)?.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(meta, style = Shell.type.bodySmall, color = if (hovered) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) {
            Spacer(Modifier.width(12.dp))
            Equalizer(height = 12.dp)
        }
        val seconds = (item as? SongItem)?.duration
        if (seconds != null && seconds > 0 && ShellPlatform.desktop) {
            Spacer(Modifier.width(16.dp))
            Text(formatTime(seconds * 1000), style = Shell.type.bodySmall, color = c.ink2)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(items: List<YTItem>, slot: SearchSlot, wide: Boolean) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp)) {
        items(items, key = { it.id }) { item ->
            val size = if (wide) 160.dp else 132.dp
            val (hover, hovered) = rememberHover()
            Column(
                Modifier.hoverable(hover).cardHover(hovered).width(size)
                    .combinedClickable(onClick = { slot.onItemClick(item) }, onLongClick = { slot.onItemLongClick(item) }),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    ShellArt(item.displayThumbnailUrl, Modifier.size(size), shape = if (item is ArtistItem) CircleShape else ShellShapes.Art)
                    if (item is SongItem) HoverPlayButton(hovered, 44.dp, { slot.onItemClick(item) }, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                Text(item.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                kindOf(item)?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun kindOf(item: YTItem): String? = when (item) {
    is ArtistItem -> stringResource(Res.string.shell_kind_artist)
    is AlbumItem -> stringResource(Res.string.shell_kind_album)
    is PlaylistItem -> stringResource(Res.string.shell_kind_playlist)
    is SongItem -> stringResource(Res.string.shell_kind_song)
    else -> null
}
