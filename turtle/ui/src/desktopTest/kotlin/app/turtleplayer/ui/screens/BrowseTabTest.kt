package app.turtleplayer.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Android's Browse tab and Search's "See all" on the shared navigation state (ShellBrowse.nav). */
class BrowseTabTest {
    private val home = "Home"
    private val search = "Search"
    private val library = "Library"
    private val focus = "Browse(Focus)"
    private val lofi = "Playlist(Lofi Loft)"

    @Test
    fun browseOpenedFromSearchComesBackAfterACategory() {
        val nav = ExploreNav()
        nav.open(search, listOf(search))
        val entry = nav.entry
        // Focus, Lofi Loft, then Back, Back: the tab stays selected and Browse shows again, same scroll state.
        for (stack in listOf(listOf(search, focus), listOf(search, focus, lofi), listOf(search, focus))) {
            nav.follow(search, stack)
            assertFalse(nav.visible(search, stack))
            assertTrue(nav.selected(search, stack), "the Browse tab stays lit under its pages")
        }
        nav.follow(search, listOf(search))
        assertTrue(nav.visible(search, listOf(search)))
        assertSame(entry, nav.entry)
    }

    @Test
    fun choosingAnotherTabClosesBrowse() {
        val nav = ExploreNav()
        nav.open(home, listOf(home))
        // The tab bar closes Browse before switching, so Home never shows it again by itself.
        nav.close()
        nav.follow(library, listOf(library))
        nav.follow(home, listOf(home))
        assertNull(nav.entry)
        assertFalse(nav.selected(home, listOf(home)))
    }

    @Test
    fun aCategoryOpenedFromSearchTilesNeedsNoBrowseEntry() {
        // Search's category tiles push a real route; nothing is recorded, so Back simply returns to Search.
        val nav = ExploreNav()
        nav.follow(search, listOf(search, focus))
        nav.follow(search, listOf(search))
        assertNull(nav.entry)
        assertFalse(nav.visible(search, listOf(search)))
    }
}
