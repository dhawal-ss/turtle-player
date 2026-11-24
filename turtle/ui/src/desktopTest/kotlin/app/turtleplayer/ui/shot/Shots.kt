package app.turtleplayer.ui.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.navigationevent.NavigationEventInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import app.turtleplayer.ui.screens.DesktopPanel
import app.turtleplayer.ui.screens.LibraryFilter
import app.turtleplayer.ui.screens.ShellBrowse
import app.turtleplayer.ui.screens.ShellDesktop
import app.turtleplayer.ui.screens.ShellDesktopPlayerBar
import app.turtleplayer.ui.screens.ShellExploreSource
import app.turtleplayer.ui.screens.ShellInfinity
import app.turtleplayer.ui.screens.ShellMixDetails
import app.turtleplayer.ui.screens.ShellNavigation
import app.turtleplayer.ui.screens.ShellSearchLocal
import app.turtleplayer.ui.theme.ShellColors
import app.turtleplayer.ui.theme.ShellThemes
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.playback.LocalPlaybackController
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.awt.event.KeyEvent as AwtKeyEvent

/*
 * Headless screenshot harness for Shell's desktop UI. Compose renders on Skia into an off-screen
 * surface (no window, no network) at density 1, so 1 dp is 1 px in every PNG.
 *
 *   shellShots(1400.dp, 900.dp) {
 *       val shell = ShellDesktopFixture()
 *       render { shell.Content() }
 *       shot("case-step")          // build/screenshots/case-step.png
 *       clickLabel("Queue")        // contentDescription
 *       resize(1080.dp, 720.dp)    // the content's constraints change, as a window resize would
 *       escape()                   // Escape as the desktop window delivers it (back when unhandled)
 *   }
 */

/** Where PNGs go: turtle/ui/build/screenshots (Gradle runs tests in the module directory). */
val ShotDir: File = File(System.getProperty("user.dir"), "build/screenshots")

/** The off-screen canvas; the rendered "window" is a [DpSize] box in its top-left corner. */
private val Canvas = DpSize(2400.dp, 1600.dp)

/** Test tag on the window box: captures crop to it. */
const val WINDOW_TAG = "shot-window"

/**
 * Runs [block] with a fresh off-screen scene whose window is [width] x [height] dp. Each call is
 * independent; reset Shell's global state first ([resetShellState]).
 */
@OptIn(ExperimentalTestApi::class)
fun shellShots(width: Dp, height: Dp, block: ShotScope.() -> Unit) =
    runSkikoComposeUiTest(size = Size(Canvas.width.value, Canvas.height.value), density = Density(1f)) {
        ShotScope(this, DpSize(width, height)).block()
    }

/** One rendered scene: render, resize, interact, capture. [ui] is Compose's test API for anything else. */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ShotScope internal constructor(val ui: SkikoComposeUiTest, initial: DpSize) {
    private var windowSize by mutableStateOf(initial)

    /** What the scene's window delivers Escape to once Compose leaves it unhandled (as ComposeSceneMediator does). */
    private val backInput = EscapeBackInput()
    private val dispatcherOwner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
    }
    private val keySource = object : java.awt.Component() {}

    /** The current window size. */
    val size: DpSize get() = windowSize

    /** Sets the scene's content inside the window box, with a back dispatcher Escape reaches. */
    fun render(content: @Composable () -> Unit) {
        ui.setContent {
            DisposableEffect(Unit) {
                dispatcherOwner.navigationEventDispatcher.addInput(backInput)
                onDispose { dispatcherOwner.navigationEventDispatcher.removeInput(backInput) }
            }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcherOwner) {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    Box(Modifier.size(windowSize).testTag(WINDOW_TAG)) { content() }
                }
            }
        }
        ui.waitForIdle()
    }

    /** Resizes the window (what BoxWithConstraints and friends see) and lets layout and animations settle. */
    fun resize(width: Dp, height: Dp) {
        require(width <= Canvas.width && height <= Canvas.height) { "window larger than the ${Canvas} canvas" }
        ui.runOnUiThread { windowSize = DpSize(width, height) }
        ui.waitForIdle()
    }

    /** Runs [action] on the UI thread (navigation, state changes), then waits for idle. */
    fun onUi(action: () -> Unit) {
        ui.runOnUiThread(action)
        ui.waitForIdle()
    }

    /** Waits for recomposition and animations (the test clock auto-advances). */
    fun idle() = ui.waitForIdle()

    /** Advances the test clock by [ms] (for mid-animation shots, set `ui.mainClock.autoAdvance = false` first). */
    fun advance(ms: Long) = ui.mainClock.advanceTimeBy(ms)

    /** Saves the window as build/screenshots/[name].png and returns the file and what it holds. */
    fun shot(name: String): Shot {
        ui.waitForIdle()
        val image = ui.captureToImage(ui.onNodeWithTag(WINDOW_TAG).fetchSemanticsNode())
        ShotDir.mkdirs()
        val file = File(ShotDir, "$name.png")
        val bytes = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG encoding failed for $name")
        file.writeBytes(bytes)
        return Shot(file, image)
    }

    /** The node whose merged text equals [text] (or contains it, with [substring]). Exactly one must match. */
    fun text(text: String, substring: Boolean = false): SemanticsNodeInteraction = ui.onNode(hasText(text, substring = substring))

    /** The node with contentDescription [label] (Shell's buttons and rows set it from their label). */
    fun label(label: String, substring: Boolean = false): SemanticsNodeInteraction = ui.onNode(hasContentDescription(label, substring = substring))

    /** A sidebar destination (Home, Search, Explore, ...): role Tab with that contentDescription. */
    fun tab(label: String): SemanticsNodeInteraction = ui.onNode(hasContentDescription(label) and hasRole(Role.Tab))

    fun clickText(text: String, substring: Boolean = false) = click(text(text, substring))
    fun clickLabel(label: String, substring: Boolean = false) = click(label(label, substring))
    fun clickTab(label: String) = click(tab(label))

    fun click(node: SemanticsNodeInteraction) {
        node.performClick()
        ui.waitForIdle()
    }

    /**
     * Presses and releases [key] the way the desktop window delivers it: to Compose first (focused
     * node, then up), and, for an Escape nothing handled, to the scene's back dispatcher, which runs
     * the innermost enabled NavigationBackHandler. Returns true when Compose consumed the press.
     */
    fun pressKey(key: Key): Boolean {
        val down = awtKey(AwtKeyEvent.KEY_PRESSED, key)
        val up = awtKey(AwtKeyEvent.KEY_RELEASED, key)
        val consumed = ui.runOnUiThread {
            val handled = ui.scene.sendKeyEvent(down)
            if (!handled && key == Key.Escape) backInput.back()
            ui.scene.sendKeyEvent(up)
            handled
        }
        ui.waitForIdle()
        return consumed
    }

    /** Escape, as the desktop window delivers it (closes overlays through NavigationBackHandler). */
    fun escape() = pressKey(Key.Escape)

    private fun awtKey(id: Int, key: Key): KeyEvent =
        KeyEvent(
            key = key,
            type = if (id == AwtKeyEvent.KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.KeyUp,
            nativeEvent = AwtKeyEvent(keySource, id, System.currentTimeMillis(), 0, key.nativeKeyCode, AwtKeyEvent.CHAR_UNDEFINED),
        )
}

