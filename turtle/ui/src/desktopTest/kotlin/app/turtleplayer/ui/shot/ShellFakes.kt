package app.turtleplayer.ui.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.turtleplayer.bridge.ExploreData
import app.turtleplayer.bridge.ExploreState
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.screens.ExploreSource
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.turtle.DesktopLibrarySlot
import com.metrolist.shared.turtle.DesktopPlayerBarSlot
import com.metrolist.shared.turtle.DesktopPlayerExtras
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.turtle.NavigationSlot
import com.metrolist.shared.turtle.NavigationSlotItem
import com.metrolist.shared.ui.navigation.NavigationState
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.ui.screens.HomeContentText
import com.metrolist.shared.ui.screens.HomeShelfCache
import com.metrolist.shared.viewmodels.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Fakes for rendering Shell's desktop frame without upstream's app: no Koin, no database, no network.
 * Art URLs are always null, so coil never loads anything.
 */

/** A song with no artwork (coil never fetches). */
fun fakeSong(i: Int, title: String = "Song $i", artist: String = "Artist ${i % 7}"): SongItem =
    SongItem(
        id = "song-$i",
        title = title,
        thumbnail = null,
        artists = listOf(ArtistTiny(id = "artist-${i % 7}", name = artist)),
        album = AlbumTiny(id = "album-${i % 5}", name = "Album ${i % 5}"),
        duration = 180L + i,
    )

/** [count] fake songs, song-0 .. song-(count-1). */
fun fakeQueue(count: Int = 30): List<SongItem> = List(count) { fakeSong(it) }

/**
 * Upstream's [PlaybackController] backed by a [MutableStateFlow]. Nothing plays: transport calls only
 * move the state, so the UI reacts as it would. Paused by default, so no position polling loop runs.
 * Set [state] directly for anything else (shuffle, queue title, loading).
 */
class FakePlaybackController(
    queue: List<SongItem> = fakeQueue(),
    index: Int = 0,
    playing: Boolean = false,
) : PlaybackController {
    val mutable = MutableStateFlow(
        PlaybackUiState(
            currentSong = queue.getOrNull(index),
            isPlaying = playing,
            durationMs = 200_000,
            queue = queue,
            currentQueueIndex = if (queue.isEmpty()) -1 else index,
        ),
    )
    override val state: StateFlow<PlaybackUiState> get() = mutable

    /** Every call the UI made, by name, for tests that check a click reached the player. */
    val calls = mutableListOf<String>()

    fun update(transform: (PlaybackUiState) -> PlaybackUiState) {
        mutable.value = transform(mutable.value)
    }

    /** No current song: the right panel and the player bar's song area go away. */
    fun clear() = update { PlaybackUiState() }

    override fun play(song: SongItem, queue: List<SongItem>, songsOnly: Boolean) {
        calls += "play:${song.id}"
        update { it.copy(currentSong = song, queue = queue, currentQueueIndex = queue.indexOf(song), isPlaying = true) }
    }

    override fun shuffleQueue(songs: List<SongItem>, queueTitle: String?, songsOnly: Boolean) {
        calls += "shuffleQueue"
        val shuffled = songs.shuffled(kotlin.random.Random(1))
        update { it.copy(currentSong = shuffled.firstOrNull(), queue = shuffled, currentQueueIndex = 0, queueTitle = queueTitle) }
    }

    override fun togglePlayPause() {
        calls += "togglePlayPause"
        update { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
        update { it.copy(positionMs = positionMs) }
    }

    override fun skipToNext() {
        calls += "skipToNext"
        update { s -> s.queue.getOrNull(s.currentQueueIndex + 1)?.let { s.copy(currentSong = it, currentQueueIndex = s.currentQueueIndex + 1) } ?: s }
    }

    override fun skipToPrevious() {
        calls += "skipToPrevious"
        update { s -> s.queue.getOrNull(s.currentQueueIndex - 1)?.let { s.copy(currentSong = it, currentQueueIndex = s.currentQueueIndex - 1) } ?: s }
    }

    override fun setPlaybackSpeed(speed: Float) {
        calls += "setPlaybackSpeed:$speed"
    }

    override fun startRadio(song: SongItem) {
        calls += "startRadio:${song.id}"
    }

    override fun playNext(songs: List<SongItem>) {
        calls += "playNext:${songs.size}"
        update { s -> s.copy(queue = s.queue.toMutableList().apply { addAll((s.currentQueueIndex + 1).coerceIn(0, size), songs) }) }
    }

    override fun addToQueue(songs: List<SongItem>) {
        calls += "addToQueue:${songs.size}"
        update { it.copy(queue = it.queue + songs) }
    }

    override fun reorderQueue(order: List<Int>): Boolean {
        calls += "reorderQueue"
        update { s -> s.copy(queue = order.map { s.queue[it] }) }
        return true
    }
}

/** The top-level routes the fake sidebar shows, in order. */
val FakeTopRoutes: List<Screen> = listOf(Screen.Home, Screen.Search, Screen.Library)

/**
 * Upstream's real [Navigator] over plain back stacks (no saved state): Home, Search and Library as
 * top-level routes, Home first. Push with `navigator.navigate(Screen.Playlist("id"))`, pop with
 * `navigator.goBack()`, switch tabs with `navigator.navigate(Screen.Search)`.
 */
fun fakeNavigator(): Navigator {
    val stacks: Map<Screen, NavBackStack<NavKey>> = FakeTopRoutes.associateWith { NavBackStack<NavKey>(it) }
    return Navigator(NavigationState(startRoute = Screen.Home, topLevelRoute = mutableStateOf(Screen.Home), backStacks = stacks))
}

/** A readable name for a route, shown as the fake page's title. */
fun routeTitle(route: Screen): String = when (route) {
    Screen.Home -> "Home"
    Screen.Search -> "Search"
    Screen.Library -> "Library"
    is Screen.Playlist -> "Playlist ${route.id}"
    is Screen.Album -> "Album ${route.id}"
    is Screen.Artist -> "Artist ${route.id}"
    is Screen.Browse -> "Category ${route.title ?: route.browseId}"
    else -> route::class.simpleName ?: "Page"
}

/**
 * What upstream's desktop app wraps every page in (AppLayouts.kt, StandardContentSurface): 8 dp
 * gutters and a card with 16 dp corners. Shell's real pages (Home, Search) drawn as the page go in
 * it, so the centre panel is rounded and clear of the player bar as in the app.
 */
@Composable
fun UpstreamPageSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(16.dp))) { content() }
}

