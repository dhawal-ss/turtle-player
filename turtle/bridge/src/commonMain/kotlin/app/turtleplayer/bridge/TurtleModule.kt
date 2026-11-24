package app.turtleplayer.bridge

import androidx.sqlite.SQLiteDriver
import app.turtleplayer.core.Clock
import app.turtleplayer.events.HistoryLens
import app.turtleplayer.events.LensedPlayEventStore
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.imports.ListenBrainzHistoryImporter
import app.turtleplayer.events.imports.PlayerHistoryImporter
import app.turtleplayer.events.imports.SpotifyHistoryImporter
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.taste.CatalogWithObserved
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import app.turtleplayer.taste.EmbeddingStore
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.Served
import app.turtleplayer.taste.SurfaceTag
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.screens.HomeShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Upstream's "Pause listening history" (Privacy settings); read on each use so toggling it applies at once. */
internal fun listenHistoryPaused(): Boolean = Settings.shared.pauseListenHistory

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
    // History as taste reads it: through the listener's repair and reset (TasteHistoryLens), read on
    // every history read. Only since() differs; writes and the live stream go straight to the database.
    single<PlayEventStore> {
        val db = get<SqlitePlayEventStore>()
        LensedPlayEventStore(db) { HistoryLens.decode(db.pref(PREF_HISTORY_LENS)) }
    }
    single {
        ListeningSessionTracker(
            get(), Clock.System, deviceTime,
            checkpoint = get<SqlitePlayEventStore>(), recording = { !listenHistoryPaused() },
        )
    }
    single { PlayerObserver(get()) }
    single { SpotifyHistoryImporter(get(), deviceTime) }
    single { ListenBrainzHistoryImporter(get(), deviceTime) }
    single { MetrolistHistoryImport({ get<MusicDatabase>() }, get(), PlayerHistoryImporter(get(), deviceTime)) }

    single { SongCache() }
    single { LocalLibrary() }
    single { LibrarySync({ get<MusicDatabase>() }, get(), get()) }


    // Co-listening learned on device from radio pages and back-to-back plays (no downloaded packs yet).
    single { CoListeningGraph() }
    single { CoListeningStore(get()) }
    single<EmbeddingStore> { get<CoListeningStore>() }
    single { UpstreamBlocks({ get<MusicDatabase>() }) }
    single { CoListeningSync(get(), get(), get(), get(), get(), learning = { !listenHistoryPaused() }) }
    single {
        TasteEngine(
            events = get(), store = get(), catalog = CatalogWithObserved(get<LibrarySync>(), get()),
            freshReleases = YouTubeFreshReleases(get(), get(), get()) { get<MusicDatabase>() },
            upstream = YouTubeRadioSource(get(), get()), localDay = deviceDay,
            contextAt = { ms -> deviceTime.hourAndWeekday(ms).let { (hour, weekday) -> PlayContext(hour, weekday) } },
            blocks = get<UpstreamBlocks>(),
        )
    }
    single { TurtleSearch(get(), get(), get()) }
    single { TurtleHomeShelves(get(), get(), get(), get()) }
    single { TurtleHomeRows(get(), get(), get(), database = { get<MusicDatabase>() }, blockChanges = get<UpstreamBlocks>().changes) }
    single { InfinityMix(get(), get(), get(), get()) }
    single { PlaylistAddWatcher({ get<MusicDatabase>() }, get(), get(), get()) }
    // Saves with their time, for the discovery measurement; recorded only while history is recording, like plays.
    single { SaveWatcher(get(), get<LibrarySync>().saved, likes = { SongLikeState.newLikes }, recording = { !listenHistoryPaused() }) }
    single {
        TurtleRuntime(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            saves = get(),
        )
    }
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
            ours.launch { r.crashResume(currentSong, isPlaying) }
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

    /**
     * Queue indices to drop when the listener re-steers the Infinity Mix ([queueIds] is the whole
     * queue, [currentIndex] the playing song): the mix's own songs after its next two. Songs the
     * listener added stay. Empty when unavailable or the mix is no longer the listener's queue.
     */
    suspend fun infinityResteerRemovals(
        queueIds: List<String>,
        currentIndex: Int,
    ): Set<Int> =
        try {
            runtime?.infinity?.resteerRemovals(queueIds, currentIndex).orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("infinity: could not work out what to re-steer", e)
            emptySet()
        }

    /**
     * A new queue occurrence begins (Shell's Start radio): what Turtle learned and tagged for the last
     * queue stops applying. Call it before starting the new queue. No-op without our runtime.
     */
    suspend fun beginOccurrence() {
        try {
            runtime?.observer?.beginOccurrence()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("player observer: could not begin a queue occurrence", e)
        }
    }

    /** Forgets the Infinity Mix's tuning and picks (listening history is kept). */
    suspend fun infinityStartFresh() {
        runtime?.infinity?.startFresh()
    }

    /** Saves the genres and moods the Infinity Mix is built from. */
    suspend fun setInfinityGenres(ids: List<String>) {
        runtime?.infinity?.setGenres(ids)
    }

    /**
     * About how many favourites the Infinity Mix could pick from now, for the dial's explanation; null
     * when our runtime is unavailable or the count fails. Off the calling thread; never waits on a batch.
     */
    suspend fun infinityFavourites(): Int? =
        try {
            withContext(Dispatchers.Default) { runtime?.infinity?.favouriteCount() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("infinity: could not count favourites", e)
            null
        }

    /**
     * Search's local stage: songs from the listener's library for [query], shown while YouTube Music
     * answers and preferred offline. Null until the library index is ready, when nothing matches, or
     * when our runtime is unavailable (fail open). Off the calling thread.
     */
    suspend fun searchLocal(query: String): SearchSummaryPage? =
        try {
            withContext(Dispatchers.Default) { runtime?.searchLocal(query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("search: local stage unavailable", e)
            null
        }

    /**
     * Artist picks for a young profile (ArtistPicks.kt); null until first read or when our runtime is
     * absent. Compose state: Home's card and the picks sheet follow changes. Read during composition,
     * so a runtime that cannot be built (Koin only maps a missing definition to null) fails open here.
     */
    val artistPicks: ArtistPicksState?
        get() =
            try {
                GlobalContext.getOrNull()?.getOrNull<TurtleRuntime>()?.artistPicks?.state
            } catch (e: Exception) {
                TurtleLog.error("artist picks unavailable", e)
                null
            }

    /** Reads the picks state again (the sheet opening). Off the calling thread; fails open. */
    suspend fun loadArtistPicks() = artistPicksCall("read artist picks") { it.refresh() }

    /** Artists matching [query] for the picks sheet; empty offline, on failure or without our runtime. */
    suspend fun searchArtists(query: String): List<ArtistItem> =
        try {
            withContext(Dispatchers.Default) { runtime?.artistPicks?.search(query).orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("artist picks: search failed", e)
            emptyList()
        }

    /** Saves the listener's starting artists (replacing earlier ones); Made for you is rebuilt from them. */
    suspend fun saveArtistPicks(picks: List<ArtistItem>) = artistPicksCall("save artist picks") { it.save(picks) }

    /** "Skip" on Home's artist picks card. */
    suspend fun skipArtistPicks() = artistPicksCall("skip artist picks") { it.skip() }

    /** Forgets the starting artists (history is untouched). */
    suspend fun clearArtistPicks() = artistPicksCall("clear artist picks") { it.clear() }

    private suspend fun artistPicksCall(
        what: String,
        block: suspend (TurtleArtistPicks) -> Unit,
    ) {
        try {
            withContext(Dispatchers.Default) { runtime?.artistPicks?.let { block(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("could not $what", e)
        }
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

    /**
     * Shell: [songs] are about to play from [place] (row [row], a Home row key, or null). Call right
     * before starting playback. HOME_TURTLE rows carry each song's generator and slot. A YouTube
     * row's tags record each song's 1-based position in [songs], the row's songs in display order (a
     * repeated id keeps its first) and [youtubeOrder]: who ordered that row, or null for a row Turtle
     * never orders. Fails open.
     */
    fun attributePlay(
        place: PlayPlace,
        row: String?,
        songs: List<SongItem>,
        youtubeOrder: Served? = null,
    ) {
        try {
            val koin = GlobalContext.getOrNull() ?: return
            val observer = koin.getOrNull<PlayerObserver>() ?: return
            val home = koin.getOrNull<TurtleHomeShelves>()
            // Where a song sits in a YouTube row, in YouTube's order or Turtle's, is what reordering is
            // measured by. Other Home callers pass one tile or a whole mix queue.
            val youtubeRow = place == PlayPlace.HOME_YOUTUBE
            val served = if (youtubeRow) youtubeOrder else null
            val tags = LinkedHashMap<String, String>()
            songs.forEachIndexed { i, song ->
                if (song.id in tags) return@forEachIndexed
                val origin =
                    if (place == PlayPlace.HOME_TURTLE && row != null && home != null) {
                        home.originOf(row, song.id)
                    } else {
                        PlayOrigin(place, row = row, served = served)
                    }
                tags[song.id] = SurfaceTag.format(if (youtubeRow) origin.copy(position = i + 1) else origin)
            }
            observer.attribute(tags)
        } catch (e: Exception) {
            TurtleLog.error("could not attribute a play to $place", e)
        }
    }

    /** Repairing older skips and resetting taste, for Turtle's settings; current after [loadTasteRepair]. */
    val tasteRepair: StateFlow<TasteRepairState> get() = tasteRepairState

    /** Reads the saved options and counts the older skips (no-op when our runtime is unavailable). Off the calling thread. */
    suspend fun loadTasteRepair() = tasteRepairCall("read taste repair") { it.loadTasteRepair() }

    /** Turns the repair of older skips on or off; taste, Home and search follow. Off the calling thread. */
    suspend fun setRepairOlderSkips(on: Boolean) = tasteRepairCall("change the skip repair") { it.setRepairOlderSkips(on) }

    /** Resets taste (stored history is kept; explicit likes and hides stay). Off the calling thread. */
    suspend fun resetTaste() = tasteRepairCall("reset taste") { it.resetTaste() }

    /** Takes the last taste reset back. Off the calling thread. */
    suspend fun undoTasteReset() = tasteRepairCall("undo the taste reset") { it.undoTasteReset() }

    private suspend fun tasteRepairCall(
        what: String,
        block: suspend (TurtleRuntime) -> Unit,
    ) {
        try {
            withContext(Dispatchers.Default) { runtime?.let { block(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("could not $what", e)
        }
    }

    /** "Less like this" on a song (no-op when our runtime is unavailable). */
    suspend fun lessLikeThis(song: SongItem) { runtime?.lessLikeThis(song) }

    /**
     * "Not interested" on a Home song, artist or Turtle mix (no-op when our runtime is unavailable). Off the
     * calling thread: Shell calls it from the UI, and the engine re-ranks and rebuilds the profile.
     */
    suspend fun notInterested(target: NotInterested) {
        try {
            withContext(Dispatchers.Default) { runtime?.notInterested(target) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("could not hide ${target::class.simpleName}", e)
        }
    }

    /** "Show again" on the Hidden page (no-op when our runtime is unavailable). Off the calling thread, as [notInterested]. */
    suspend fun undoHidden(entry: HiddenEntry) {
        try {
            withContext(Dispatchers.Default) { runtime?.undoHidden(entry) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("could not show ${entry.kind} again", e)
        }
    }

    /** What the listener hid in Turtle, for the Hidden page. */
    val hidden: StateFlow<List<HiddenEntry>> get() = hiddenEntriesState

    /** What Home must not show. Compose state: read it during composition and Home follows changes. */
    val hiddenSet: HiddenSet get() = hiddenSetState

    /** Which Home item the next menu belongs to, for its "Not interested" row. */
    val homeMenu: HomeMenuLatch = HomeMenuLatch(Clock.System::nowMs)

    /** Discovery dial: 0 familiar, 0.5 balanced, 1 adventurous. Saved, and applied to the taste engine. */
    suspend fun setDiscoveryDial(position: Double) {
        discoveryDialState.value = position
        runtime?.setDiscoveryDial(position)
    }

    /** The dial's current position, restored from our database at startup. */
    val discoveryDial: StateFlow<Double> get() = discoveryDialState

    /** Our current home shelves (Turtle mixes), for screens that lay them out themselves. */
    val homeShelves: List<HomeShelf> get() = GlobalContext.getOrNull()?.getOrNull<TurtleHomeShelves>()?.shelves.orEmpty()

    /** What Turtle's Home row [homeKey] is made of, for its details page; null when unknown or our module is absent. */
    fun mixAbout(homeKey: String): MixAbout? = GlobalContext.getOrNull()?.getOrNull<TurtleHomeShelves>()?.about(homeKey)

    /**
     * Shell is about to save [songIds] to a playlist in one go (a Turtle mix's Save): that is one
     * action, so the playlist-add watcher records none of them as a separate "added to playlist"
     * like. Call before opening the playlist picker. Fails open.
     */
    fun expectBulkPlaylistAdd(songIds: Collection<String>) {
        try {
            GlobalContext.getOrNull()?.getOrNull<PlaylistAddWatcher>()?.expectBulk(songIds)
        } catch (e: Exception) {
            TurtleLog.error("could not mark a bulk playlist save", e)
        }
    }

    /** False until startup's Turtle shelves are published; true when our module is absent (fail open). Compose state. */
    val homeShelvesReady: Boolean get() = GlobalContext.getOrNull()?.getOrNull<TurtleHomeShelves>()?.ready ?: true

    private val homeRows: TurtleHomeRows? get() = GlobalContext.getOrNull()?.getOrNull<TurtleHomeRows>()

    /**
     * Home's Jump back in, newest first: null until first loaded, empty when our module is absent
     * (fail open). Compose state: read it during composition and Home follows changes.
     */
    val homeJumpBackIn: List<SongItem>?
        get() {
            val rows = homeRows ?: return emptyList()
            return rows.jumpBackIn
        }

    /** Which of YouTube's Home rows and grid suggestions match the listener's taste; null until scored. Compose state. */
    val homeRowsPick: HomeRowsPick? get() = homeRows?.pick

    /** Scores Home's candidate YouTube [rows] (HomeLayout.candidates) and grid suggestions [grid], off the calling thread. */
    suspend fun scoreHomeRows(
        rows: List<HomeShelf>,
        grid: List<YTItem>,
    ) {
        try {
            homeRows?.score(rows, grid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("home rows: could not score", e)
        }
    }

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

    /**
     * Hook: re-rank upstream's filtered radio page with the taste engine. [seedId] is the song the
     * station was started from; null when no station was asked for, so the playing song seeds it.
     * [songsOnly]: the queue takes no videos or podcast episodes (upstream's `recommendationsSongsOnly`),
     * which matters for pages fetched past a page of ruled-out songs, as no caller filtered them.
     */
    suspend fun rankRadio(
        page: RadioQueuePage,
        seedId: String? = null,
        songsOnly: Boolean = false,
    ): RadioQueuePage = runtime?.rankRadio(page, seedId, songsOnly) ?: page

    /**
     * YouTube Music's Explore page (moods and genres, new releases, charts, community playlists) for
     * Shell's Explore, loaded on its own (TurtleExplore.kt): signed in, Home's feed does not carry it.
     * Independent of the runtime and Koin; an empty state when the loader cannot be reached.
     */
    val explore: StateFlow<ExploreState>
        get() =
            try {
                TurtleExplore.shared.state
            } catch (e: Exception) {
                TurtleLog.error("explore unavailable", e)
                noExplore
            }

    /** Loads Explore in the background unless a fresh one is kept; [force] reloads (Retry). Never throws. */
    fun loadExplore(force: Boolean = false) {
        try {
            TurtleExplore.shared.load(force)
        } catch (e: Exception) {
            TurtleLog.error("explore: could not start a load", e)
        }
    }

    private val noExplore: StateFlow<ExploreState> = kotlinx.coroutines.flow.MutableStateFlow(ExploreState(failed = true))

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