/** A saved screenshot: the PNG and the pixels. */
class Shot(val file: File, val image: ImageBitmap) {
    /** Distinct colours in the image (a blank or crashed render has one or two). */
    fun distinctColors(): Int {
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return pixels.toHashSet().size
    }
}

fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

/**
 * Shell's desktop frame as the desktop app draws it: [ShellNavigation] (which takes the
 * three-panel ShellDesktopLayout because TurtleUiSlots.desktopLibrary is set) over the player bar,
 * with fake playback, real navigation over fake pages, a fake library and fake player services.
 */
class ShellDesktopFixture(
    val controller: FakePlaybackController = FakePlaybackController(),
    val navigator: Navigator = fakeNavigator(),
    val library: FakeLibrary = FakeLibrary(),
    /** Null drops desktop services: the right panel then shows only Now Playing and Devices. */
    val extras: FakeExtras? = FakeExtras(),
    val playerBar: Boolean = true,
) {
    /** The frame. Registers the desktop slots, so call it inside [ShotScope.render]. */
    @Composable
    fun Content() {
        remember {
            TurtleUiSlots.desktopLibrary = library.slot
            TurtleUiSlots.desktopExtras = extras?.extras
        }
        CompositionLocalProvider(LocalPlaybackController provides controller, LocalNavigator provides navigator) {
            Column(Modifier.fillMaxSize().background(ShellThemes.active.frame)) {
                Box(Modifier.weight(1f)) { ShellNavigation(fakeNavigationSlot(navigator)) }
                if (playerBar) ShellDesktopPlayerBar(fakePlayerBarSlot())
            }
        }
    }
}

/**
 * Puts Shell's global objects back to launch defaults: ShellDesktop (panel, library sidebar,
 * restored song, queue scroll), Explore (ShellBrowse.nav and .home), the mix details page, the
 * Infinity sheet, Search's local stage, the theme and the desktop slots. Call in @BeforeTest.
 */
fun resetShellState() {
    // Back to wide, then Now Playing docked open: DesktopPanelState's initial state.
    ShellDesktop.panelState.resized(false)
    ShellDesktop.panelState.show(DesktopPanel.NOW_PLAYING)
    ShellDesktop.libraryCollapsed = false
    ShellDesktop.libraryFilter = LibraryFilter.ALL
    ShellDesktop.libraryQuery = ""
    ShellDesktop.restoredSong = null
    ShellDesktop.queueList.requestScrollToItem(0)
    ShellBrowse.nav.close()
    ShellBrowse.home = null
    ShellMixDetails.close()
    ShellInfinity.sheetOpen = false
    ShellSearchLocal.search = ShellSearchLocal.bridge
    // Explore's own load never reaches the network in tests: an idle source unless a test sets one.
    ShellExploreSource.source = FakeExploreSource()
    ShellThemes.active = ShellColors()
    TurtleUiSlots.desktopLibrary = null
    TurtleUiSlots.desktopExtras = null
}

/** Compose's desktop window feeds an unhandled Escape to its dispatcher as back (BackNavigationEventInput, internal); this does the same. */
private class EscapeBackInput : NavigationEventInput() {
    fun back() = dispatchOnBackCompleted()
}
