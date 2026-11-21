package app.turtleplayer.ui.screens

import app.turtleplayer.ui.generated.resources.shell_now_playing_view
import androidx.compose.foundation.layout.PaddingValues
import com.metrolist.shared.turtle.TurtleUiSlots
import app.turtleplayer.ui.generated.resources.shell_np_sleep_timer
import app.turtleplayer.ui.generated.resources.shell_more
import app.turtleplayer.ui.generated.resources.shell_devices
import app.turtleplayer.ui.generated.resources.shell_lyrics
import app.turtleplayer.ui.generated.resources.shell_queue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_inf_tune
import app.turtleplayer.ui.generated.resources.shell_inf_blend_familiar
import app.turtleplayer.ui.generated.resources.shell_inf_blend_new
import app.turtleplayer.ui.generated.resources.shell_inf_resteered
import app.turtleplayer.ui.generated.resources.shell_albums
import app.turtleplayer.ui.generated.resources.shell_artists
import app.turtleplayer.ui.generated.resources.shell_collapse_library
import app.turtleplayer.ui.generated.resources.shell_expand_library
import app.turtleplayer.ui.generated.resources.shell_explore
import app.turtleplayer.ui.generated.resources.shell_hide_now_playing
import app.turtleplayer.ui.generated.resources.shell_infinity_meta
import app.turtleplayer.ui.generated.resources.shell_infinity_title
import app.turtleplayer.ui.generated.resources.shell_kind_album
import app.turtleplayer.ui.generated.resources.shell_kind_artist
import app.turtleplayer.ui.generated.resources.shell_kind_playlist
import app.turtleplayer.ui.generated.resources.shell_less_like_this
import app.turtleplayer.ui.generated.resources.shell_library_empty
import app.turtleplayer.ui.generated.resources.shell_liked_songs
import app.turtleplayer.ui.generated.resources.shell_meta_pair
import app.turtleplayer.ui.generated.resources.shell_next_in_queue
import app.turtleplayer.ui.generated.resources.shell_open_library
import app.turtleplayer.ui.generated.resources.shell_open_queue
import app.turtleplayer.ui.generated.resources.shell_playlists
import app.turtleplayer.ui.generated.resources.shell_queue_end
import app.turtleplayer.ui.generated.resources.shell_search_library
import app.turtleplayer.ui.generated.resources.shell_clear_search
import app.turtleplayer.ui.generated.resources.shell_your_library
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.turtle.DesktopLibrarySlot
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.turtle.NavigationSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Desktop layout state that outlives screens (panel open, library collapsed, filter). */
object ShellDesktop {
    var nowPlayingOpen by mutableStateOf(true)
    var libraryCollapsed by mutableStateOf(false)
    var libraryFilter by mutableStateOf(LibraryFilter.ALL)
    var libraryQuery by mutableStateOf("")

    /** True while Explore covers the page (our own page; any upstream navigation closes it). */
    var explore by mutableStateOf(false)

    /** The last Home Shell drew: Explore reads its YouTube Music data and opener. */
    internal var home by mutableStateOf<HomeSlot?>(null)

    /** The track desktop restores at launch; it is loaded into the player only on play. */
    internal var restoredSong by mutableStateOf<SongItem?>(null)

    /** What the right panel shows: Now Playing, or one of Shell's player surfaces. */
    var panel by mutableStateOf(DesktopPanel.NOW_PLAYING)

    /** Shows [mode] in the right panel, or closes the panel when it is already showing it. */
    fun toggle(mode: DesktopPanel) {
        if (nowPlayingOpen && panel == mode) {
            nowPlayingOpen = false
        } else {
            panel = mode
            nowPlayingOpen = true
        }
    }

    /** Opens [mode] in the right panel. */
    fun show(mode: DesktopPanel) {
        panel = mode
        nowPlayingOpen = true
    }
}

enum class LibraryFilter { ALL, PLAYLISTS, ARTISTS, ALBUMS }

enum class DesktopPanel { NOW_PLAYING, QUEUE, LYRICS, DEVICES, MORE, SLEEP }

