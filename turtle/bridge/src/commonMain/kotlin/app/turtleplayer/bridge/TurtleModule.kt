package app.turtleplayer.bridge

import androidx.sqlite.SQLiteDriver
import app.turtleplayer.core.Clock
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.imports.ListenBrainzHistoryImporter
import app.turtleplayer.events.imports.SpotifyHistoryImporter
import app.turtleplayer.search.HistoryAffinityProvider
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.SearchEngine
import app.turtleplayer.taste.EmbeddingStore
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import org.koin.core.context.GlobalContext
import org.koin.core.error.NoDefinitionFoundException
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

/** App-lifetime work (index sync, taste updates), independent of any screen or service. */
class TurtleScope(
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> TurtleLog.error("background task failed", t) },
    ),
)

/**
 * Koin bindings for Turtle Player. The app loads this after upstream's modules, so any binding here
 * overrides upstream's (see turtle/HOOKS.md). Upstream's `MusicDatabase` is registered by the app
 * modules on both platforms.
 */
val turtleModule: Module = module {
    single { TurtleScope() }
    single<PlayEventStore> {
        val spec = turtleDatabaseSpec()
        SqlitePlayEventStore(spec.driver, spec.path)
    }
    single { ListeningSessionTracker(get(), Clock.System, deviceTime) }
    single { PlayerObserver(get()) }
    single { SpotifyHistoryImporter(get(), deviceTime) }
    single { ListenBrainzHistoryImporter(get(), deviceTime) }

    single { SongCache() }
    single { LocalLibrary() }
    single { LibrarySync({ get<MusicDatabase>() }, get(), get()) }

    single { SearchEngine(library = get(), online = YouTubeOnlineSearch(get()), affinity = HistoryAffinityProvider(get())) }

    // Co-listening packs are not trained yet; until then the engine ranks on listening history alone.
    single<EmbeddingStore> { InMemoryEmbeddingStore() }
    single { TasteEngine(events = get(), store = get(), catalog = get<LibrarySync>(), upstream = YouTubeRadioSource(get())) }
    single { TurtleRuntime(get(), get(), get(), get(), get(), get()) }
}

/**
 * Entry points for upstream hook lines. Upstream builds its players and queues outside Koin, so
 * each hook is a single call here rather than an injected dependency. Every entry point fails
 * open: if our code throws or Koin is not ready, upstream's own behavior is kept.
 */
object TurtleBridge {
    private class NotReady : Exception()

    /**
     * Our runtime, or null when Koin or our module is not loaded yet. `startKoin` publishes the
     * global context before it finishes loading modules, so "Koin exists" is not "we exist".
     */
    private fun runtimeOrThrow(): TurtleRuntime {
        val koin = GlobalContext.getOrNull() ?: throw NotReady()
        return try {
            koin.get<TurtleRuntime>().also { it.start() }
        } catch (_: NoDefinitionFoundException) {
            throw NotReady()
        }
    }

    private val runtime: TurtleRuntime?
        get() = try {
            runtimeOrThrow()
        } catch (_: NotReady) {
            null
        } catch (e: Exception) {
            TurtleLog.error("runtime unavailable; upstream behavior kept", e)
            null
        }

    /**
     * Hook: attach the listening-event log to a player. Both platforms' players expose these flows.
     * Desktop builds its player before Koin is up, so this retries until our module is loaded; the
     * current song is a StateFlow, so nothing playing at attach time is missed.
     */
    fun observePlayer(scope: CoroutineScope, currentSong: StateFlow<SongItem?>, isPlaying: StateFlow<Boolean>, onEndOfTrack: Flow<*>) {
        val ours = isolatedChild(scope, "player observer")
        ours.launch {
            val r = withTimeoutOrNull(STARTUP_WAIT_MS) {
                var found: TurtleRuntime? = null
                while (found == null) {
                    found = try {
                        runtimeOrThrow()
                    } catch (_: NotReady) {
                        delay(KOIN_POLL_MS)
                        null
                    }
                }
                found
            }
            if (r == null) {
                TurtleLog.error("turtleModule not loaded after ${STARTUP_WAIT_MS / 1000}s; listening events are not recorded")
                return@launch
            }
            r.observer.attach(ours, currentSong, isPlaying, onEndOfTrack)
            TurtleLog.info("player observer attached")
        }
    }

    /** Hook: re-rank upstream's filtered radio page with the taste engine. */
    suspend fun rankRadio(page: RadioQueuePage): RadioQueuePage = runtime?.rankRadio(page) ?: page

    private const val KOIN_POLL_MS = 100L
    private const val STARTUP_WAIT_MS = 60_000L
}
