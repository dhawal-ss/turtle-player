package app.turtleplayer.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the desktop right panel shows and whether it is open, at either width. Wide windows dock the
 * panel beside the page; below the breakpoint the same surfaces open as an overlay over the page, so
 * the player bar's Queue, Lyrics and Devices buttons always show something when they light up.
 */
internal class DesktopPanelState {
    /** What the panel shows: Now Playing, or one of Shell's player surfaces. */
    var panel by mutableStateOf(DesktopPanel.NOW_PLAYING)

    /** The docked panel (wide windows) is open. Open by default: Spotify shows Now Playing. */
    var dockedOpen by mutableStateOf(true)
        private set

    /** The overlay panel (narrow windows) is open. */
    var overlayOpen by mutableStateOf(false)
        private set

    /** The window is below the docking breakpoint. */
    var narrow by mutableStateOf(false)
        private set

    private val open: Boolean get() = if (narrow) overlayOpen else dockedOpen

    private fun setOpen(value: Boolean) {
        if (narrow) overlayOpen = value else dockedOpen = value
    }

    /**
     * Shows [mode], or closes the panel when it is already showing it. With no song nothing can show,
     * so the click is ignored rather than remembered (it would pop the overlay open once a song starts).
     */
    fun toggle(mode: DesktopPanel, hasSong: Boolean = true) {
        if (!hasSong) return
        if (open && panel == mode) {
            setOpen(false)
        } else {
            panel = mode
            setOpen(true)
        }
    }

    /** Opens [mode]. */
    fun show(mode: DesktopPanel) {
        panel = mode
        setOpen(true)
    }

    fun close() = setOpen(false)

    /**
     * The window crossed the breakpoint. Going narrow carries an open queue, lyrics or devices panel
     * into the overlay (Now Playing stays docked-only, so a narrow launch never pops an overlay).
     * Going wide docks an open overlay; a surface closed while narrow is not brought back, so the
     * docked panel returns to Now Playing.
     */
    fun resized(narrow: Boolean) {
        if (narrow == this.narrow) return
        if (narrow) {
            overlayOpen = dockedOpen && panel != DesktopPanel.NOW_PLAYING
        } else {
            if (overlayOpen) {
                dockedOpen = true
            } else if (panel != DesktopPanel.NOW_PLAYING) {
                panel = DesktopPanel.NOW_PLAYING
            }
            overlayOpen = false
        }
        this.narrow = narrow
    }

    /** True while [mode] is on screen: the player bar lights its button only then. */
    fun showing(mode: DesktopPanel, hasSong: Boolean): Boolean = hasSong && open && panel == mode
}

/**
 * A place in navigation: the top-level route and its back stack when an overlay opened. The overlay
 * shows while navigation is exactly there, stays alive (hidden) while pages are pushed on top of it
 * or another top-level route is showing, and dies once its stack is popped below it or reset.
 */
internal class RouteAnchor<R>(val topLevel: R, stack: List<R>) {
    val stack: List<R> = stack.toList()

    fun visible(top: R, stack: List<R>): Boolean = top == topLevel && stack == this.stack

    fun inSubtree(top: R, stack: List<R>): Boolean =
        top == topLevel && stack.size > this.stack.size && stack.subList(0, this.stack.size) == this.stack

    fun alive(top: R, stack: List<R>): Boolean = top != topLevel || visible(top, stack) || inSubtree(top, stack)
}

/** Explore while it is open: where it opened, and its scroll and entrance state, kept across pages opened from it. */
internal class ExploreEntry(
    val anchor: RouteAnchor<Any>,
    val list: LazyListState = LazyListState(),
    val rise: RiseIn = RiseIn(settled = false),
)

/** Desktop Explore's navigation state: opened over a route, followed as navigation changes. */
internal class ExploreNav {
    var entry by mutableStateOf<ExploreEntry?>(null)
        private set

    /** Opens Explore over [top]/[stack]. Reopening keeps the scroll position of the entry still alive. */
    fun open(top: Any, stack: List<Any>) {
        val old = entry
        entry = if (old != null) ExploreEntry(RouteAnchor(top, stack), old.list, old.rise) else ExploreEntry(RouteAnchor(top, stack))
    }

    fun close() {
        entry = null
    }

    /** Explore covers the page. */
    fun visible(top: Any, stack: List<Any>): Boolean = entry?.anchor?.visible(top, stack) == true

    /** Explore or a page opened from it is showing: the sidebar keeps Explore selected. */
    fun selected(top: Any, stack: List<Any>): Boolean = entry?.anchor?.let { it.visible(top, stack) || it.inSubtree(top, stack) } == true

    /** Drops Explore once navigation has left it for good; call on every navigation change. */
    fun follow(top: Any, stack: List<Any>) {
        if (entry?.anchor?.alive(top, stack) == false) entry = null
    }
}