// Matches upstream's content panel (StandardContentSurface), so all three panels share one radius.
private val PanelShape = RoundedCornerShape(16.dp)
private val SidebarWidth = 300.dp
private val SidebarCollapsedWidth = 72.dp
private val NowPlayingWidth = 340.dp

/**
 * Shell's desktop layout, familiar from Spotify: a black frame holding three rounded panels, Your
 * Library on the left, the page in the middle and the Now Playing view on the right. Upstream's
 * page (with its queue, lyrics and device sheets) is [NavigationSlot.content]; it draws its own 8 dp
 * gutters, so our panels add only the outer ones.
 */
@Composable
internal fun ShellDesktopLayout(slot: NavigationSlot, library: DesktopLibrarySlot) {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.rememberCalmState()
    val route = LocalNavigator.current.state.currentRoute
    LaunchedEffect(route) { ShellDesktop.explore = false }
    BoxWithConstraints(slot.modifier.fillMaxSize().background(c.frame)) {
        val collapsed = ShellDesktop.libraryCollapsed || maxWidth < 960.dp
        val sidebarWidth by animateDpAsState(if (collapsed) SidebarCollapsedWidth else SidebarWidth, ShellMotion.emphasized())
        val showNowPlaying = ShellDesktop.nowPlayingOpen && (state.currentSong ?: ShellDesktop.restoredSong) != null && maxWidth >= 1100.dp
        Row(Modifier.fillMaxSize()) {
            if (slot.navigationVisible) {
                DesktopSidebar(slot, library, collapsed, Modifier.width(sidebarWidth).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalShellGround provides c.panel) { SlotContent(slot) }
                // Explore sits over the page (inside upstream's 8 dp gutters) so the page underneath keeps its state.
                ExploreOverlay()
            }
            AnimatedVisibility(
                showNowPlaying,
                enter = expandHorizontally(ShellMotion.emphasized(), expandFrom = Alignment.Start) + fadeIn(ShellMotion.emphasized()),
                exit = shrinkHorizontally(ShellMotion.exit(), shrinkTowards = Alignment.Start) + fadeOut(ShellMotion.exit()),
            ) {
                RightPanel(library, Modifier.width(NowPlayingWidth).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp, end = 8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Your Library sidebar
// ---------------------------------------------------------------------------------------------

private fun navGlyph(key: String, selected: Boolean): ImageVector? = when (key.lowercase()) {
    "home" -> if (selected) Glyphs.HomeFilled else Glyphs.Home
    "search" -> Glyphs.Search
    "explore" -> Glyphs.Explore
    "history" -> Glyphs.History
    "stats", "statistics" -> Glyphs.Stats
    "together" -> Glyphs.Together
    else -> null
}

@Composable
private fun DesktopSidebar(slot: NavigationSlot, library: DesktopLibrarySlot, collapsed: Boolean, modifier: Modifier) {
    val c = Shell.colors
    val libraryIndex = slot.items.indexOfFirst { it.key.equals("library", ignoreCase = true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Destinations card: Home first, then upstream's other pages (History, Stats, Together).
        Column(Modifier.fillMaxWidth().clip(PanelShape).background(c.panel).padding(8.dp)) {
            val explore = ShellDesktop.explore
            slot.items.forEachIndexed { i, item ->
                if (i == libraryIndex) return@forEachIndexed
                val selected = !explore && i == slot.selectedIndex
                NavRow(navGlyph(item.key, selected) ?: item.icon, item.label, selected, collapsed) { ShellDesktop.explore = false; slot.onSelect(i) }
                if (item.key.equals("home", ignoreCase = true)) {
                    NavRow(Glyphs.Explore, stringResource(Res.string.shell_explore), explore, collapsed) { ShellDesktop.explore = true }
                }
            }
        }
        LibraryCard(
            library = library,
            collapsed = collapsed,
            librarySelected = libraryIndex >= 0 && libraryIndex == slot.selectedIndex,
            onOpenLibrary = { ShellDesktop.explore = false; if (libraryIndex >= 0) slot.onSelect(libraryIndex) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        slot.footer?.invoke()
    }
}

@Composable
private fun NavRow(glyph: ImageVector, label: String, selected: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val tint by animateColorAsState(if (selected || hovered) c.ink else c.ink2, ShellMotion.emphasized())
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(6.dp)).hoverable(source)
            .shellPressable(onClick, Role.Tab, label).padding(horizontal = if (collapsed) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        Glyph(glyph, tint, 24.dp)
        if (!collapsed) {
            Spacer(Modifier.width(16.dp))
            Text(label, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One row of Your Library: what it is, how to show it and what it does. */
private class LibraryRow(
    val key: String,
    val title: String,
    val meta: String,
    val art: String?,
    val round: Boolean,
    val badge: ImageVector? = null,
    val badgeTint: Color? = null,
    val badgeBackground: Color? = null,
    val playing: Boolean = false,
    val onClick: () -> Unit,
)

private fun YTItem.libraryFilter(): LibraryFilter? = when (this) {
    is PlaylistItem, is PodcastItem -> LibraryFilter.PLAYLISTS
    is ArtistItem -> LibraryFilter.ARTISTS
    is AlbumItem -> LibraryFilter.ALBUMS
    else -> null
}

@Composable
private fun LibraryCard(
    library: DesktopLibrarySlot,
    collapsed: Boolean,
    librarySelected: Boolean,
    onOpenLibrary: () -> Unit,
    modifier: Modifier,
) {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.rememberCalmState()
    val infinityTitle = stringResource(Res.string.shell_infinity_title)
    val likedTitle = stringResource(Res.string.shell_liked_songs)
    val playlistKind = stringResource(Res.string.shell_kind_playlist)
    val artistKind = stringResource(Res.string.shell_kind_artist)
    val albumKind = stringResource(Res.string.shell_kind_album)
    val infinityMeta = stringResource(Res.string.shell_infinity_meta)
    val pairFormat = stringResource(Res.string.shell_meta_pair, "\u0001", "\u0002")
    fun pair(a: String, b: String?) = if (b.isNullOrBlank()) a else pairFormat.replace("\u0001", a).replace("\u0002", b)

    val filter = ShellDesktop.libraryFilter
    val items = library.items()
    val activeQueue = ShellQueue.activeTitle(state)
    val rows = buildList {
        if (filter == LibraryFilter.ALL || filter == LibraryFilter.PLAYLISTS) {
            add(LibraryRow("liked", likedTitle, playlistKind, null, false, Glyphs.Heart, c.onAccent, c.accent) {
                library.open(PlaylistItem(id = BuiltinPlaylist.LIKED.id, title = likedTitle, thumbnail = null))
            })
            add(
                LibraryRow("infinity", infinityTitle, infinityMeta, null, false, Glyphs.Infinity, c.accent, c.surface2, playing = activeQueue == infinityTitle && state.isPlaying) {
                    // Like Spotify's sidebar, a click opens it: the Infinity sheet (genres, moods, steer, Start).
                    ShellInfinity.sheetOpen = true
                },
            )
        }
        items.asSequence()
            .filter { it.id != BuiltinPlaylist.LIKED.id }
            .filter { item -> item.libraryFilter()?.let { filter == LibraryFilter.ALL || it == filter } ?: false }
            .distinctBy { it.id }
            .forEach { item ->
                val meta = when (item) {
                    is ArtistItem -> artistKind
                    is AlbumItem -> pair(albumKind, item.artists.joinToString(", ") { it.name })
                    is PlaylistItem -> pair(playlistKind, item.author)
                    else -> playlistKind
                }
                val art = (item as? PlaylistItem)?.customThumbnail ?: item.thumbnail
                add(LibraryRow(item.id, item.title, meta, art, item is ArtistItem, playing = activeQueue == item.title && state.isPlaying) { library.open(item) })
            }
    }
    val query = ShellDesktop.libraryQuery.trim()
    val shown = if (query.isEmpty()) rows else rows.filter { it.title.contains(query, ignoreCase = true) || it.meta.contains(query, ignoreCase = true) }

    Column(modifier.clip(PanelShape).background(c.panel)) {
        // Header: the title collapses the panel (as in Spotify); the arrow opens the full Library page.
        Row(
            Modifier.fillMaxWidth().padding(start = if (collapsed) 0.dp else 8.dp, end = if (collapsed) 0.dp else 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            val (source, hovered) = rememberHover()
            val tint by animateColorAsState(if (hovered || librarySelected) c.ink else c.ink2, ShellMotion.emphasized())
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(6.dp)).hoverable(source)
                    .shellPressable({ ShellDesktop.libraryCollapsed = !collapsed }, label = stringResource(if (collapsed) Res.string.shell_expand_library else Res.string.shell_collapse_library))
                    .padding(horizontal = if (collapsed) 12.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Glyphs.Library, tint, 24.dp)
                if (!collapsed) {
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.shell_your_library), style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = tint)
                }
            }
            if (!collapsed) {
                Spacer(Modifier.weight(1f))
                SmallIconButton(Glyphs.ArrowRight, stringResource(Res.string.shell_open_library), onOpenLibrary)
            }
        }
        if (!collapsed) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    LibraryFilter.PLAYLISTS to Res.string.shell_playlists,
                    LibraryFilter.ARTISTS to Res.string.shell_artists,
                    LibraryFilter.ALBUMS to Res.string.shell_albums,
                ).forEach { (f, label) ->
                    ShellChip(stringResource(label), filter == f, { ShellDesktop.libraryFilter = if (filter == f) LibraryFilter.ALL else f })
                }
            }
            LibrarySearchField()
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
            items(shown, key = { it.key }) { row -> LibraryRowView(row, collapsed, Modifier.animateItem()) }
            if (!collapsed && query.isEmpty() && rows.none { it.key != "liked" && it.key != "infinity" }) {
                item(key = "empty") {
                    Text(stringResource(Res.string.shell_library_empty), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SmallIconButton(glyph: ImageVector, label: String, onClick: () -> Unit) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(bg).hoverable(source).shellPressable(onClick, label = label),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, if (hovered) c.ink else c.ink2, 18.dp) }
}

@Composable
private fun LibraryRowView(row: LibraryRow, collapsed: Boolean, modifier: Modifier) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(source)
            .shellPressable(row.onClick, label = row.title).padding(horizontal = if (collapsed) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        val shape = if (row.round) CircleShape else RoundedCornerShape(4.dp)
        if (row.badge != null) {
            Box(Modifier.size(48.dp).clip(shape).background(row.badgeBackground ?: c.surface2), contentAlignment = Alignment.Center) {
                Glyph(row.badge, row.badgeTint ?: c.ink, 24.dp)
            }
        } else {
            ShellArt(row.art, Modifier.size(48.dp), shape = shape)
        }
        if (!collapsed) {
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = Shell.type.body, color = if (row.playing) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.meta, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.playing) {
                Spacer(Modifier.width(8.dp))
                Equalizer(height = 12.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Now Playing view
// ---------------------------------------------------------------------------------------------

/** Desktop services for Shell's surfaces: upstream's desktop lyrics, queue and timer state, via hook #30. */
@Composable
private fun rememberDesktopServices(library: DesktopLibrarySlot): ShellPlayerServices? {
    val extras = TurtleUiSlots.desktopExtras ?: return null
    return remember(extras, library) {
        ShellPlayerServices(
            lyrics = extras.lyrics,
            lyricsLoading = extras.lyricsLoading,
            lyricsOffsetMs = extras.lyricsOffsetMs,
            ensureLyrics = { song -> if (extras.lyricsSongId() != song.id && !extras.lyricsLoading()) extras.loadLyrics(song) },
            removeQueueIndex = extras.removeQueueIndex,
            addToPlaylist = extras.addToPlaylist,
            share = null,
            download = null,
            isDownloaded = { false },
            openArtist = { id -> library.open(ArtistItem(id = id, title = "", thumbnail = null)) },
            openAlbum = { id -> library.open(AlbumItem(id = id, title = "", thumbnail = null, artists = emptyList())) },
            startSleepTimer = extras.startSleepTimer,
            cancelSleepTimer = extras.cancelSleepTimer,
            sleepTimerActive = extras.sleepTimerActive,
        )
    }
}

/**
 * The right panel: Spotify's Now Playing view, or one of Shell's own player surfaces (queue, lyrics,
 * devices, more, sleep timer) in its place. Upstream's drawers and expanded player are never shown.
 */
@Composable
private fun RightPanel(library: DesktopLibrarySlot, modifier: Modifier) {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by controller.rememberCalmState()
    val song = state.currentSong ?: ShellDesktop.restoredSong ?: return
    val services = rememberDesktopServices(library)
    val tint = artworkTint(song.displayThumbnailUrl, 0.14f, base = c.panel)
    val requested = ShellDesktop.panel
    val mode = if (services == null && requested != DesktopPanel.DEVICES) DesktopPanel.NOW_PLAYING else requested
    Column(modifier.clip(PanelShape).background(tint)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mode != DesktopPanel.NOW_PLAYING) {
                SmallIconButton(Glyphs.ArrowLeft, stringResource(Res.string.shell_now_playing_view)) { ShellDesktop.panel = DesktopPanel.NOW_PLAYING }
                Spacer(Modifier.width(4.dp))
            }
            val title = when (mode) {
                DesktopPanel.NOW_PLAYING -> ShellQueue.activeTitle(state) ?: song.title
                DesktopPanel.QUEUE -> stringResource(Res.string.shell_queue)
                DesktopPanel.LYRICS -> stringResource(Res.string.shell_lyrics)
                DesktopPanel.DEVICES -> stringResource(Res.string.shell_devices)
                DesktopPanel.MORE -> stringResource(Res.string.shell_more)
                DesktopPanel.SLEEP -> stringResource(Res.string.shell_np_sleep_timer)
            }
            Text(title, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (mode == DesktopPanel.NOW_PLAYING && services != null) {
                SmallIconButton(Glyphs.More, stringResource(Res.string.shell_more)) { ShellDesktop.panel = DesktopPanel.MORE }
            }
            SmallIconButton(Glyphs.Close, stringResource(Res.string.shell_hide_now_playing)) { ShellDesktop.nowPlayingOpen = false }
        }
        AnimatedContent(mode, transitionSpec = { fadeIn(ShellMotion.emphasized()) togetherWith fadeOut(ShellMotion.exit()) }, modifier = Modifier.weight(1f)) { m ->
            val pad = Modifier.fillMaxSize().padding(horizontal = 16.dp)
            if (m == DesktopPanel.NOW_PLAYING || (services == null && m != DesktopPanel.DEVICES)) {
                NowPlayingContent(song, state, tint)
            } else if (m == DesktopPanel.DEVICES) {
                Column(pad.verticalScroll(rememberScrollState())) { ShellDevices(state, controller) }
            } else if (services != null) {
                when (m) {
                    DesktopPanel.QUEUE -> ShellQueueList(state, controller, services, pad, PaddingValues(bottom = 16.dp))
                    DesktopPanel.LYRICS -> {
                        val live = rememberLivePosition(controller, state.isPlaying, song.id)
                        ShellLyricsFor(song, services, live, { controller.seekTo(it); live.ms = it }, pad, compact = true, contentPadding = PaddingValues(vertical = 16.dp))
                    }
                    DesktopPanel.MORE -> Column(pad.verticalScroll(rememberScrollState())) {
                        ShellMoreActions(song, controller, services, onShow = { s -> if (s == PlayerSurface.SLEEP) ShellDesktop.panel = DesktopPanel.SLEEP }, onDone = { ShellDesktop.panel = DesktopPanel.NOW_PLAYING })
                    }
                    DesktopPanel.SLEEP -> Column(pad.verticalScroll(rememberScrollState())) { ShellSleepTimer(services) { ShellDesktop.panel = DesktopPanel.NOW_PLAYING } }
                    else -> Unit
                }
            }
        }
    }
}

/** Spotify's Now Playing view: big artwork, like, our Discovery dial and what plays next. */
@Composable
private fun NowPlayingContent(song: SongItem, state: com.metrolist.shared.ui.playback.PlaybackUiState, tint: Color) {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedContent(
            song,
            transitionSpec = { (slideInHorizontally(ShellMotion.emphasized()) { it / 7 } + fadeIn(ShellMotion.emphasized())) togetherWith fadeOut(ShellMotion.exit()) },
            contentKey = { it.id },
        ) { s -> ShellArt(s.displayThumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(8.dp), sizePx = 720) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AnimatedContent(song, transitionSpec = { fadeIn(ShellMotion.emphasized()) togetherWith fadeOut(ShellMotion.exit()) }, contentKey = { it.id }) { s ->
                    Column {
                        Text(s.title, style = Shell.type.body.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s.artists.joinToString(", ") { it.name }, style = Shell.type.body, color = c.ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        InfinityWhyLabel(s.id, Modifier.padding(top = 6.dp))
                    }
                }
            }
            com.metrolist.shared.turtle.TurtleUiSlots.desktopExtras?.addToPlaylist?.let { add ->
                AddToPlaylistButton(song, add, c.ink2, size = 36.dp, iconSize = 20.dp)
            }
            if (ShellQueue.activeTitle(state) == stringResource(Res.string.shell_infinity_title)) {
                InfinityThumbs(song, controller, c.ink2, size = 36.dp, iconSize = 20.dp)
            } else {
                GlyphButton(Glyphs.Less, stringResource(Res.string.shell_less_like_this), {
                    ShellActions.lessLikeThis(controller, song)
                }, c.ink2, size = 36.dp, iconSize = 20.dp)
                LikeButton(song, SongLikeState.isLiked(song.id), size = 36.dp)
            }
        }
        SteerCard(tint, captions = false)
        UpNextCard(state.queue, state.currentQueueIndex) { controller.playQueueIndex(it) }
    }
}

@Composable
private fun UpNextCard(queue: List<SongItem>, current: Int, onPlay: (Int) -> Unit) {
    val c = Shell.colors
    val next = (current + 1 until queue.size).take(4).map { it to queue[it] }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.shell_next_in_queue), style = Shell.type.label.copy(fontWeight = FontWeight.Bold), color = c.ink, modifier = Modifier.weight(1f))
            Text(
                stringResource(Res.string.shell_open_queue), style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink2,
                modifier = Modifier.shellPressable({ ShellDesktop.show(DesktopPanel.QUEUE) }),
            )
        }
        if (next.isEmpty()) Text(stringResource(Res.string.shell_queue_end), style = Shell.type.bodySmall, color = c.ink2)
        next.forEach { (index, s) ->
            val (source, hovered) = rememberHover()
            val bg by animateColorAsState(if (hovered) c.ink.copy(alpha = 0.08f) else Color.Transparent, ShellMotion.emphasized())
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg).hoverable(source).shellPressable({ onPlay(index) }, label = s.title).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShellArt(s.displayThumbnailUrl, Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Spotify's "Search in Your Library": a quiet field that filters the list as you type. */
@Composable
private fun LibrarySearchField() {
    val c = Shell.colors
    val query = ShellDesktop.libraryQuery
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Search, c.ink2, 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(stringResource(Res.string.shell_search_library), style = Shell.type.bodySmall, color = c.ink2, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = { ShellDesktop.libraryQuery = it },
                singleLine = true,
                textStyle = Shell.type.bodySmall.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(24.dp).shellPressable({ ShellDesktop.libraryQuery = "" }, label = stringResource(Res.string.shell_clear_search)), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Close, c.ink2, 12.dp)
            }
        }
    }
}

/** Explore fades in over the page; a separate function so it is not scoped to the layout's Row. */
@Composable
private fun ExploreOverlay() {
    AnimatedVisibility(ShellDesktop.explore, enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit())) {
        ShellTheme { ShellExplorePage(ShellDesktop.home, Modifier.fillMaxSize().padding(8.dp).clip(PanelShape)) }
    }
}
