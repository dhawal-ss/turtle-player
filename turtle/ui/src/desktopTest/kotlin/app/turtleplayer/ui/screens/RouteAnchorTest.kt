package app.turtleplayer.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RouteAnchorTest {
    private val home = "Home"
    private val focus = "Browse(Focus)"
    private val lofi = "Playlist(Lofi Loft)"

    @Test
    fun backTwiceFromACategoryOpenedFromExploreShowsExploreAgain() {
        val anchor = RouteAnchor(home, listOf(home))
        assertTrue(anchor.visible(home, listOf(home)))

        // Explore -> Focus -> Lofi Loft: Explore is hidden underneath, not gone.
        for (stack in listOf(listOf(home, focus), listOf(home, focus, lofi))) {
            assertFalse(anchor.visible(home, stack))
            assertTrue(anchor.inSubtree(home, stack))
            assertTrue(anchor.alive(home, stack))
        }

        // Back, Back.
        assertTrue(anchor.inSubtree(home, listOf(home, focus)))
        assertTrue(anchor.visible(home, listOf(home)))
    }

    @Test
    fun poppingBelowTheAnchorEndsIt() {
        val anchor = RouteAnchor(home, listOf(home, focus))
        assertFalse(anchor.alive(home, listOf(home)))
        // A different page at the same depth is not the anchor's subtree either.
        assertFalse(anchor.alive(home, listOf(home, lofi)))
        assertFalse(anchor.alive(home, listOf(home, lofi, focus)))
    }

    @Test
    fun aStackResetToItsRootEndsADeeperAnchorButNotOneAtTheRoot() {
        val deep = RouteAnchor(home, listOf(home, focus))
        assertFalse(deep.alive(home, listOf(home)))
        val root = RouteAnchor(home, listOf(home))
        assertTrue(root.visible(home, listOf(home)))
    }

    @Test
    fun anotherTopLevelPageKeepsItAliveButHidden() {
        val anchor = RouteAnchor(home, listOf(home))
        assertTrue(anchor.alive("Library", listOf("Library")))
        assertFalse(anchor.visible("Library", listOf("Library")))
        assertFalse(anchor.inSubtree("Library", listOf("Library", home)))
    }

    @Test
    fun theAnchorCopiesTheStack() {
        val live = mutableListOf(home)
        val anchor = RouteAnchor(home, live)
        live += focus
        assertTrue(anchor.inSubtree(home, live))
        assertTrue(anchor.visible(home, listOf(home)))
    }

    @Test
    fun exploreFollowsNavigationAndKeepsItsScrollState() {
        val nav = ExploreNav()
        nav.open(home, listOf(home))
        val entry = assertNotNull(nav.entry)
        assertTrue(nav.visible(home, listOf(home)))

        nav.follow(home, listOf(home, focus))
        assertSame(entry, nav.entry)
        assertFalse(nav.visible(home, listOf(home, focus)))
        assertTrue(nav.selected(home, listOf(home, focus)))

        nav.follow(home, listOf(home))
        assertSame(entry, nav.entry)
        assertTrue(nav.visible(home, listOf(home)))

        // Reopened from a page under it: shows there, with the same list and entrance state.
        nav.open(home, listOf(home, focus))
        assertSame(entry.list, nav.entry?.list)
        assertSame(entry.rise, nav.entry?.rise)
        assertTrue(nav.visible(home, listOf(home, focus)))
    }

    @Test
    fun leavingTheStackDropsExplore() {
        val nav = ExploreNav()
        nav.open(home, listOf(home, focus))
        nav.follow(home, listOf(home))
        assertNull(nav.entry)
        assertFalse(nav.visible(home, listOf(home, focus)))
    }

    @Test
    fun aLatePushAfterClosingDoesNotBringExploreBack() {
        val nav = ExploreNav()
        nav.open(home, listOf(home))
        nav.close()
        // The genre tile's page arrives after Escape closed Explore.
        nav.follow(home, listOf(home, focus))
        nav.follow(home, listOf(home))
        assertNull(nav.entry)
        assertFalse(nav.visible(home, listOf(home)))
        assertFalse(nav.selected(home, listOf(home, focus)))
    }
}
