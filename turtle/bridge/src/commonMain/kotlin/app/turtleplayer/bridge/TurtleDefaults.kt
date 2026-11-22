package app.turtleplayer.bridge

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.settings.Settings

/**
 * Turtle's own defaults for upstream settings. Each is applied once (remembered in our database),
 * so a listener who later changes the setting back keeps their choice. No upstream file changes.
 */
internal object TurtleDefaults {
    /** One default: a key remembering it was applied, and how to apply it. */
    class Default(val key: String, val apply: () -> Unit)

    /**
     * Swiping the app away from recents keeps the music playing, as in Spotify. Upstream stops it,
     * which in the car looked like music stopping at random when the app was cleared.
     */
    private val keepPlayingOnTaskClear = Default("default_keep_playing_on_task_clear_v1") {
        Settings.shared.stopMusicOnTaskClear = false
    }

    private val all = listOf(keepPlayingOnTaskClear)

    suspend fun applyOnce(store: SqlitePlayEventStore) = applyOnce(all, store::pref, store::setPref)

    /** Applies each default not yet applied, then remembers it. A failing default is logged and skipped. */
    internal suspend fun applyOnce(
        defaults: List<Default>,
        pref: suspend (String) -> String?,
        setPref: suspend (String, String) -> Unit,
    ) {
        for (default in defaults) {
            try {
                if (pref(default.key) != null) continue
                default.apply()
                // Upstream settings are snapshot state: publish the change to observers outside the UI too.
                Snapshot.sendApplyNotifications()
                setPref(default.key, "applied")
                TurtleLog.info("applied Turtle default ${default.key}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TurtleLog.error("Turtle default ${default.key} failed", e)
            }
        }
    }
}
