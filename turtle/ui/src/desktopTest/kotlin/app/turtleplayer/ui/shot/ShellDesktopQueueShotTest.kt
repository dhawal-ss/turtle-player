@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.turtleplayer.ui.shot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.screens.DesktopPanel
import app.turtleplayer.ui.screens.LibraryFilter
import app.turtleplayer.ui.screens.ShellDesktop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression case A (owner's audit, Windows): at 1080x720 the Queue button lit up with no queue on
 * screen, and the queue heading broke inside the word "queue" in the wide panel. Drives the real
 * ShellDesktopLayout headless and checks what is on screen, not just the panel state.
 */
class ShellDesktopQueueShotTest {
    @BeforeTest
    fun reset() = resetShellState()

    @AfterTest
    fun cleanUp() = resetShellState()

    /** 1 and 2: the narrow overlay opens from the right over a dimmed page, and the scrim and Escape close it. */
    @Test
    fun narrowQueueOpensAsOverlayAndCloses() = shellShots(1080.dp, 720.dp) {
        val shell = ShellDesktopFixture(controller = FakePlaybackController(playing = true))
        render { shell.Content() }
        assertFalse(queueButtonLit(), "Queue lit before it was clicked")
        val before = shot("queue-narrow-before")
        val pageRegion = Rect(330f, 120f, 700f, 560f)
        val pageLumBefore = meanLuminance(before, pageRegion)

        clickLabel("Queue")
        assertTrue(ShellDesktop.panelState.overlayOpen, "Queue did not open the overlay")
        assertEquals(DesktopPanel.QUEUE, ShellDesktop.panelState.panel)
        assertTrue(queueButtonLit(), "Queue button not lit while the queue shows")
        text("Queue").assertIsDisplayed() // the panel title
        text("Song 3").assertIsDisplayed()
        text("Song 5").assertIsDisplayed()

        val card = queueCard()
        val outer = card.width + 8f // the 8 dp end gutter the panel keeps from the window edge
        println("queue-narrow-open: card=$card outerWidth=$outer")
        assertTrue(outer in 320f..360f, "overlay width $outer dp, expected about 340")
        assertEquals(1080f, card.right + 8f, 0.5f, "overlay is not against the right edge")
        // An overlay, not a squeezed page: the page keeps its full width and runs under the panel.
        val pageRow = text("Home row 0").fetchSemanticsNode().boundsInRoot
        assertTrue(pageRow.left < card.left - 300f, "page not visible to the left of the overlay")
        assertTrue(pageRow.right > card.left, "page was squeezed beside the panel instead of overlaid ($pageRow vs $card)")

        val open = shot("queue-narrow-open")
        assertTrue(open.distinctColors() > 20, "queue-narrow-open looks blank")
        val pageLumOpen = meanLuminance(open, pageRegion)
        println("page luminance before=$pageLumBefore open=$pageLumOpen")
        assertTrue(pageLumOpen < pageLumBefore * 0.75f, "page not dimmed behind the overlay ($pageLumBefore -> $pageLumOpen)")
        assertTrue(pageLumOpen > 1f, "page not visible behind the scrim (luminance $pageLumOpen)")

        // 2: a click on the scrim, left of the panel, closes it.
        ui.onNodeWithTag(WINDOW_TAG).performTouchInput { click(Offset(450f, 300f)) }
        idle()
        assertFalse(ShellDesktop.panelState.overlayOpen, "scrim click did not close the overlay")
        assertFalse(queueButtonLit(), "Queue still lit after the overlay closed")
        assertTrue(ui.onAllNodes(hasText("Song 5")).fetchSemanticsNodes().isEmpty(), "queue rows still on screen")
        shot("queue-narrow-closed")

        clickLabel("Queue")
        assertTrue(ShellDesktop.panelState.overlayOpen, "Queue did not reopen")
        assertTrue(queueButtonLit())
        escape()
        assertFalse(ShellDesktop.panelState.overlayOpen, "Escape did not close the overlay")
        assertFalse(queueButtonLit(), "Queue still lit after Escape")
        assertTrue(ui.onAllNodes(hasText("Song 5")).fetchSemanticsNodes().isEmpty(), "queue rows still on screen after Escape")
    }

    /** 3: an open narrow queue docks when the window widens, and becomes the overlay again when it narrows. */
    @Test
    fun queueMovesBetweenOverlayAndDockOnResize() = shellShots(1080.dp, 720.dp) {
        val shell = ShellDesktopFixture()
        render { shell.Content() }
        clickLabel("Queue")
        assertTrue(ShellDesktop.panelState.overlayOpen)

        resize(1400.dp, 900.dp)
        assertFalse(ShellDesktop.panelState.narrow)
        assertFalse(ShellDesktop.panelState.overlayOpen, "still an overlay after widening")
        assertTrue(ShellDesktop.panelState.dockedOpen, "queue not docked after widening")
        assertEquals(DesktopPanel.QUEUE, ShellDesktop.panelState.panel)
        assertTrue(queueButtonLit(), "Queue not lit while docked")
        text("Song 3").assertIsDisplayed()
        val docked = queueCard()
        val pageRow = text("Home row 0").fetchSemanticsNode().boundsInRoot
        println("queue-wide-docked: card=$docked pageRow=$pageRow")
        assertEquals(1400f, docked.right + 8f, 0.5f)
        assertTrue(docked.width + 8f in 320f..360f, "docked width ${docked.width + 8f}")
        assertTrue(pageRow.right < docked.left, "page runs under the panel: overlay, not docked")
        shot("queue-wide-docked")
        assertQueueHeadingOnOneLine("wide docked")

        resize(1080.dp, 720.dp)
        assertTrue(ShellDesktop.panelState.overlayOpen, "queue did not return as the overlay")
        assertTrue(queueButtonLit(), "Queue not lit after narrowing")
        text("Song 3").assertIsDisplayed()
        val overlay = queueCard()
        val pageRow2 = text("Home row 0").fetchSemanticsNode().boundsInRoot
        assertTrue(pageRow2.right > overlay.left, "page squeezed instead of overlaid after narrowing")
        assertTrue(overlay.width + 8f in 320f..360f)
        shot("queue-narrow-again")
        assertQueueHeadingOnOneLine("narrow overlay")
    }

    /** 4: "Next in queue" never breaks inside a word, at normal text size and at 2x. */
    @Test
    fun queueHeadingKeepsWordsWholeAtTwiceTheTextSize() = shellShots(1080.dp, 720.dp) {
        val shell = ShellDesktopFixture()
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) { shell.Content() }
        }
        clickLabel("Queue")
        assertTrue(queueButtonLit())
        shot("queue-heading-2x")
        assertQueueHeadingOnOneLine("narrow overlay 2x")
        assertQueueLengthKeepsNumbersWithUnits("narrow overlay 2x")

        // Your Library at 2x: the placeholder ends in an ellipsis instead of losing "Library" silently,
        // and the Albums chip that no longer fits is reached by scrolling the chip row.
        val placeholder = layoutOf(ui.onNode(hasText("Search in Your Library"), useUnmergedTree = true))
        println("library placeholder 2x: ${describe(placeholder)}")
        val ellipsized = (0 until placeholder.lineCount).any { placeholder.isLineEllipsized(it) }
        assertTrue(ellipsized || !placeholder.didOverflowHeight, "library placeholder is cut off with no ellipsis")
        val albums = ui.onNode(hasText("Albums") and hasRole(Role.Tab))
        albums.performScrollTo()
        click(albums)
        assertEquals(LibraryFilter.ALBUMS, ShellDesktop.libraryFilter, "the Albums chip could not be reached at 2x")
        click(albums)
        assertEquals(LibraryFilter.ALL, ShellDesktop.libraryFilter)

        resize(1400.dp, 900.dp)
        assertTrue(queueButtonLit())
        shot("queue-heading-2x-wide")
        assertQueueHeadingOnOneLine("wide docked 2x")
        assertQueueLengthKeepsNumbersWithUnits("wide docked 2x")
    }

    /** 4, Now Playing side: the docked Now Playing view has its own "Next in queue" card with Open queue. */
    @Test
    fun nowPlayingUpNextHeadingKeepsWordsWholeAtTwiceTheTextSize() = shellShots(1400.dp, 900.dp) {
        val shell = ShellDesktopFixture()
        render {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) { shell.Content() }
        }
        assertEquals(DesktopPanel.NOW_PLAYING, ShellDesktop.panelState.panel)
        val upNext = ui.onNode(hasText("Next in queue"), useUnmergedTree = true)
        upNext.performScrollToNodeIfNeeded()
        shot("queue-heading-2x-nowplaying")
        val layout = layoutOf(upNext)
        println("now-playing up next 2x: ${describe(layout)}")
        assertNoWordSplit("Now Playing up next 2x", layout)
        assertEquals(1, layout.lineCount, "Now Playing up-next heading wrapped")
    }

    /** 5: with nothing playing, Queue must not light up over an empty screen. */
    @Test
    fun queueWithNoSongDoesNotLightUp() = shellShots(1080.dp, 720.dp) {
        val shell = ShellDesktopFixture(controller = FakePlaybackController(queue = emptyList()))
        render { shell.Content() }
        clickLabel("Queue")
        val lit = queueButtonLit()
        val panelOnScreen = ui.onAllNodes(hasText("Queue")).fetchSemanticsNodes().isNotEmpty()
        println("no song: lit=$lit panelOnScreen=$panelOnScreen overlayOpen=${ShellDesktop.panelState.overlayOpen}")
        shot("queue-narrow-nosong")
        assertFalse(lit && !panelOnScreen, "Queue lit with nothing shown")
        assertFalse(lit, "Queue lit with no song")
        assertFalse(panelOnScreen, "a queue panel showed with no song")
        assertFalse(ShellDesktop.panelState.overlayOpen, "the no-song click was remembered as an open overlay")

        // A song starting later must not pop the queue over the page by itself.
        onUi { shell.controller.update { it.copy(currentSong = fakeSong(0), queue = fakeQueue(10), currentQueueIndex = 0) } }
        val popped = ui.onAllNodes(hasText("Queue")).fetchSemanticsNodes().isNotEmpty()
        println("after a song starts: overlay showing=$popped lit=${queueButtonLit()}")
        shot("queue-narrow-nosong-then-song")
        assertFalse(popped, "the queue overlay opened by itself when a song started after a no-song click")
        assertFalse(queueButtonLit(), "Queue lit after a song started, with no click since")

        // The button works as usual once there is a song.
        clickLabel("Queue")
        assertTrue(queueButtonLit(), "Queue did not open once a song was playing")
    }
}

