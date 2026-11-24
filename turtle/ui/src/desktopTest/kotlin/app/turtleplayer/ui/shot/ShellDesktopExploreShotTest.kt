@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.turtleplayer.ui.shot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.screens.ShellBrowse
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.viewmodels.HomeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression case B (owner's audit, Windows): Explore -> Focus -> Lofi Loft -> Back, Back must land on
 * Explore where it was scrolled, without replaying its entrance. Drives the real ShellDesktopLayout
 * headless at 1400x900 with upstream's real Navigator; the category and playlist pages are fakes.
 */
class ShellDesktopExploreShotTest {
    @BeforeTest
    fun reset() = resetShellState()

    @AfterTest
    fun cleanUp() = resetShellState()

    @Test
    fun backFromACategoryPlaylistReturnsToExploreWhereItWasScrolled() = shellShots(1400.dp, 900.dp) {
        val shell = ShellDesktopFixture()
        val home = FakeHome(state = exploreState(), open = { item -> shell.navigator.open(item) })
        onUi { ShellBrowse.home = home.slot }
        render { shell.Content() }

        // 1: the sidebar's Explore row opens Explore over Home and lights up.
        clickTab("Explore")
        text(EXPLORE_TITLE).assertIsDisplayed()
        tab("Explore").assertIsSelected()
        tab("Home").assertIsNotSelected()
        val entry = assertNotNull(ShellBrowse.nav.entry, "Explore has no entry after the sidebar click")
        assertTrue(shot("explore-open").distinctColors() > 20, "explore-open looks blank")

        // 2: scroll with the mouse wheel until the Focus tile is near the top of the list.
        val list = exploreList()
        val viewportTop = list.fetchSemanticsNode().boundsInRoot.top
        var wheel = 0
        while (text("Focus").fetchSemanticsNode().boundsInRoot.top > viewportTop + 40f && wheel < 60) {
            list.performMouseInput { moveTo(center); scroll(1f) }
            idle()
            wheel++
        }
        val index = entry.list.firstVisibleItemIndex
        val offset = entry.list.firstVisibleItemScrollOffset
        println("explore-scrolled: wheel=$wheel index=$index offset=$offset")
        assertTrue(index >= 1 && offset > 0, "Explore did not scroll (index $index, offset $offset)")
        assertTrue(entry.rise.settled, "scrolling did not settle the entrance animation")
        text("Focus").assertIsDisplayed()
        val focusBefore = text("Focus").fetchSemanticsNode().boundsInRoot
        parkPointer() // no hover highlight in the shots compared below
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val scrolled = shot("explore-scrolled")

        // 3: the Focus tile calls Home's opener (a real route push), then the category opens Lofi Loft.
        clickText("Focus")
        assertEquals(listOf("Focus"), home.opened.map { it.title })
        assertEquals(Screen.Browse("focus-id", "focus-params", "Focus"), shell.navigator.state.currentRoute)
        text("Category Focus").assertIsDisplayed()
        onUi { shell.navigator.navigate(Screen.Playlist("lofi-loft")) }
        text("Playlist lofi-loft").assertIsDisplayed()
        // Explore is gone from the screen (its tiles are labelled), not just scrolled away.
        ui.onAllNodes(hasContentDescription("Focus")).assertCountEquals(0)
        ui.onAllNodes(hasText(EXPLORE_TITLE)).assertCountEquals(0)
        assertFalse(ShellBrowse.nav.visible(shell.top(), shell.stack()), "Explore still covers the playlist")
        tab("Explore").assertIsSelected()
        tab("Home").assertIsNotSelected()
        assertSame(entry, ShellBrowse.nav.entry, "Explore's entry did not survive the pages opened from it")
        shot("explore-category")

        // 4: Back twice. Desktop Back (Escape with nothing else open, the mouse Back button, the menu)
        // ends in DesktopNavigation.goBack(), which is Navigator.goBack().
        onUi { check(shell.navigator.goBack()) }
        text("Category Focus").assertIsDisplayed()
        tab("Explore").assertIsSelected()
        // The second Back with the clock held: Explore's very first frame must already be where it was
        // (no jump to the top and back), and its tiles must not rise in again.
        ui.mainClock.autoAdvance = false
        onUi { check(shell.navigator.goBack()) }
        advance(16)
        idle()
        assertTrue(ShellBrowse.nav.visible(shell.top(), shell.stack()), "Explore is not showing after Back")
        val back = assertNotNull(ShellBrowse.nav.entry)
        assertSame(entry, back, "Back built a new Explore entry")
        assertEquals(index to offset, back.list.firstVisibleItemIndex to back.list.firstVisibleItemScrollOffset, "first frame after Back is not where Explore was")
        assertTrue(back.rise.settled, "Explore's entrance would replay")
        ui.mainClock.autoAdvance = true
        idle()
        tab("Explore").assertIsSelected()
        tab("Home").assertIsNotSelected()
        assertEquals(index, back.list.firstVisibleItemIndex, "Back lost Explore's scroll index")
        assertEquals(offset, back.list.firstVisibleItemScrollOffset, "Back lost Explore's scroll offset")
        text("Focus").assertIsDisplayed()
        val focusAfter = text("Focus").fetchSemanticsNode().boundsInRoot
        assertEquals(focusBefore, focusAfter, "the Focus tile moved across Back")
        parkPointer()
        val backShot = shot("explore-back")
        val changed = changedFraction(scrolled, backShot, listBounds)
        println("explore-back: index=${back.list.firstVisibleItemIndex} offset=${back.list.firstVisibleItemScrollOffset} changedFraction=$changed")
        assertTrue(changed < 0.005, "Explore after Back looks different from before ($changed of its pixels changed)")

        // 6: Home closes Explore; Explore opens again from the sidebar.
        clickTab("Home")
        assertNull(ShellBrowse.nav.entry, "Home did not close Explore")
        ui.onAllNodes(hasText(EXPLORE_TITLE)).assertCountEquals(0)
        text("Home row 0").assertIsDisplayed()
        tab("Home").assertIsSelected()
        tab("Explore").assertIsNotSelected()
        clickTab("Explore")
        text(EXPLORE_TITLE).assertIsDisplayed()
        tab("Explore").assertIsSelected()
        val reopened = assertNotNull(ShellBrowse.nav.entry)
        println("explore-reopened: index=${reopened.list.firstVisibleItemIndex} offset=${reopened.list.firstVisibleItemScrollOffset}")
    }

    /** 5: Popular picks are labelled as unranked trending songs, with no position numbers beside them. */
    @Test
    fun popularPicksAreUnranked() = shellShots(1400.dp, 900.dp) {
        val shell = ShellDesktopFixture()
        onUi { ShellBrowse.home = FakeHome(state = exploreState()).slot }
        render { shell.Content() }
        clickTab("Explore")
        val list = exploreList()
        var wheel = 0
        val state = ShellBrowse.nav.entry!!.list
        // Wheel down until the section header sits near the top of the list, with its rows below it.
        // A prefetched (composed, not yet placed) item reports empty bounds, so only placed headers count.
        fun headerTop() = ui.onAllNodes(hasText(POPULAR_HEADER)).fetchSemanticsNodes().map { it.boundsInRoot }.firstOrNull { it.height > 0f }?.top
        while (state.canScrollForward && (headerTop() ?: Float.MAX_VALUE) > list.fetchSemanticsNode().boundsInRoot.top + 120f) {
            check(wheel++ < 80) { "Popular picks never scrolled into view" }
            list.performMouseInput { moveTo(center); scroll(1f) }
            idle()
        }
        println(
            "explore-popular: wheel=$wheel index=${state.firstVisibleItemIndex} offset=${state.firstVisibleItemScrollOffset} " +
                "list=${list.fetchSemanticsNode().boundsInRoot} header=${ui.onAllNodes(hasText(POPULAR_HEADER)).fetchSemanticsNodes().map { it.boundsInRoot }}",
        )
        val popular = shot("explore-popular")
        text(POPULAR_HEADER).assertIsDisplayed()
        text(POPULAR_SOURCE).assertIsDisplayed()
        PopularTitles.forEach { text(it).assertIsDisplayed() }
        // No rank column: no node anywhere reads just a number.
        for (n in 1..10) ui.onAllNodes(hasText(n.toString())).assertCountEquals(0)
        // Each song row holds artwork, title, artist and duration only.
        val rowTexts = text(PopularTitles[0]).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        println("popular row texts: $rowTexts")
        assertEquals(listOf(PopularTitles[0], "Artist 0"), rowTexts.take(2))
        assertEquals(3, rowTexts.size, "a song row shows other text than title, artist and duration: $rowTexts")
        assertTrue(Regex("""\d+:\d\d""").matches(rowTexts[2]), "third text is not a duration: ${rowTexts[2]}")
        assertTrue(popular.distinctColors() > 20, "explore-popular looks blank")
    }

    /** Moves the mouse onto the frame gap under the page, so nothing on the page is hovered. */
    private fun ShotScope.parkPointer() {
        ui.onNodeWithTag(WINDOW_TAG).performMouseInput { moveTo(Offset(304f, height - 100f)) }
        idle()
    }

    private fun ShellDesktopFixture.top(): Any = navigator.state.topLevelRoute

    private fun ShellDesktopFixture.stack(): List<Any> = navigator.state.backStacks.getValue(navigator.state.topLevelRoute).toList()

    /** Share of pixels inside [region] (window coordinates, 1 dp = 1 px) that differ between two shots. */
    private fun changedFraction(a: Shot, b: Shot, region: Rect): Double {
        val w = a.image.width
        val h = a.image.height
        val pa = IntArray(w * h).also { a.image.readPixels(it) }
        val pb = IntArray(w * h).also { b.image.readPixels(it) }
        var changed = 0
        var total = 0
        for (y in region.top.toInt().coerceAtLeast(0) until region.bottom.toInt().coerceAtMost(h)) {
            for (x in region.left.toInt().coerceAtLeast(0) until region.right.toInt().coerceAtMost(w)) {
                total++
                if (pa[y * w + x] != pb[y * w + x]) changed++
            }
        }
        return changed.toDouble() / total
    }

    /** Explore's scrolling list: the lazy list holding Explore's title. */
    private fun ShotScope.exploreList(): SemanticsNodeInteraction {
        val id = ui.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(EXPLORE_TITLE))).fetchSemanticsNode().id
        return ui.onNode(SemanticsMatcher("Explore list") { it.id == id })
    }

    /** The desktop app's opener: a category pushes Screen.Browse, a playlist Screen.Playlist. */
    private fun Navigator.open(item: YTItem) = when (item) {
        is BrowseItem -> navigate(Screen.Browse(item.id, item.params, item.title))
        is PlaylistItem -> navigate(Screen.Playlist(item.id))
        else -> error("unexpected item $item")
    }

    private fun exploreState() = HomeViewModel.HomeUiState(
        // Focus sixth: the third row at two columns, the second at four.
        moodsAndGenres = listOf("Chill", "Workout", "Party", "Sleep", "Romance").mapIndexed { i, name ->
            HomeViewModel.Genre(name = name, id = "genre-$i", params = "p$i")
        } + HomeViewModel.Genre(name = "Focus", id = "focus-id", params = "focus-params") +
            List(6) { HomeViewModel.Genre(name = "Genre $it", id = "more-$it", params = "m$it") },
        newReleases = List(8) { AlbumItem(id = "new-$it", title = "New Release $it", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $it"))) },
        trending = PopularTitles.mapIndexed { i, title -> fakeSong(200 + i, title = title, artist = "Artist $i").copy(duration = 180L) },
        albumCharts = List(6) { AlbumItem(id = "chart-$it", title = "Chart Album $it", thumbnail = null, artists = listOf(ArtistTiny(null, "Charting $it"))) },
    )

    private companion object {
        const val EXPLORE_TITLE = "Wander off."
        const val POPULAR_HEADER = "Popular picks"
        const val POPULAR_SOURCE = "Trending songs from YouTube Music, in no ranked order."
        val PopularTitles = listOf("Night Drive", "Paper Planes", "Golden Hour", "Low Tide", "Neon Rain", "Slow Motion")
    }
}
