package app.turtleplayer.ui

import androidx.compose.material3.ColorScheme
import app.turtleplayer.bridge.turtleModule
import app.turtleplayer.ui.screens.ShellDesktopPlayerBar
import app.turtleplayer.ui.screens.ShellHome
import app.turtleplayer.ui.screens.ShellMiniPlayer
import app.turtleplayer.ui.screens.ShellNavigation
import app.turtleplayer.ui.screens.ShellNowPlaying
import app.turtleplayer.ui.screens.ShellSearch
import app.turtleplayer.ui.theme.ShellColors
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellThemes
import app.turtleplayer.ui.theme.shellMaterialScheme
import app.turtleplayer.ui.theme.shellMaterialTypography
import com.metrolist.shared.turtle.TurtleUiSlots
import org.koin.core.module.Module
import org.koin.dsl.module

/** Switches upstream's screens to Shell, the Turtle Player design (see turtle/HOOKS.md). */
object TurtleUi {
    private var cachedScheme: Triple<ShellColors, Boolean, ColorScheme>? = null

    fun install() {
        app.turtleplayer.ui.theme.ShellPalettes.restore()
        TurtleUiSlots.home = { ShellHome(it) }
        TurtleUiSlots.search = { ShellSearch(it) }
        TurtleUiSlots.miniPlayer = { ShellMiniPlayer(it) }
        TurtleUiSlots.player = { ShellNowPlaying(it) }
        TurtleUiSlots.navigation = { ShellNavigation(it) }
        TurtleUiSlots.desktopPlayerBar = { ShellDesktopPlayerBar(it) }
        TurtleUiSlots.colorScheme = { dark -> if (dark) appScheme() else null }
        TurtleUiSlots.typography = { shellMaterialTypography() }
        TurtleUiSlots.playlistPicker = { app.turtleplayer.ui.screens.ShellPlaylistPicker(it) }
        TurtleUiSlots.hideSupportPrompt = true
        TurtleUiSlots.hideListenTogether = true // the product owner scrapped Listen Together (20 Nov 2026)
        TurtleUiSlots.dialog = { app.turtleplayer.ui.screens.ShellDialog(it) }
        TurtleUiSlots.artistPage = { app.turtleplayer.ui.screens.ShellArtistPage(it) }
        TurtleUiSlots.playlistPage = { app.turtleplayer.ui.screens.ShellPlaylistPage(it) }
        TurtleUiSlots.albumPage = { app.turtleplayer.ui.screens.ShellAlbumPage(it) }
        TurtleUiSlots.libraryPage = { app.turtleplayer.ui.screens.ShellLibraryPage(it) }
        TurtleUiSlots.settingsScreen = { app.turtleplayer.ui.screens.ShellSettingsScreen(it) }
        TurtleUiSlots.menuHeader = { app.turtleplayer.ui.screens.ShellMenuHeader(it) }
        TurtleUiSlots.menuPrimaryActions = { app.turtleplayer.ui.screens.ShellMenuPrimaryActions(it) }
        TurtleUiSlots.menuActionGroup = { app.turtleplayer.ui.screens.ShellMenuActionGroup(it) }
        TurtleUiSlots.menuSurface = { app.turtleplayer.ui.screens.ShellMenuSurface(it) }
        TurtleUiSlots.podcastPage = { app.turtleplayer.ui.screens.ShellPodcastPage(it) }
    }

    /** Shell's scheme for the whole app; reading [ShellThemes.active] recomposes the app when the theme changes. */
    private fun appScheme(): ColorScheme {
        val colors = ShellThemes.active
        val desktop = ShellPlatform.desktop
        cachedScheme?.let { (c, d, scheme) -> if (c == colors && d == desktop) return scheme }
        return shellMaterialScheme(colors, desktop).also { cachedScheme = Triple(colors, desktop, it) }
    }
}

/**
 * Everything Turtle Player adds: the bridge (engines on upstream) plus Shell. The apps load this one
 * module after upstream's; creating it installs Shell's screens.
 */
val turtleAppModule: Module = module {
    includes(turtleModule)
    single(createdAtStart = true) { TurtleUi.install() }
}