/** The Queue player-bar button is lit (its stateDescription says On and its dot shows). */
private fun ShotScope.queueButtonLit(): Boolean =
    label("Queue").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "On"

/**
 * The right panel's visible card. Its queue list fills the card inside 16 dp side padding, so the
 * card spans the list's bounds widened by 16 dp on each side.
 */
private fun ShotScope.queueCard(): Rect {
    val list = ui.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Song 3"))).fetchSemanticsNode().boundsInRoot
    return Rect(list.left - 16f, list.top, list.right + 16f, list.bottom)
}

private fun ShotScope.assertQueueHeadingOnOneLine(where: String) {
    val heading = ui.onNode(hasText("NEXT IN QUEUE"), useUnmergedTree = true)
    heading.assertIsDisplayed()
    val layout = layoutOf(heading)
    val clear = ui.onNode(hasText("Clear queue"), useUnmergedTree = true)
    clear.assertIsDisplayed()
    val clearLayout = layoutOf(clear)
    println("$where heading: ${describe(layout)} at ${heading.fetchSemanticsNode().boundsInRoot}")
    println("$where clear: ${describe(clearLayout)} at ${clear.fetchSemanticsNode().boundsInRoot}")
    assertNoWordSplit("$where heading", layout)
    assertEquals(1, layout.lineCount, "$where: heading wrapped onto ${layout.lineCount} lines")
    assertNoWordSplit("$where Clear queue", clearLayout)
}

