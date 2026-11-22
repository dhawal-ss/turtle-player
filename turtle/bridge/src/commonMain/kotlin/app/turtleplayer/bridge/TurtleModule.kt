package app.turtleplayer.bridge

import androidx.sqlite.SQLiteDriver
import app.turtleplayer.core.Clock
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.imports.ListenBrainzHistoryImporter
import app.turtleplayer.events.imports.PlayerHistoryImporter
import app.turtleplayer.events.imports.SpotifyHistoryImporter
import app.turtleplayer.search.HistoryAffinityProvider
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.SearchEngine
import app.turtleplayer.taste.CatalogWithObserved
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import app.turtleplayer.taste.EmbeddingStore
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.screens.HomeShelf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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

/** The listener's calendar day, so Daily Mixes turn over at their midnight. */
@OptIn(ExperimentalTime::class)
internal val deviceDay: (Long) -> Long = { epochMs ->
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays().toLong()
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
    // Start our runtime when the app starts (in the background), not on first playback: Home needs
    // our shelves and search needs the library index before anything plays.
    single(createdAtStart = true) { TurtleAutoStart(get()) }
    single {
        val spec = turtleDatabaseSpec()
        SqlitePlayEventStore(spec.driver, spec.path)
    }
    single<PlayEventStore> { get<SqlitePlayEventStore>() }
    single { ListeningSessionTracker(get(), Clock.System, deviceTime, checkpoint = get<SqlitePlayEventStore>()) }
    single { PlayerObserver(get()) }
    single { SpotifyHistoryImporter(get(), deviceTime) }
    single { ListenBrainzHistoryImporter(get(), deviceTime) }
    single { MetrolistHistoryImport({ get<MusicDatabase>() }, get(), PlayerHistoryImporter(get(), deviceTime)) }

    single { SongCache() }
    single { LocalLibrary() }
    single { LibrarySync({ get<MusicDatabase>() }, get(), get()) }

    single { SearchEngine(library = get(), online = YouTubeOnlineSearch(get()), affinity = HistoryAffinityProvider(get())) }

    // Co-listening learned on device from radio pages and back-to-back plays (no downloaded packs yet).
    single { CoListeningGraph() }
    single { CoListeningStore(get()) }
    single<EmbeddingStore> { get<CoListeningStore>() }
    single { CoListeningSync(get(), get(), get(), get(), get()) }
    single {
        TasteEngine(
            events = get(), store = get(), catalog = CatalogWithObserved(get<LibrarySync>(), get()),
            freshReleases = YouTubeFreshReleases(get(), get(), get()) { get<MusicDatabase>() },
            upstream = YouTubeRadioSource(get(), get()), localDay = deviceDay,
            contextAt = { ms -> deviceTime.hourAndWeekday(ms).let { (hour, weekday) -> PlayContext(hour, weekday) } },
        )
    }
    single { TurtleSearch(get(), get(), get()) }
    single { TurtleHomeShelves(get(), get(), get()) }
    single { InfinityMix(get(), get(), get(), get()) }
    single { TurtleRuntime(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
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
            // Drive log heartbeat: a gap between two beats means the system froze or killed the player.
            while (isActive) {
                delay(HEARTBEAT_MS)
                DriveLog.line("heartbeat", "playing=${isPlaying.value} song=${currentSong.value?.let { "${it.id} ${it.title}" }}")
            }
        }
    }

    internal fun startInBackground() {
        runtime
    }

    /** The Infinity Mix's opening queue (empty when our runtime is not available). */
    suspend fun infinityMix(): List<SongItem> = runtime?.infinityMix().orEmpty()

    /** The Infinity Mix's next songs after the ones already [queued] (empty when unavailable). */
    suspend fun infinityNext(count: Int, queued: Set<String>): List<SongItem> =
        try {
            runtime?.infinity?.next(count, queued).orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("infinity: could not extend the mix", e)
            emptyList()
        }

    /** Forgets the Infinity Mix's tuning and picks (listening history is kept). */
    suspend fun infinityStartFresh() {
        runtime?.infinity?.startFresh()
    }

    /** Saves the genres and moods the Infinity Mix is built from. */
    suspend fun setInfinityGenres(ids: List<String>) {
        runtime?.infinity?.setGenres(ids)
    }

    /** Picks, tuning and the reason behind each mix song, for the Infinity sheet and Now Playing. */
    val infinity: StateFlow<InfinityState>
        get() = infinityStateFlow

    /** A saved Shell preference (theme and the like), kept in our database; null when unset. */
    suspend fun uiPref(key: String): String? {
        val store = waitForStore() ?: return null
        return store.pref("ui_$key")
    }

    suspend fun setUiPref(key: String, value: String) {
        waitForStore()?.setPref("ui_$key", value)
    }

    /** Our database, waiting briefly for Koin at startup (the theme is restored before Koin settles). */
    private suspend fun waitForStore(): SqlitePlayEventStore? = withTimeoutOrNull(STARTUP_WAIT_MS) {
        var store: SqlitePlayEventStore? = null
        while (store == null) {
            store = GlobalContext.getOrNull()?.getOrNull<SqlitePlayEventStore>()
            if (store == null) delay(KOIN_POLL_MS)
        }
        store
    }

    /**
     * Shell hands over upstream's playback controller while it is on screen, so the listening log can
     * tell the next button from choosing another song, and see seeks. Needs no upstream hook line.
     */
    fun attachController(controller: PlaybackController) {
        PlaybackHints.controller = controller
    }

    /** Shell's player left the screen: drop the controller so it is not kept past its Activity. */
    fun detachController(controller: PlaybackController) {
        if (PlaybackHints.controller === controller) PlaybackHints.controller = null
    }

    /** "Less like this" on a song (no-op when our runtime is unavailable). */
    suspend fun lessLikeThis(song: SongItem) { runtime?.lessLikeThis(song) }

    /** Discovery dial: 0 familiar, 0.5 balanced, 1 adventurous. Saved, and applied to the taste engine. */
    suspend fun setDiscoveryDial(position: Double) {
        discoveryDialState.value = position
        runtime?.setDiscoveryDial(position)
    }

    /** The dial's current position, restored from our database at startup. */
    val discoveryDial: StateFlow<Double> get() = discoveryDialState

    /** Our current home shelves (Turtle mixes), for screens that lay them out themselves. */
    val homeShelves: List<HomeShelf> get() = GlobalContext.getOrNull()?.getOrNull<TurtleHomeShelves>()?.shelves.orEmpty()

    /**
     * Smart Shuffle: [songs] shuffled with taste-matched recommendations mixed in (SmartShuffleBridge.kt).
     * Takes at most about 3 s; a plain shuffle when our runtime is unavailable, slow or failing.
     * [morePlaylistId]: the page showed only the first part of this YouTube playlist; the rest loads
     * within the same budget, so long playlists are shuffled whole, as upstream's Shuffle does.
     */
    suspend fun smartShuffle(songs: List<SongItem>, morePlaylistId: String? = null): List<SongItem> {
        val r = runtime
        val koin = GlobalContext.getOrNull()
        if (r == null || koin == null) return songs.shuffled()
        val rest: (suspend () -> List<SongItem>?)? = morePlaylistId?.let { id -> { wholePlaylist(id) } }
        // Its radio calls also teach the co-listening graph.
        return SmartShuffleBridge(koin.get(), koin.get(), r.observer, YouTubeRadioSource(koin.get(), koin.get())).shuffle(songs, rest)
    }

    /** Every playable song of a paged YouTube playlist; null when it fails (the caller bounds the time). */
    private suspend fun wholePlaylist(playlistId: String): List<SongItem>? =
        try {
            val repo = com.metrolist.shared.state.GlobalYouTubeRepository.instance
            repo.completePlaylistPage(repo.getPlaylistPageData(playlistId)).songs.filterNot { it.isUnavailable }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("smart shuffle: could not load the whole playlist", e)
            null
        }

    /** Hook: re-rank upstream's filtered radio page with the taste engine. */
    suspend fun rankRadio(page: RadioQueuePage): RadioQueuePage = runtime?.rankRadio(page) ?: page

    // Spoken word (TurtleSpoken.kt): podcast shelves, the audiobook catalog and per-book resume.
    /** Podcasts and audiobooks for Shell; independent of the runtime, so it works before it starts. */
    val spoken: TurtleSpoken get() = TurtleSpoken

    private const val KOIN_POLL_MS = 100L
    private const val STARTUP_WAIT_MS = 60_000L
    private const val HEARTBEAT_MS = 30_000L
}

/**
 * Whether our runtime should warm the stream resolver at startup. Android and iOS upstream already
 * do this from their UI; desktop never does, so its first play pays the full extractor warm-up.
 */
internal expect val warmStreamResolverOnStart: Boolean

/** Created eagerly by Koin; starts the runtime off the startup path once Koin has finished loading. */
class TurtleAutoStart(turtleScope: TurtleScope) {
    init {
        turtleScope.scope.launch {
            delay(AUTO_START_DELAY_MS)
            TurtleBridge.startInBackground()
        }
    }

    private companion object {
        const val AUTO_START_DELAY_MS = 1_000L
    }
}
