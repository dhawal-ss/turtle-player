package app.turtleplayer.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPanelStateTest {
    private fun narrow() = DesktopPanelState().apply { resized(true) }

    @Test
    fun queueOpensInANarrowWindowAndTheButtonLightsOnlyWhileItShows() {
        // The 1080x720 regression: the button lit with nothing on screen.
        val state = narrow()
        assertFalse(state.showing(DesktopPanel.QUEUE, hasSong = true))
        state.toggle(DesktopPanel.QUEUE)
        assertTrue(state.overlayOpen)
        assertTrue(state.showing(DesktopPanel.QUEUE, hasSong = true))
        assertFalse(state.showing(DesktopPanel.NOW_PLAYING, hasSong = true))
        state.toggle(DesktopPanel.QUEUE)
        assertFalse(state.overlayOpen)
        assertFalse(state.showing(DesktopPanel.QUEUE, hasSong = true))
    }

    @Test
    fun nothingShowsWithoutASong() {
        for (s in listOf(DesktopPanelState(), narrow())) {
            DesktopPanel.entries.forEach { mode ->
                s.show(mode)
                assertFalse(s.showing(mode, hasSong = false), "$mode")
                assertTrue(s.showing(mode, hasSong = true), "$mode")
            }
        }
    }

    @Test
    fun aPanelClickWithNoSongIsNotRemembered() {
        // A Queue click with nothing playing used to set overlayOpen, and the overlay popped up by
        // itself once a song started.
        val state = narrow()
        state.toggle(DesktopPanel.QUEUE, hasSong = false)
        assertFalse(state.overlayOpen)
        assertFalse(state.showing(DesktopPanel.QUEUE, hasSong = true))
        val wide = DesktopPanelState()
        wide.toggle(DesktopPanel.QUEUE, hasSong = false)
        assertTrue(wide.dockedOpen)
        assertEquals(DesktopPanel.NOW_PLAYING, wide.panel, "the docked panel switched to the queue with no song")
    }

    @Test
    fun aNarrowLaunchPopsNoOverlay() {
        val state = DesktopPanelState()
        assertTrue(state.dockedOpen)
        state.resized(true)
        assertFalse(state.overlayOpen)
        assertFalse(state.showing(DesktopPanel.NOW_PLAYING, hasSong = true))
    }

    @Test
    fun wideningDocksTheOpenOverlay() {
        val state = narrow()
        state.toggle(DesktopPanel.QUEUE)
        state.resized(false)
        assertFalse(state.overlayOpen)
        assertTrue(state.dockedOpen)
        assertTrue(state.showing(DesktopPanel.QUEUE, hasSong = true))
    }

    @Test
    fun narrowingCarriesTheQueueButNotNowPlaying() {
        val queue = DesktopPanelState()
        queue.show(DesktopPanel.QUEUE)
        queue.resized(true)
        assertTrue(queue.showing(DesktopPanel.QUEUE, hasSong = true))

        val nowPlaying = DesktopPanelState()
        nowPlaying.show(DesktopPanel.NOW_PLAYING)
        nowPlaying.resized(true)
        assertFalse(nowPlaying.overlayOpen)
        // Back to wide, the docked Now Playing view is still there.
        nowPlaying.resized(false)
        assertTrue(nowPlaying.showing(DesktopPanel.NOW_PLAYING, hasSong = true))
    }

    @Test
    fun aSurfaceClosedWhileNarrowDoesNotComeBackWhenWide() {
        val state = DesktopPanelState()
        state.show(DesktopPanel.QUEUE)
        state.resized(true)
        state.close()
        state.resized(false)
        assertEquals(DesktopPanel.NOW_PLAYING, state.panel)
        assertTrue(state.showing(DesktopPanel.NOW_PLAYING, hasSong = true))
    }

    @Test
    fun closeWorksAtBothWidths() {
        val wide = DesktopPanelState()
        wide.show(DesktopPanel.LYRICS)
        wide.close()
        assertFalse(wide.dockedOpen)
        assertFalse(wide.showing(DesktopPanel.LYRICS, hasSong = true))

        val narrow = narrow()
        narrow.show(DesktopPanel.LYRICS)
        narrow.close()
        assertFalse(narrow.overlayOpen)
        assertFalse(narrow.showing(DesktopPanel.LYRICS, hasSong = true))
    }

    @Test
    fun resizingToTheSameWidthChangesNothing() {
        val state = DesktopPanelState()
        state.show(DesktopPanel.DEVICES)
        state.resized(false)
        assertTrue(state.showing(DesktopPanel.DEVICES, hasSong = true))
    }
}