/** The queue's "29 SONGS · 1 H 34 MIN" line never wraps between a number and its unit ("34" / "MIN"). */
private fun ShotScope.assertQueueLengthKeepsNumbersWithUnits(where: String) {
    val node = ui.onAllNodes(hasText("SONGS ·", substring = true), useUnmergedTree = true).onFirst()
    node.assertIsDisplayed()
    val layout = layoutOf(node)
    val text = layout.layoutInput.text.text
    println("$where queue length: ${describe(layout)} \"${text.replace(' ', '_')}\"")
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        val before = text.substring(0, end).trimEnd()
        val after = text.substring(end).trimStart()
        assertFalse(
            before.lastOrNull()?.isDigit() == true && after.firstOrNull()?.isLetter() == true,
            "$where: \"$text\" wraps between a number and its unit after \"$before\"",
        )
    }
    assertNoWordSplit("$where queue length", layout)
}

private fun describe(l: TextLayoutResult): String =
    "lines=${l.lineCount} size=${l.size} paragraph=${l.multiParagraph.width}x${l.multiParagraph.height} " +
        "overflowW=${l.didOverflowWidth} overflowH=${l.didOverflowHeight} ellipsized=${(0 until l.lineCount).any { l.isLineEllipsized(it) }} " +
        "constraints=${l.layoutInput.constraints}"

private fun SemanticsNodeInteraction.performScrollToNodeIfNeeded() {
    runCatching { performScrollTo() }
}

private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
    return results.single()
}

/** Fails when a line break falls between two letters (a split word) or the text is cut off. */
private fun assertNoWordSplit(name: String, layout: TextLayoutResult) {
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        val before = text.getOrNull(end - 1)
        val after = text.getOrNull(end)
        assertFalse(
            before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true,
            "$name: \"$text\" breaks inside a word after \"${text.substring(0, end)}\"",
        )
    }
    // Not hasVisualOverflow: a maxLines = 1 Text lays its paragraph out at the full max width and reports
    // didOverflowWidth even when the line fits. What matters is an ellipsis or a line wider than the box.
    val cut = (0 until layout.lineCount).any { layout.isLineEllipsized(it) || layout.getLineRight(it) > layout.size.width + 0.5f }
    assertFalse(cut || layout.didOverflowHeight, "$name: \"$text\" is cut off")
}

/** Mean of (r+g+b)/3 over [region] of the shot, in window pixels. */
private fun meanLuminance(shot: Shot, region: Rect): Float {
    val w = region.width.toInt()
    val h = region.height.toInt()
    val pixels = IntArray(w * h)
    shot.image.readPixels(pixels, region.left.toInt(), region.top.toInt(), w, h)
    return pixels.map { p -> (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3f }.average().toFloat()
}
