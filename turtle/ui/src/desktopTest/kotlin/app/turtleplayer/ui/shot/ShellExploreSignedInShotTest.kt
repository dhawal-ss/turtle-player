@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.turtleplayer.ui.shot

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.turtleplayer.bridge.ExploreData
import app.turtleplayer.ui.screens.ShellBrowse
import app.turtleplayer.ui.screens.ShellExploreSource
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.viewmodels.HomeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Explore when signed in (owner's Windows report): Home's personal feed carries no moods, new releases
 * or charts, so Explore loads YouTube Music's Explore page through the bridge (here a fake source) and
 * must show it, or a Retry when the load fails, never placeholders forever.
 */
class ShellExploreSignedInShotTest {
    @BeforeTest
    fun reset() = resetShellState()

    @AfterTest
    fun cleanUp() = resetShellState()

    @Test
    fun signedInExploreShowsItsOwnLoad() = shellShots(1400.dp, 1500.dp) {
        val shell = ShellDesktopFixture()
        val home = FakeHome(state = signedInHome(), open = { shell.navigator.open(it) })
        val source = FakeExploreSource { exploreData() }
        onUi {
            ShellBrowse.home = home.slot
            ShellExploreSource.source = source
        }
        render { shell.Content() }

        clickTab("Explore")
        text(EXPLORE_TITLE).assertIsDisplayed()
        assertEquals(listOf(false), source.loads, "Explore did not ask for its own load once")
        // The grid shows three rows; at this width (Now Playing docked) that is two columns.
        Genres.take(6).forEach { text(it).assertIsDisplayed() }
        text("New Release 0").assertIsDisplayed()
        text(POPULAR_HEADER).assertIsDisplayed()
        text(PopularTitles[0]).assertIsDisplayed()
        ui.onAllNodes(hasContentDescription(LOADING)).assertCountEquals(0)
        val s = shot("explore-signed-in")
        assertTrue(s.distinctColors() > 20, "explore-signed-in looks blank")

        // A genre still opens through Home's opener, a real route, so Back returns to Explore.
        clickText("Focus")
        assertEquals(listOf("Focus"), home.opened.map { it.title })
        assertEquals(Screen.Browse("FEmusic_moods_and_genres_category", "focus-params", "Focus"), shell.navigator.state.currentRoute)
        onUi { check(shell.navigator.goBack()) }
        idle()
        text(EXPLORE_TITLE).assertIsDisplayed()
        text("Focus").assertIsDisplayed()

        // Paged to its end, a signed-in Home gains moods (upstream's fallback) but still no new releases:
        // Home's genres win, and Explore keeps the new releases its own load brought.
        val loadsBefore = source.loads.toList()
        onUi {
            ShellBrowse.home = FakeHome(
                state = HomeViewModel.HomeUiState(moodsAndGenres = List(3) { HomeViewModel.Genre(name = "Genre $it", id = "genre-$it", params = "p$it") }),
            ).slot
        }
        idle()
        text("Genre 0").assertIsDisplayed()
        text("New Release 0").assertIsDisplayed()
        assertEquals(loadsBefore, source.loads, "Home gaining moods started a load")
    }

    @Test
    fun aFailedLoadOffersRetry() = shellShots(1400.dp, 900.dp) {
        val shell = ShellDesktopFixture()
        val source = FakeExploreSource { null }
        onUi {
            ShellBrowse.home = FakeHome(state = signedInHome()).slot
            ShellExploreSource.source = source
        }
        render { shell.Content() }

        clickTab("Explore")
        text(EXPLORE_TITLE).assertIsDisplayed()
        text(FAILED).assertIsDisplayed()
        text(RETRY).assertIsDisplayed()
        // A button with a 48 dp target, not a chip (which reads as an unselected tab).
        text(RETRY).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertTrue(text(RETRY).getUnclippedBoundsInRoot().height >= 48.dp, "Retry is under 48 dp tall")
        ui.onAllNodes(hasText("Chill")).assertCountEquals(0)
        val s = shot("explore-signed-in-error")
        assertTrue(s.distinctColors() > 10, "explore-signed-in-error looks blank")

        // Retry forces a load; once it answers, the genres replace the message.
        source.answer = { exploreData() }
        clickText(RETRY)
        assertEquals(listOf(false, true), source.loads)
        text("Chill").assertIsDisplayed()
        ui.onAllNodes(hasText(FAILED)).assertCountEquals(0)
    }

    @Test
    fun signedOutExploreKeepsHomeDataAndLoadsNothing() = shellShots(1400.dp, 900.dp) {
        val shell = ShellDesktopFixture()
        val source = FakeExploreSource { exploreData() }
        onUi {
            ShellBrowse.home = FakeHome().slot
            ShellExploreSource.source = source
        }
        render { shell.Content() }
        clickTab("Explore")
        text("Genre 0").assertIsDisplayed()
        assertEquals(emptyList(), source.loads, "signed out, Explore loaded on its own")
        ui.onAllNodes(hasText("Chill")).assertCountEquals(0)
    }

    @Test
    fun searchCategoriesComeFromExploreWhenHomeHasNone() {
        val data = exploreData()
        assertEquals(emptyList(), ShellBrowse.categories(data), "categories before Home has drawn (no opener)")
        ShellBrowse.home = FakeHome(state = signedInHome()).slot
        assertEquals(Genres, ShellBrowse.categories(data).map { it.title })
        ShellBrowse.home = FakeHome(genres = 3).slot
        assertEquals(listOf("Genre 0", "Genre 1", "Genre 2"), ShellBrowse.categories(data).map { it.title })
    }

    /** Home signed in: a personal feed with none of Explore's sections. */
    private fun signedInHome() = HomeViewModel.HomeUiState()

    private fun exploreData() = ExploreData(
        genres = Genres.map { name ->
            BrowseItem(id = "FEmusic_moods_and_genres_category", title = name, params = if (name == "Focus") "focus-params" else "p-$name")
        },
        newReleases = List(6) { AlbumItem(id = "MPREb_new$it", title = "New Release $it", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $it"))) },
        trending = PopularTitles.mapIndexed { i, title -> fakeSong(300 + i, title = title, artist = "Artist $i").copy(duration = 200L) },
        albumCharts = List(4) { AlbumItem(id = "MPREb_chart$it", title = "Chart Album $it", thumbnail = null, artists = listOf(ArtistTiny(null, "Charting $it"))) },
    )

    private fun Navigator.open(item: YTItem) = when (item) {
        is BrowseItem -> navigate(Screen.Browse(item.id, item.params, item.title))
        else -> error("unexpected item $item")
    }

    private companion object {
        const val EXPLORE_TITLE = "Wander off."
        const val POPULAR_HEADER = "Popular picks"
        const val LOADING = "Loading Explore"
        const val FAILED = "Explore did not load. Check your connection and try again."
        const val RETRY = "Try again"
        val Genres = listOf("Chill", "Focus", "Workout", "Party", "Sleep", "Romance", "Hip-hop", "Jazz")
        val PopularTitles = listOf("Night Drive", "Paper Planes", "Golden Hour", "Low Tide")
    }
}
