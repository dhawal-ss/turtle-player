package app.turtleplayer.ui.shot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.screens.ShellBrowse
import app.turtleplayer.ui.screens.ShellDesktop
import com.metrolist.shared.ui.navigation.Screen
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Renders Shell's desktop frame headless at a wide and a narrow window and saves both. */
class ShellDesktopSmokeShotTest {
    @BeforeTest
    fun reset() = resetShellState()

    @AfterTest
    fun cleanUp() = resetShellState()

    @Test
    fun rendersTheFrameWideAndNarrow() = shellShots(1400.dp, 900.dp) {
        val shell = ShellDesktopFixture()
        render { shell.Content() }
        tab("Home").assertIsDisplayed()
        tab("Explore").assertIsDisplayed()
        label("Queue").assertIsDisplayed()
        val wide = shot("smoke-wide")
        assertTrue(wide.distinctColors() > 20, "smoke-wide looks blank")

        resize(1080.dp, 720.dp)
        label("Queue").assertIsDisplayed()
        val narrow = shot("smoke-narrow")
        assertTrue(narrow.distinctColors() > 20, "smoke-narrow looks blank")
    }

    /** The harness's own controls: a click, Escape, Explore with Home data, and a pushed and popped route. */
    @Test
    fun harnessDrivesClicksKeysAndNavigation() = shellShots(1080.dp, 720.dp) {
        val shell = ShellDesktopFixture()
        render { shell.Content() }

        clickLabel("Queue")
        assertTrue(ShellDesktop.panelState.overlayOpen, "Queue did not open the overlay")
        text("Song 3").assertIsDisplayed()
        shot("smoke-narrow-queue")
        escape()
        assertFalse(ShellDesktop.panelState.overlayOpen, "Escape did not close the overlay")

        val home = FakeHome()
        onUi { ShellBrowse.home = home.slot }
        clickTab("Explore")
        text("Genre 0").assertIsDisplayed()
        shot("smoke-narrow-explore")

        onUi { shell.navigator.navigate(Screen.Playlist("p1")) }
        text("Playlist p1").assertIsDisplayed()
        shot("smoke-narrow-pushed")
        onUi { shell.navigator.goBack() }
        text("Genre 0").assertIsDisplayed()
        escape()
        assertEquals(null, ShellBrowse.nav.entry, "Escape did not close Explore")
        text("Home row 0").assertIsDisplayed()
    }
}
