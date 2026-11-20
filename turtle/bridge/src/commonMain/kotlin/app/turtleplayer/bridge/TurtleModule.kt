package app.turtleplayer.bridge

import androidx.sqlite.SQLiteDriver
import app.turtleplayer.core.Clock
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.imports.ListenBrainzHistoryImporter
import app.turtleplayer.events.imports.SpotifyHistoryImporter
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Where our database lives on this platform, and which SQLite driver opens it. */
internal class TurtleDatabaseSpec(val driver: SQLiteDriver, val path: String)

internal expect fun Scope.turtleDatabaseSpec(): TurtleDatabaseSpec

/** Device time zone, so "hour of day" features match the listener's day. */
@OptIn(ExperimentalTime::class)
internal val deviceTime = LocalTimeResolver { epochMs ->
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    local.hour to local.dayOfWeek.isoDayNumber
}

/**
 * Koin bindings for Turtle Player. The app loads this after upstream's modules, so any binding here
 * overrides upstream's (see turtle/HOOKS.md).
 */
val turtleModule: Module = module {
    single<PlayEventStore> {
        val spec = turtleDatabaseSpec()
        SqlitePlayEventStore(spec.driver, spec.path)
    }
    single { ListeningSessionTracker(get(), Clock.System, deviceTime) }
    single { PlayerObserver(get()) }
    single { SpotifyHistoryImporter(get(), deviceTime) }
    single { ListenBrainzHistoryImporter(get(), deviceTime) }
}

/**
 * Entry points for upstream hook lines. Upstream builds its players outside Koin, so each hook is a
 * single call here rather than an injected dependency.
 */
object TurtleBridge {
    /** Hook: attach the listening-event log to a player. Both platforms' players expose these flows. */
    fun observePlayer(scope: CoroutineScope, currentSong: StateFlow<SongItem?>, isPlaying: StateFlow<Boolean>, onEndOfTrack: Flow<*>) {
        GlobalContext.get().get<PlayerObserver>().attach(scope, currentSong, isPlaying, onEndOfTrack)
    }
}
