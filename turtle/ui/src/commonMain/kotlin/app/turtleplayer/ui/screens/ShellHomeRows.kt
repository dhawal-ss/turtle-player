package app.turtleplayer.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.turtleplayer.bridge.HomeYouTubeRows
import app.turtleplayer.bridge.TurtleBridge
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** "YouTube Music suggestions on Home": which of YouTube's rows Home shows, saved in our database. */
internal object ShellHomeRows {
    private val scope = MainScope()

    /** Null until restored from our database (then the saved value, or MATCHED). Compose state. */
    var mode: HomeYouTubeRows? by mutableStateOf(null)
        private set

    /** Reads the saved choice; called once at startup. Always sets [mode], because Home's grid waits for it. */
    fun restore() {
        scope.launch {
            val saved = runCatching { TurtleBridge.uiPref(HomeYouTubeRows.PREF_KEY) }.getOrNull()
            // A choice made while the saved one was loading wins.
            if (mode == null) mode = HomeYouTubeRows.fromPref(saved)
        }
    }

    fun choose(m: HomeYouTubeRows) {
        mode = m
        scope.launch { runCatching { TurtleBridge.setUiPref(HomeYouTubeRows.PREF_KEY, m.prefValue) } }
    }
}