/** Test tag on the fake page's scrolling column (scroll it with performScrollToIndex / swipe). */
const val FAKE_PAGE_TAG = "fake-page"

/**
 * Upstream's page stand-in: a title for the current route and 40 rows in a scrolling column, on a
 * panel-coloured rounded card inside upstream's 8 dp gutters.
 */
@Composable
fun FakePage(navigator: Navigator) {
    val route = navigator.state.currentRoute
    Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF121212))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(FAKE_PAGE_TAG).padding(24.dp)) {
            Text(routeTitle(route), color = Color.White, fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
            repeat(40) { i ->
                Text("${routeTitle(route)} row $i", color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
    }
}

/**
 * The [NavigationSlot] upstream's desktop app hands Shell, driven by [navigator]: Home, Search and
 * Library in the sidebar (Shell adds Explore under Home), and [FakePage] as the page.
 */
@Composable
fun fakeNavigationSlot(navigator: Navigator, navigationVisible: Boolean = true): NavigationSlot {
    val items = listOf(
        NavigationSlotItem("home", "Home", Glyphs.Home),
        NavigationSlotItem("search", "Search", Glyphs.Search),
        NavigationSlotItem("library", "Library", Glyphs.Library),
    )
    val selected = FakeTopRoutes.indexOf(navigator.state.topLevelRoute)
    return NavigationSlot(
        items = items,
        selectedIndex = selected,
        onSelect = { i -> navigator.navigate(FakeTopRoutes[i]) },
        navigationVisible = navigationVisible,
        bottomBar = false,
        floating = false,
        onOccupiedHeightChanged = {},
        header = {},
        footer = null,
        modifier = Modifier,
        content = { FakePage(navigator) },
    )
}

/** Items in Your Library: playlists, artists and albums with no artwork. */
fun fakeLibraryItems(): List<YTItem> = listOf(
    PlaylistItem(id = "pl-road", title = "Road Trip", thumbnail = null, author = "You"),
    PlaylistItem(id = "pl-focus", title = "Deep Focus", thumbnail = null, author = "You"),
    ArtistItem(id = "artist-1", title = "Artist 1", thumbnail = null),
    ArtistItem(id = "artist-2", title = "Artist 2", thumbnail = null),
    AlbumItem(id = "album-1", title = "Album 1", thumbnail = null, artists = listOf(ArtistTiny(id = "artist-1", name = "Artist 1"))),
)

/** The library slot; [opened] records every item the sidebar opened. */
class FakeLibrary(items: List<YTItem> = fakeLibraryItems()) {
    val opened = mutableListOf<YTItem>()
    val slot = DesktopLibrarySlot(items = { items }, open = { opened += it })
}

/**
 * Desktop player services (hook #30). Without them Shell shows only Now Playing and Devices in the
 * right panel; with them Queue, Lyrics, More and Sleep render too. Lyrics are static lines.
 */
class FakeExtras {
    val removed = mutableListOf<Int>()
    var sleepActive = false
    val extras = DesktopPlayerExtras(
        lyrics = { List(24) { LyricsEntry(time = it * 4_000L, text = "Lyric line $it") } },
        lyricsLoading = { false },
        lyricsOffsetMs = { 0 },
        lyricsSongId = { null },
        loadLyrics = {},
        removeQueueIndex = { removed += it },
        startSleepTimer = { _, _ -> sleepActive = true },
        cancelSleepTimer = { sleepActive = false },
        sleepTimerActive = { sleepActive },
        addToPlaylist = {},
    )
}

/** Upstream's player bar slot with inert actions. */
fun fakePlayerBarSlot(restoredSong: SongItem? = null): DesktopPlayerBarSlot = DesktopPlayerBarSlot(
    onExpand = {},
    onToggleQueue = {},
    onToggleLyrics = {},
    onToggleDevices = {},
    onToggleFullscreen = {},
    volume = 0.6f,
    onVolumeChange = {},
    restoredSong = restoredSong,
)

/**
 * Explore's own load without the network (set `ShellExploreSource.source` before rendering). With no
 * [answer] it stays idle, as if the load never finished. Otherwise each load publishes [answer]'s
 * data, or a failure when it returns null or empty data (the old data, if any, stays). [loads]
 * records each request's force flag (true: Retry).
 */
internal class FakeExploreSource(var answer: (() -> ExploreData?)? = null) : ExploreSource {
    val mutable = MutableStateFlow(ExploreState())
    override val state: StateFlow<ExploreState> get() = mutable
    val loads = mutableListOf<Boolean>()

    override fun load(force: Boolean) {
        loads += force
        val respond = answer ?: return
        val data = respond()
        mutable.value =
            if (data == null || data.isEmpty) mutable.value.copy(loading = false, failed = true) else ExploreState(data = data, loadedAtMs = 0L)
    }
}

/**
 * A Home slot Explore can read (set `ShellBrowse.home = fakeHomeSlot()` before opening Explore):
 * moods and genres, new releases, trending and album charts, all without artwork. [opened] records
 * what Explore opened through Home's opener; [open] then runs, as the app's opener would (push a route).
 */
class FakeHome(
    genres: Int = 12,
    state: HomeViewModel.HomeUiState = HomeViewModel.HomeUiState(
        moodsAndGenres = List(genres) { HomeViewModel.Genre(name = "Genre $it", id = "genre-$it", params = "p$it") },
        newReleases = List(8) { AlbumItem(id = "new-$it", title = "New Release $it", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $it"))) },
        trending = List(10) { fakeSong(100 + it, title = "Trending $it") },
        albumCharts = List(6) { AlbumItem(id = "chart-$it", title = "Chart Album $it", thumbnail = null, artists = listOf(ArtistTiny(null, "Charting $it"))) },
    ),
    private val open: (YTItem) -> Unit = {},
) {
    val opened = mutableListOf<YTItem>()
    val slot = HomeSlot(
        uiState = state,
        text = HomeContentText(
            moodsAndGenres = "Moods and genres", speedDial = "Speed dial", newReleases = "New releases", similarTo = "Similar to",
            podcastsForYou = "Podcasts for you", charts = "Charts", albumCharts = "Album charts", editorialPlaylists = "Playlists",
            recapPlaylists = "Recap", shuffle = "Shuffle", playAll = "Play all", keepListening = "Keep listening", quickPicks = "Quick picks",
            forgottenFavorites = "Forgotten favorites", loading = "Loading", tryAgain = "Try again", homeUnavailable = "Home unavailable",
        ),
        onItemClick = { opened += it; open(it) },
        onItemLongClick = null,
        onPlaySongs = null,
        onShuffleSongs = null,
        onArtistClick = null,
        onAlbumClick = null,
        onPlayAlbum = null,
        onLoadMore = null,
        currentSongId = mutableStateOf(null),
        isPlaying = mutableStateOf(false),
        modifier = Modifier,
        listState = androidx.compose.foundation.lazy.LazyListState(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(),
        shelfCache = HomeShelfCache(),
    )
}
