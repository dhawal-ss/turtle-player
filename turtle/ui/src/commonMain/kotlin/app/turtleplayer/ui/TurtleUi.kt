package app.turtleplayer.ui

import app.turtleplayer.bridge.turtleModule
import app.turtleplayer.ui.screens.ShellHome
import app.turtleplayer.ui.screens.ShellSearch
import com.metrolist.shared.turtle.TurtleUiSlots
import org.koin.core.module.Module
import org.koin.dsl.module

/** Switches upstream's screens to Shell, the Turtle Player design (see turtle/HOOKS.md #22-#23). */
object TurtleUi {
    fun install() {
        TurtleUiSlots.home = { ShellHome(it) }
        TurtleUiSlots.search = { ShellSearch(it) }
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
