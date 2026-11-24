@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.turtleplayer.ui.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.SongCache
import app.turtleplayer.bridge.TurtleSearch
import app.turtleplayer.bridge.toTrackMeta
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import app.turtleplayer.ui.screens.ShellDesktopPlayerBar
import app.turtleplayer.ui.screens.ShellNavigation
import app.turtleplayer.ui.screens.ShellSearch
import app.turtleplayer.ui.screens.ShellSearchLocal
import app.turtleplayer.ui.theme.ShellThemes
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.turtle.NavigationSlot
import com.metrolist.shared.turtle.SearchSlot
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.viewmodels.SearchViewModel.SearchUiState
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression case D (owner's Windows walkthrough, roadmap row 3): "lofi loft", where YouTube's top
 * item is the playlist Lofi Loft and a same-name artist exists. Shell's real ShellSearch is drawn as
 * the Search page of the real ShellDesktopLayout, fed through upstream's own SearchSlot with fake
 * search state (no network, no view model). The unfiltered page is the bridge's real re-ranking
 * (TurtleSearch.rerank, HOOKS.md #20) of YouTube's page, as the app shows it.
 *
 * The local stage ("From your library") is fed through ShellSearchLocal with the bridge's real local
 * search over a fake library (the app reaches it through TurtleBridge.searchLocal and the runtime).
 * See [localFirstBeforeTheNetwork].
 */
class ShellSearchShotTest {
    @BeforeTest
    fun reset() = resetShellState()

    @AfterTest
    fun cleanUp() = resetShellState()

    private fun song(id: String, title: String, artist: String) =
        SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 180)

    private val lofiLoft = PlaylistItem(id = "VLlofiloft", title = "Lofi Loft", thumbnail = null, author = "Lofi Records")
    private val sameNameArtist = ArtistItem(id = "UClofiloft", title = "Lofi Loft", thumbnail = null)

    /** YouTube's page for "lofi loft": the playlist on top, songs, the same-name artist, community playlists. */
    private val youTubePage =
        SearchSummaryPage(
            listOf(
                SearchSummary("", listOf(lofiLoft), isTopResult = true),
                SearchSummary("Songs", listOf(song("lf1", "Lofi Loft Sessions", "Chill Beats"), song("lf2", "Rainy Loft", "Lofi Loft"))),
                SearchSummary("Artists", listOf(sameNameArtist)),
                SearchSummary("Community playlists", listOf(PlaylistItem(id = "VLvibes", title = "Lofi Loft vibes", thumbnail = null, author = "Someone"))),
            ),
        )

    /** YouTube's answer for the Playlists chip (FEATURED_PLAYLIST), in YouTube's order. */
    private val playlistsFilter =
        SearchResultPage(
            listOf(
                lofiLoft,
                PlaylistItem(id = "VLradio", title = "Lofi Loft Radio", thumbnail = null, author = "YouTube Music"),
                PlaylistItem(id = "VLstudy", title = "Study in the Loft", thumbnail = null, author = "Lofi Girl"),
            ),
        )

    /** What the app shows for the unfiltered search: YouTube's page after our engine re-ranked it. */
    private fun rerankedPage(): SearchSummaryPage =
        runBlocking { TurtleSearch(LocalLibrary(), InMemoryPlayEventStore(), SongCache()).rerank("lofi loft", youTubePage) } ?: youTubePage

    private fun submitted(page: SearchSummaryPage?, searching: Boolean = false) =
        SearchUiState(
            query = "lofi loft",
            isQuerySubmitted = true,
            hasSearched = !searching,
            isSearching = searching,
            unfilteredSearchSummary = page,
            searchSummary = page,
        )

    @Test
    fun lofiLoftShowsThePlaylistAsTopResultAndTheArtistUnderArtists() = shellShots(1400.dp, 1500.dp) {
        val search = FakeSearch(submitted(rerankedPage()))
        val frame = SearchFrame(search)
        render { frame.Content() }
        onUi { frame.shell.navigator.navigate(Screen.Search) }

        text("Top result").assertIsDisplayed()
        val top = ui.onNode(hasText("Lofi Loft") and hasText("Playlist · Lofi Records"))
        top.assertIsDisplayed()
        val artistsTitle = ui.onNode(hasText("Artists") and !hasRole(Role.Tab))
        artistsTitle.assertIsDisplayed()
        val artistCard = ui.onNode(hasText("Lofi Loft") and hasText("Artist"))
        artistCard.assertIsDisplayed()

        val topBox = top.bounds()
        assertTrue(topBox.top < artistsTitle.bounds().top, "the top result sits above the Artists section")
        assertTrue(artistsTitle.bounds().top < artistCard.bounds().top, "the same-name artist is listed under Artists")
        // Only one Lofi Loft playlist card and one artist card: the artist did not take the top slot.
        ui.onAllNodes(hasText("Lofi Loft") and hasText("Artist")).assertCountEquals(1)

        val shot = shot("search-lofi-loft")
        assertTrue(shot.distinctColors() > 50, "a real render, not a blank frame")

        // The top card opens the playlist, not the artist.
        click(top)
        assertEquals(listOf<YTItem>(lofiLoft), search.clicked.toList())
    }

    @Test
    fun playlistsFilterListsThePlaylistFirst() = shellShots(1400.dp, 1500.dp) {
        val search = FakeSearch(submitted(rerankedPage()), filtered = mapOf(SearchFilter.FEATURED_PLAYLIST to playlistsFilter))
        val frame = SearchFrame(search)
        render { frame.Content() }
        onUi { frame.shell.navigator.navigate(Screen.Search) }

        // The search filter chip, not Your Library's Playlists chip in the sidebar.
        val chip = ui.onNode(hasText("Playlists") and hasRole(Role.Tab) and hasAnySibling(hasText("Top") and hasRole(Role.Tab)))
        chip.assertIsNotSelected()
        click(chip)
        assertEquals(SearchFilter.FEATURED_PLAYLIST, search.state.selectedFilter)
        chip.assertIsSelected()
        ui.onNode(hasText("Top") and hasRole(Role.Tab)).assertIsNotSelected()

        // Filtered rows replace the summary: no top result card, no "From your library" note.
        ui.onAllNodes(hasText("Top result")).assertCountEquals(0)
        ui.onAllNodes(hasText("From your library")).assertCountEquals(0)

        val first = ui.onNode(hasText("Lofi Loft") and hasText("Playlist · Lofi Records"))
        val second = ui.onNode(hasText("Lofi Loft Radio"))
        val third = ui.onNode(hasText("Study in the Loft"))
        first.assertIsDisplayed()
        assertTrue(first.bounds().top < second.bounds().top, "Lofi Loft is the first playlist row")
        assertTrue(second.bounds().top < third.bounds().top, "YouTube's order is kept below it")
        // The same-name artist is not a playlist, so it is not in this list at all.
        ui.onAllNodes(hasText("Lofi Loft") and hasText("Artist")).assertCountEquals(0)

        val shot = shot("search-playlists-filter")
        assertTrue(shot.distinctColors() > 50, "a real render, not a blank frame")

        click(first)
        assertEquals(listOf<YTItem>(lofiLoft), search.clicked.toList())
    }

    /**
     * Before YouTube answers: the query is submitted and nothing online has landed. The local stage
     * fills the page with the listener's own songs under "From your library": here the bridge's real
     * TurtleSearch.local over a one-song library, fed through ShellSearchLocal (in the app it reaches the
     * same engine through TurtleBridge.searchLocal and the runtime). When YouTube's page lands it
     * replaces the local one and the note goes.
     */
    @Test
    fun localFirstBeforeTheNetwork() = shellShots(1400.dp, 900.dp) {
        val mine = song("lib-lofi", "Lofi Loft Sessions", "Chill Beats")
        val engine = runBlocking {
            val library = LocalLibrary()
            library.upsert(mine.toTrackMeta(), setOf(LocalSource.LIBRARY))
            TurtleSearch(library, InMemoryPlayEventStore(), SongCache().apply { replaceLibrary(listOf(mine)) })
        }
        assertNotNull(runBlocking { engine.local("lofi loft") }, "the bridge's local stage finds the library song")
        val asked = mutableListOf<String>()
        ShellSearchLocal.search = { q -> asked += q; engine.local(q) }

        val search = FakeSearch(submitted(page = null, searching = true))
        val frame = SearchFrame(search)
        render { frame.Content() }
        onUi { frame.shell.navigator.navigate(Screen.Search) }

        // The engine may hop threads, which waitForIdle does not track.
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodes(hasText("From your library")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("lofi loft"), asked, "Search did not ask the local stage for the submitted query")
        text("From your library").assertIsDisplayed()
        ui.onNode(hasText("Lofi Loft Sessions", substring = true)).assertIsDisplayed()
        // Local results stand in for the skeleton while YouTube is still searching.
        ui.onAllNodes(hasContentDescription("Searching")).assertCountEquals(0)
        val shot = shot("search-local-first")
        assertTrue(shot.distinctColors() > 50, "a real render, not a blank frame")
        assertNull(search.state.searchSummary)

        // YouTube answers: its (re-ranked) page replaces the local one and the note goes.
        onUi { search.state = submitted(rerankedPage()) }
        ui.onAllNodes(hasText("From your library")).assertCountEquals(0)
        text("Top result").assertIsDisplayed()
        ui.onNode(hasText("Lofi Loft") and hasText("Playlist · Lofi Records")).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

/**
 * Upstream's search state and callbacks as SearchScreen hands them to Shell (HOOKS.md #23), without
 * the view model: [state] is what the page shows, the Playlists chip and friends swap in [filtered].
 */
class FakeSearch(initial: SearchUiState, private val filtered: Map<SearchFilter, SearchResultPage> = emptyMap()) {
    var state by mutableStateOf(initial)
    val clicked = mutableListOf<YTItem>()
    private val focus = FocusRequester()

    /** Desktop's slot: the title bar owns the field, so no search bar on the page. */
    fun slot(): SearchSlot =
        SearchSlot(
            uiState = state,
            onQueryChange = { state = state.copy(query = it) },
            onSearch = { state = state.copy(query = it, isQuerySubmitted = true) },
            onBack = {},
            onItemClick = { clicked += it },
            onItemLongClick = {},
            onHistoryItemClick = {},
            onRemoveHistoryItem = {},
            onFilterSelected = { f -> state = state.copy(selectedFilter = f, filteredResults = f?.let { filtered[it] }) },
            onLoadMore = {},
            onArtistClick = null,
            onAlbumClick = null,
            currentSongId = null,
            isPlaying = false,
            showSearchBar = false,
            focusRequester = focus,
            modifier = Modifier,
            contentPadding = PaddingValues(),
        )
}

/** [ShellDesktopFixture]'s frame, with Shell's real Search as the page while the route is Search. */
class SearchFrame(val search: FakeSearch, val shell: ShellDesktopFixture = ShellDesktopFixture()) {
    @Composable
    fun Content() {
        remember {
            TurtleUiSlots.desktopLibrary = shell.library.slot
            TurtleUiSlots.desktopExtras = shell.extras?.extras
        }
        CompositionLocalProvider(LocalPlaybackController provides shell.controller, LocalNavigator provides shell.navigator) {
            Column(Modifier.fillMaxSize().background(ShellThemes.active.frame)) {
                val base = fakeNavigationSlot(shell.navigator)
                val slot = NavigationSlot(
                    items = base.items,
                    selectedIndex = base.selectedIndex,
                    onSelect = base.onSelect,
                    navigationVisible = base.navigationVisible,
                    bottomBar = base.bottomBar,
                    floating = base.floating,
                    onOccupiedHeightChanged = base.onOccupiedHeightChanged,
                    header = base.header,
                    footer = base.footer,
                    modifier = base.modifier,
                    content = {
                        if (shell.navigator.state.currentRoute == Screen.Search) UpstreamPageSurface { ShellSearch(search.slot()) } else FakePage(shell.navigator)
                    },
                )
                Box(Modifier.weight(1f)) { ShellNavigation(slot) }
                ShellDesktopPlayerBar(fakePlayerBarSlot())
            }
        }
    }
}
