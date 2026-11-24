@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.turtleplayer.ui.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.HomeYouTubeRows
import app.turtleplayer.bridge.MixAbout
import app.turtleplayer.bridge.PlaylistAddWatcher
import app.turtleplayer.bridge.SongCache
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.ShelfCadence
import app.turtleplayer.taste.ShelfKind
import app.turtleplayer.taste.TasteEngine
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.screens.ShellDesktopPlayerBar
import app.turtleplayer.ui.screens.ShellHome
import app.turtleplayer.ui.screens.ShellHomeRows
import app.turtleplayer.ui.screens.MixSnapshot
import app.turtleplayer.ui.screens.ShellMixDetails
import app.turtleplayer.ui.screens.ShellNavigation
import app.turtleplayer.ui.theme.ShellThemes
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.turtle.DesktopPlayerExtras
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.turtle.NavigationSlot
import com.metrolist.shared.turtle.NavigationSlotItem
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfHeaderAction
import com.metrolist.shared.ui.screens.HomeShelfStyle
import com.metrolist.shared.ui.screens.homeShelvesOverride
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression case C: a Turtle mix's card opens its details page from the artwork or the title and
 * plays only from its own Play button; the page shows the songs in the snapshot's order with Play and
 * a separate Save (no Shuffle), any row plays the list as shown from that row, Save hands upstream's
 * playlist picker every song in that order after announcing a bulk save, and Escape goes back to Home.
 *
 * Home is Shell's real ShellHome inside the real ShellDesktopLayout; the mix reaches it the way the
 * bridge's shelves do, through upstream's homeShelvesOverride hook (#21).
 */
class ShellMixDetailsShotTest {
    private var koinStarted = false

    @BeforeTest
    fun reset() {
        resetShellState()
        homeShelvesOverride = null
    }

    @AfterTest
    fun cleanUp() {
        if (koinStarted) stopKoin()
        koinStarted = false
        homeShelvesOverride = null
        resetShellState()
    }

    @Test
    fun mixCardOpensFromArtAndTitleAndPlaysOnlyFromItsPlayButton() = shellShots(1400.dp, 900.dp) {
        val fixture = MixHomeFixture(listOf(mixShelf()))
        render { fixture.Desktop() }
        settleHomeGrid()
        text(MADE_FOR_YOU).assertIsDisplayed()
        val card = label(MIX_TITLE)
        card.assertIsDisplayed()

        // No hover, no focus: no Play button on the desktop card.
        ui.onAllNodes(cardPlay(MIX_TITLE)).assertCountEquals(0)

        // Hover shows Play.
        card.performMouseInput { moveTo(center) }
        idle()
        ui.onNode(cardPlay(MIX_TITLE)).assertIsDisplayed()
        // Hover must not hide the end of the name under Play ("Daily Mi" with "x 1" under the button).
        assertTitleClearOfPlay(MIX_TITLE, "desktop hover")
        val hover = shot("mix-card-hover")
        assertTrue(hover.distinctColors() > 20, "mix-card-hover looks blank")

        // Play plays the mix from its first song in its order, and does not open the page.
        ui.onNode(cardPlay(MIX_TITLE)).performMouseInput { this.click(center) }
        idle()
        assertNull(ShellMixDetails.current, "the card's Play opened the details page")
        assertEquals(listOf(QueuePlay(MIX_SONGS, 0, MIX_TITLE)), fixture.controller.queuePlays, "the card's Play did not play the mix from the top")
        fixture.controller.queuePlays.clear()
        fixture.fake.calls.clear()

        // Keyboard focus on the card also shows Play (hover gone first).
        parkPointer()
        ui.onAllNodes(cardPlay(MIX_TITLE)).assertCountEquals(0)
        card.requestFocus()
        idle()
        ui.onNode(cardPlay(MIX_TITLE)).assertIsDisplayed()
        parkPointer()

        // The artwork opens the page and plays nothing.
        val width = card.fetchSemanticsNode().boundsInRoot.width
        card.performMouseInput { this.click(Offset(width / 2f, width * 0.35f)) }
        idle()
        val opened = assertNotNull(ShellMixDetails.current, "clicking the artwork did not open the mix")
        assertEquals(MIX_TITLE, opened.title)
        assertEquals(emptyList(), fixture.controller.queuePlays, "opening from the artwork started playback")
        assertEquals(emptyList(), fixture.fake.calls, "opening from the artwork reached the player")

        // Back arrow returns to Home.
        clickLabel("Back")
        assertNull(ShellMixDetails.current, "Back did not close the mix")
        label(MIX_TITLE).assertIsDisplayed()

        // The title (set on the artwork) opens it too, still without playing.
        click(ui.onNode(hasText(MIX_TITLE) and hasAnyAncestor(hasContentDescription(MIX_TITLE)), useUnmergedTree = true))
        assertNotNull(ShellMixDetails.current, "clicking the title did not open the mix")
        assertEquals(emptyList(), fixture.controller.queuePlays, "opening from the title started playback")
        assertEquals(emptyList(), fixture.fake.calls, "opening from the title reached the player")
    }

    @Test
    fun mixPagePlaysInDisplayedOrderSavesSeparatelyAndEscapeReturnsHome() = shellShots(1400.dp, 900.dp) {
        val fixture = MixHomeFixture(listOf(mixShelf()))
        render { fixture.Desktop() }
        settleHomeGrid()
        parkPointer()
        label(MIX_TITLE).performMouseInput { this.click(Offset(40f, 40f)) }
        idle()
        val snapshot = assertNotNull(ShellMixDetails.current, "the mix did not open")
        assertEquals(MIX_SONGS, snapshot.songs, "the snapshot is not the mix's order")
        parkPointer()

        // 2: the title, Play and Save, no Shuffle, and the songs in the snapshot's order.
        val list = ui.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(MIX_SONGS[0].title)))
        assertTrue(ui.onAllNodes(hasText(MIX_TITLE)).fetchSemanticsNodes().isNotEmpty(), "the page shows no mix title")
        ui.onNode(hasContentDescription("Play") and hasAnyAncestor(pageList())).assertIsDisplayed()
        ui.onNode(hasContentDescription(SAVE) and hasAnyAncestor(pageList())).assertIsDisplayed()
        ui.onAllNodes(hasContentDescription("Shuffle") and hasAnyAncestor(pageList())).assertCountEquals(0)
        ui.onAllNodes(hasText("Shuffle") and hasAnyAncestor(pageList())).assertCountEquals(0)
        val displayed = mutableListOf<String>()
        MIX_SONGS.forEachIndexed { i, song ->
            list.performScrollToNode(hasText(song.title))
            idle()
            val texts = row(song.title).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertEquals("${i + 1}", texts.firstOrNull(), "row for ${song.title} is not number ${i + 1}: $texts")
            displayed += song.title
        }
        assertEquals(MIX_SONGS.map { it.title }, displayed)
        // The rows on screen also sit top to bottom in that order.
        list.performScrollToIndex(0)
        idle()
        val onScreen =
            MIX_SONGS.mapNotNull { s ->
                ui.onAllNodes(rowMatcher(s.title)).fetchSemanticsNodes().firstOrNull()?.let { s.title to it.boundsInRoot.top }
            }
        assertTrue(onScreen.size >= 3, "too few rows on screen: $onScreen")
        assertEquals(onScreen.sortedBy { it.second }.map { it.first }, onScreen.map { it.first }, "rows on screen are not in the mix's order")
        val details = shot("mix-details")
        assertTrue(details.distinctColors() > 20, "mix-details looks blank")

        // 3: row 5 plays the whole list as shown, starting at row 5 (index 4).
        list.performScrollToNode(hasText(MIX_SONGS[4].title))
        idle()
        click(row(MIX_SONGS[4].title))
        val play = fixture.controller.queuePlays.singleOrNull() ?: error("row 5 made ${fixture.controller.queuePlays.size} queue plays: ${fixture.fake.calls}")
        assertEquals(MIX_SONGS.map { it.id }, play.songs.map { it.id }, "row 5 did not queue the displayed list")
        assertEquals(4, play.startIndex, "row 5 did not start at index 4")
        assertEquals(MIX_TITLE, play.title)
        assertEquals(MIX_SONGS[4].id, fixture.fake.state.value.currentSong?.id)
        assertEquals(MIX_SONGS.map { it.id }, fixture.fake.state.value.queue.map { it.id }, "the player's queue is not in the displayed order")

        // 4: Save announces a bulk save (PlaylistAddWatcher.expectBulk), then opens the picker with every song in order.
        val watcher = startBulkWatcher()
        var pendingAtSave: List<Pair<Set<String>, Long>>? = null
        fixture.onSave = { pendingAtSave = pending(watcher) }
        list.performScrollToIndex(0)
        idle()
        click(ui.onNode(hasContentDescription(SAVE) and hasAnyAncestor(pageList())))
        val saved = fixture.saved.singleOrNull() ?: error("Save made ${fixture.saved.size} picker calls")
        assertEquals(MIX_SONGS.map { it.id }, saved.map { it.id }, "Save did not pass every song in the displayed order")
        val announced = assertNotNull(pendingAtSave, "the picker was not opened")
        assertEquals(listOf(MIX_SONGS.map { it.id }.toSet()), announced.map { it.first }, "the bulk save was not announced before the picker opened")
        assertEquals(1, fixture.controller.queuePlays.size, "Save started playback")
        stopKoin()
        koinStarted = false

        // 5: Escape closes the page and Home is back.
        escape()
        assertNull(ShellMixDetails.current, "Escape did not close the mix")
        text(MADE_FOR_YOU).assertIsDisplayed()
        label(MIX_TITLE).assertIsDisplayed()
        assertSame(Screen.Home, fixture.navigator.state.currentRoute)
        parkPointer()
        val closed = shot("mix-closed")
        assertTrue(closed.distinctColors() > 20, "mix-closed looks blank")
    }

    @Test
    fun phoneMixCardShowsPlayWithoutHoverAndKeepsTheTitleClearOfIt() = shellShots(360.dp, 800.dp) {
        val long = "Late Night Rainy Window Drive Mix"
        val fixture = MixHomeFixture(listOf(mixShelf(), mixShelf(key = "turtle:daily_mix:1", title = long)))
        render { fixture.Phone() }
        settleHomeGrid()
        text(MADE_FOR_YOU).assertIsDisplayed()
        for (title in listOf(MIX_TITLE, long)) {
            label(title).assertIsDisplayed()
            val play = ui.onNode(cardPlay(title), useUnmergedTree = true)
            play.assertIsDisplayed()
            val playBounds = play.fetchSemanticsNode().boundsInRoot
            val titleBounds = ui.onNode(hasText(title) and hasAnyAncestor(hasContentDescription(title)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val cardBounds = label(title).fetchSemanticsNode().boundsInRoot
            println("phone card '$title': card=$cardBounds title=$titleBounds play=$playBounds")
            assertTrue(playBounds.width >= 47.5f, "phone Play touch target is ${playBounds.width} wide")
            // The title may share the art's bottom edge with Play, but never reaches under it.
            assertTrue(titleBounds.right <= playBounds.left + 0.5f, "title ($titleBounds) runs under Play ($playBounds)")
        }
        // The long title wraps at a line height that fits its smaller size, not the full headline's 28 sp.
        val longTitle = ui.onNode(hasText(long) and hasAnyAncestor(hasContentDescription(long)), useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        longTitle.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        val lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        val fontSize = layout.layoutInput.style.fontSize.value
        println("phone long title: lines=${layout.lineCount} fontSize=$fontSize lineHeight=$lineHeight")
        assertTrue(layout.lineCount > 1, "the long title should wrap in this case")
        assertTrue(lineHeight <= fontSize * 1.4f, "long title line height $lineHeight for a $fontSize sp font: loose line spacing")
        val phone = shot("mix-card-phone")
        assertTrue(phone.distinctColors() > 20, "mix-card-phone looks blank")
        // On a phone the card's Play plays without opening anything.
        click(ui.onNode(cardPlay(MIX_TITLE)))
        assertNull(ShellMixDetails.current)
        assertEquals(listOf(QueuePlay(MIX_SONGS, 0, MIX_TITLE)), fixture.controller.queuePlays)
    }

    /**
     * A brand-new profile's artist-pick mix ("Drake Mix", none of its songs heard yet) says it is all
     * new, not "mostly songs you know": the about line counts the mix as built.
     */
    @Test
    fun coldProfileMixSaysItIsAllNew() = shellShots(1400.dp, 900.dp) {
        val fixture = MixHomeFixture(listOf(mixShelf(title = COLD_TITLE)))
        render { fixture.Desktop() }
        settleHomeGrid()
        parkPointer()
        val about = MixAbout(ShelfKind.DAILY_MIX, ShelfCadence.DAILY, seed = null, topArtists = listOf("Drake", "Future", "21 Savage"), known = 0, total = MIX_SONGS.size)
        ShellMixDetails.open(MixSnapshot("turtle:daily_mix:0", COLD_TITLE, "Made for you by Turtle", Color(0xFF3E6FD8), MIX_SONGS, about))
        idle()
        val line = "All new to you, picked for your taste. • Refreshes daily"
        ui.onNode(hasText(line)).assertIsDisplayed()
        ui.onAllNodes(hasText("familiar", substring = true)).assertCountEquals(0)
        ui.onAllNodes(hasText("songs you know", substring = true)).assertCountEquals(0)
        val cold = shot("mix-about-cold")
        assertTrue(cold.distinctColors() > 20, "mix-about-cold looks blank")
    }

    /** A mix holding some known songs states their share of the listed songs, percent sign and all. */
    @Test
    fun mixedMixStatesTheShareOfListedSongs() = shellShots(1400.dp, 900.dp) {
        val fixture = MixHomeFixture(listOf(mixShelf(title = COLD_TITLE)))
        render { fixture.Desktop() }
        settleHomeGrid()
        parkPointer()
        // The engine knew 4 songs; one of them is not on the page, so 3 of the 12 listed are known.
        val knownIds = MIX_SONGS.take(3).map { it.id }.toSet() + "not-listed"
        val about =
            MixAbout(
                ShelfKind.DAILY_MIX, ShelfCadence.DAILY, seed = "Drake", topArtists = listOf("Drake", "Future", "21 Savage"),
                known = 4, total = 30, knownSongs = knownIds,
            )
        ShellMixDetails.open(MixSnapshot("turtle:daily_mix:0", COLD_TITLE, "Made for you by Turtle", Color(0xFF3E6FD8), MIX_SONGS, about))
        idle()
        ui.onNode(hasText("Built around Drake. You know 25% of these songs. • Refreshes daily")).assertIsDisplayed()
        val mixed = shot("mix-about-mixed")
        assertTrue(mixed.distinctColors() > 20, "mix-about-mixed looks blank")
    }

    /**
     * Home holds grid slots for YouTube's suggestions as dim placeholder tiles for up to 4 s
     * (SHELVES_WAIT_MS). Headless no suggestions ever come (no runtime to score them), so without this
     * every shot caught Home mid-wait, with a block of blank tiles above Made for you.
     */
    private fun ShotScope.settleHomeGrid() {
        advance(4_100)
        idle()
    }

    /** The card title's text box ends left of the shown Play circle. */
    private fun ShotScope.assertTitleClearOfPlay(title: String, where: String) {
        val play = ui.onNode(cardPlay(title), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val text = ui.onNode(hasText(title) and hasAnyAncestor(hasContentDescription(title)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        println("$where card '$title': title=$text play=$play")
        assertTrue(text.right <= play.left + 0.5f, "$where: title ($text) runs under Play ($play)")
    }

    private fun ShotScope.row(title: String): SemanticsNodeInteraction = ui.onNode(rowMatcher(title))

    private fun rowMatcher(title: String) = hasText(title) and hasClickAction() and hasAnyAncestor(pageList())

    /** The mix page's song list (the scrollable list that holds the first song). */
    private fun pageList() = hasScrollToIndexAction() and hasAnyDescendant(hasText(MIX_SONGS[0].title))

    private fun cardPlay(title: String) = hasContentDescription("Play") and hasAnyAncestor(hasContentDescription(title))

    /** Moves the mouse to the window's top-left corner (the sidebar's edge), off any card or row. */
    private fun ShotScope.parkPointer() {
        ui.onNodeWithTag(WINDOW_TAG).performMouseInput { moveTo(Offset(2f, 2f)) }
        idle()
    }

    /** Registers a real PlaylistAddWatcher (no database: it is never started) where TurtleBridge looks for it. */
    private fun startBulkWatcher(): PlaylistAddWatcher {
        val store = InMemoryPlayEventStore()
        val taste = TasteEngine(events = store, store = InMemoryEmbeddingStore(), catalog = InMemoryTrackCatalog(emptyList()))
        val watcher = PlaylistAddWatcher(database = { error("no database in this test") }, store = store, taste = taste, cache = SongCache())
        startKoin { modules(module { single { watcher } }) }
        koinStarted = true
        return watcher
    }

    /** The watcher's announced bulk saves not yet handed on (it is never started, so all of them). */
    @Suppress("UNCHECKED_CAST")
    private fun pending(watcher: PlaylistAddWatcher): List<Pair<Set<String>, Long>> {
        val field = PlaylistAddWatcher::class.java.getDeclaredField("pendingBulk").apply { isAccessible = true }
        return (field.get(watcher) as MutableStateFlow<List<Pair<Set<String>, Long>>>).value
    }

    private companion object {
        const val MIX_TITLE = "Daily Mix 1"
        const val COLD_TITLE = "Drake Mix"
        const val MADE_FOR_YOU = "Made for you"
        const val SAVE = "Save to a playlist"

        /** Twelve distinct titles, deliberately not alphabetical, so any re-sorting shows. */
        val MIX_SONGS: List<SongItem> =
            listOf(
                "Kite Season", "Amber Skyline", "Hollow Pines", "Copper Rain", "Echo Harbour", "Juniper Road",
                "Borrowed Light", "Lantern Drift", "Dune Static", "Ivory Tide", "Glass Orchard", "Fable Lane",
            ).mapIndexed { i, title ->
                SongItem(
                    id = "mix-$i",
                    title = title,
                    thumbnail = null,
                    artists = listOf(ArtistTiny(id = "mix-artist-${i % 4}", name = "Mix Artist ${i % 4}")),
                    album = AlbumTiny(id = "mix-album-$i", name = "Mix Album $i"),
                    duration = 200L + i,
                )
            }

        fun mixShelf(key: String = "turtle:daily_mix:0", title: String = MIX_TITLE) =
            HomeShelf(
                key = key,
                title = title,
                items = MIX_SONGS,
                style = HomeShelfStyle.SONG_GRID,
                headerAction = HomeShelfHeaderAction.PLAY_ALL,
                rows = 4,
                label = "Made for you by Turtle",
                isPersonal = true,
            )
    }
}

/** One playQueue call: the list, where it starts, and the queue's name. */
private data class QueuePlay(val songs: List<SongItem>, val startIndex: Int, val title: String?)

/** [fake], recording playQueue (Shell's named queues) with its list and start instead of folding it into play(). */
private class QueueRecordingController(val fake: FakePlaybackController) : PlaybackController by fake {
    val queuePlays = mutableListOf<QueuePlay>()

    override fun playQueue(songs: List<SongItem>, startIndex: Int, queueTitle: String?) {
        queuePlays += QueuePlay(songs.toList(), startIndex, queueTitle)
        fake.calls += "playQueue:$startIndex"
        // Stays paused: a playing fake starts the position-polling loop.
        fake.update { it.copy(currentSong = songs.getOrNull(startIndex), queue = songs.toList(), currentQueueIndex = startIndex, isPlaying = false) }
    }
}

/**
 * Shell's real Home with Turtle mixes [shelves] (through upstream's homeShelvesOverride hook, as the
 * bridge adds them), fake playback, and desktop services whose Save records what it was handed.
 */
private class MixHomeFixture(shelves: List<HomeShelf>) {
    val fake = FakePlaybackController(queue = fakeQueue(30), index = 0)
    val controller = QueueRecordingController(fake)
    val navigator = fakeNavigator()
    val library = FakeLibrary()
    val home = FakeHome()
    val saved = mutableListOf<List<SongItem>>()
    var onSave: (List<SongItem>) -> Unit = {}
    private val base = FakeExtras().extras
    val extras =
        DesktopPlayerExtras(
            lyrics = base.lyrics, lyricsLoading = base.lyricsLoading, lyricsOffsetMs = base.lyricsOffsetMs, lyricsSongId = base.lyricsSongId,
            loadLyrics = base.loadLyrics, removeQueueIndex = base.removeQueueIndex, startSleepTimer = base.startSleepTimer,
            cancelSleepTimer = base.cancelSleepTimer, sleepTimerActive = base.sleepTimerActive, addToPlaylist = base.addToPlaylist,
            addSongsToPlaylist = { songs ->
                onSave(songs)
                saved += songs.toList()
            },
        )

    init {
        homeShelvesOverride = { upstream -> shelves + upstream }
        // The app restores this setting at startup (ShellHomeRows.restore); until it is set, Home holds its
        // grid as loading placeholders, which is all an unset harness ever showed above Made for you.
        ShellHomeRows.choose(HomeYouTubeRows.MATCHED)
    }

    /** The desktop frame (ShellNavigation into ShellDesktopLayout) with ShellHome as the Home page. */
    @Composable
    fun Desktop() {
        remember {
            TurtleUiSlots.desktopLibrary = library.slot
            TurtleUiSlots.desktopExtras = extras
        }
        CompositionLocalProvider(LocalPlaybackController provides controller, LocalNavigator provides navigator) {
            Column(Modifier.fillMaxSize().background(ShellThemes.active.frame)) {
                Box(Modifier.weight(1f)) { ShellNavigation(homeNavigationSlot(navigator, home.slot)) }
                ShellDesktopPlayerBar(fakePlayerBarSlot())
            }
        }
    }

    /** The phone Home (no desktop library registered, so Shell takes its phone layout). */
    @Composable
    fun Phone() {
        CompositionLocalProvider(LocalPlaybackController provides controller, LocalNavigator provides navigator) {
            ShellHome(home.slot)
        }
    }
}

/** [fakeNavigationSlot] with Shell's real Home on the Home route and fake pages elsewhere. */
@Composable
private fun homeNavigationSlot(navigator: Navigator, home: HomeSlot): NavigationSlot {
    val items =
        listOf(
            NavigationSlotItem("home", "Home", Glyphs.Home),
            NavigationSlotItem("search", "Search", Glyphs.Search),
            NavigationSlotItem("library", "Library", Glyphs.Library),
        )
    return NavigationSlot(
        items = items,
        selectedIndex = FakeTopRoutes.indexOf(navigator.state.topLevelRoute),
        onSelect = { i -> navigator.navigate(FakeTopRoutes[i]) },
        navigationVisible = true,
        bottomBar = false,
        floating = false,
        onOccupiedHeightChanged = {},
        header = {},
        footer = null,
        modifier = Modifier,
        content = { if (navigator.state.currentRoute == Screen.Home) UpstreamPageSurface { ShellHome(home) } else FakePage(navigator) },
    )
}

