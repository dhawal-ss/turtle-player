package app.turtleplayer.bridge

import kotlinx.coroutines.flow.StateFlow
import app.turtleplayer.search.DocType
import app.turtleplayer.search.LexicalQuery
import app.turtleplayer.search.LocalItem
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.FeedbackKind
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import app.turtleplayer.taste.DiscoveryDial
import app.turtleplayer.taste.Hidden
import app.turtleplayer.taste.LessLikeThis
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.Served
import app.turtleplayer.taste.SurfaceTag
import app.turtleplayer.taste.TasteEngine
import app.turtleplayer.taste.hidden
import app.turtleplayer.taste.undo
import com.metrolist.shared.api.searchSummaryOverride
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.screens.homeShelvesOverride
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.libraryContentSearchOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** Wires our engines into the running app once, and serves the upstream hook calls. */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class TurtleRuntime(
    private val turtleScope: TurtleScope,
    val observer: PlayerObserver,
    private val librarySync: LibrarySync,
    private val library: LocalLibrary,
    private val taste: TasteEngine,
    private val cache: SongCache,
    private val search: TurtleSearch,
    private val home: TurtleHomeShelves,
    private val store: SqlitePlayEventStore,
    val infinity: InfinityMix,
    private val coListening: CoListeningSync,
    private val history: MetrolistHistoryImport,
    private val playlistAdds: PlaylistAddWatcher,
    private val blocks: UpstreamBlocks,
    private val homeRows: TurtleHomeRows,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val started = AtomicBoolean(false)

    /** Completed once the saved taste state has been read (or found unreadable), so nothing saves defaults over it. */
    private val tasteRestored = CompletableDeferred<Unit>()

    // Display data of what the listener hid in Turtle (titles, thumbnails), saved under [PREF_HIDDEN].
    private val hiddenLock = Mutex()
    private var hiddenRecords: List<HiddenEntry> = emptyList()
    private val publishLock = Mutex()

    /** Resumes after a crash or system kill mid-song, then keeps the playing mark (CrashResume.kt). */
    suspend fun crashResume(currentSong: StateFlow<SongItem?>, isPlaying: StateFlow<Boolean>) = CrashResume.run(store, currentSong, isPlaying)

    /** Idempotent. Called from the first player hook, which runs early on both platforms. */
    fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        val scope = turtleScope.scope
        DriveLog.start(scope)
        librarySync.start(scope)
        observer.startCheckpoints(scope)
        playlistAdds.start(scope)
        blocks.start(scope)
        scope.launch { TurtleDefaults.applyOnce(store) }
        // Load history and build the taste profile now, so the first radio page is not ranked cold.
        scope.launch {
            try {
                restoreTasteState()
            } finally {
                tasteRestored.complete(Unit)
            }
            restoreHidden()
            // Home's Turtle row keys are rebuilt with the shelves (a cluster mix's id is its index), so
            // which rows are dismissed is worked out again for each new set.
            scope.launch { snapshotFlow { home.shelves.map { it.key } }.collect { publishHidden() } }
            restoreDiscoveryDial()
            infinity.restore()
            // Before the first profile build, so a listener coming from Metrolist is not cold.
            runCatching { history.runOnce() }.onFailure { if (it is CancellationException) throw it else TurtleLog.error("history import failed", it) }
            // A listener coming from Metrolist sees the imported plays without waiting for their next play.
            homeRows.refreshJumpBackIn()
            runCatching { taste.refresh() }
            // Home may have been scored on the profile from before the import: score it again.
            homeRows.invalidate()
        }
        // After the saved controls are back, so the first Jump back in already leaves out muted artists.
        // Its own launch: Home's grid waits for Jump back in, so a failed restore must not stop it.
        scope.launch {
            tasteRestored.await()
            homeRows.start(scope)
        }
        taste.observe(scope)
        coListening.start(scope, taste)
        // Finishes and quick skips of Infinity songs tune their genre without a tap; every play also
        // moves the exploration state, which is saved with the listener's controls.
        scope.launch {
            store.newEvents.collect {
                runCatching { infinity.onPlayEvent(it) }
                saveTasteState()
            }
        }
        libraryContentSearchOverride = ::searchLibrary
        searchSummaryOverride = ::rerankSearch
        homeShelvesOverride = home::mergeInto
        scope.launch {
            // Shelf items resolve through the library mirror, so build shelves after its first sync.
            while (!librarySync.isReady()) delay(500)
            // After the saved kept shelves are back in the engine, so restored rows are not replaced by a fresh build.
            tasteRestored.await()
            home.start(scope)
        }
        // Liked and library songs (YouTube Music likes included) count toward taste from the first launch.
        scope.launch {
            librarySync.saved.collect { saved ->
                if (saved != null) runCatching { taste.setSavedTracks(saved) }.onFailure { if (it is CancellationException) throw it else TurtleLog.error("could not apply saved songs", it) }
            }
        }
        if (warmStreamResolverOnStart) scope.launch { warmStreamResolver() }
        scope.launch { watchLikes() }
        TurtleLog.info("runtime started")
    }

    /** Echo Music-style warm-up: pays the extractor and token setup before the first play, not on it. */
    private suspend fun warmStreamResolver() {
        val resolver = withTimeoutOrNull(RESOLVER_WAIT_MS) {
            var r = GlobalYouTubeRepository.streamResolver
            while (r == null) {
                delay(200)
                r = GlobalYouTubeRepository.streamResolver
            }
            r
        } ?: return
        val startedAt = TimeSource.Monotonic.markNow()
        runCatching { resolver.prewarm() }
            .onSuccess { TurtleLog.info("stream resolver warmed in ${startedAt.elapsedNow().inWholeMilliseconds}ms") }
            .onFailure { if (it is CancellationException) throw it else TurtleLog.error("stream resolver warm-up failed", it) }
    }

    /**
     * Orders upstream's radio candidates (already filtered for blocked and queued songs) with the
     * taste engine. Keeps upstream's page whenever we cannot do better in time.
     */
    suspend fun rankRadio(page: RadioQueuePage): RadioQueuePage {
        if (page.songs.size < 2) return page.also { tagServed(it, Served.PROVIDER) }
        return try {
            // Radio pages often load as playback starts, before the observer has seen a song.
            val seed = observer.currentSong() ?: page.songs.first()
            val ranked =
                withTimeoutOrNull(RANK_TIMEOUT_MS) {
                    cache.putAll(page.songs)
                    coListening.observeRadio(seed.toTrackMeta(), page.songs)
                    val byId = page.songs.associateBy { it.id }
                    taste.rankExternal(seed.toTrackMeta(), page.songs.map { it.toTrackMeta() }).mapNotNull { item ->
                        item.track.youtubeId?.let(byId::get)?.let { song ->
                            song to SurfaceTag.format(PlayOrigin(PlayPlace.RADIO, item.source, item.slot, served = Served.RANKED))
                        }
                    }
                }
            if (ranked == null) {
                TurtleLog.info("radio: kept upstream order (ranking exceeded ${RANK_TIMEOUT_MS}ms)")
                tagServed(page, Served.TIMEOUT)
                return page
            }
            // Tagged only once ranking is done, so a timeout never leaves songs marked as ranked.
            for ((song, tag) in ranked) observer.tag(song.id, tag)
            TurtleLog.info("radio: ranked ${ranked.size} of ${page.songs.size} candidates")
            page.copy(songs = ranked.map { it.first })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("radio: ranking failed, kept upstream order", e)
            tagServed(page, Served.FAILED)
            page
        }
    }

    /**
     * Tags a radio page we return unranked with why ([served]); no source or slot, so per-source
     * learning ignores it. Bounded and never throws: the observer's lock can be busy saving a play.
     */
    private suspend fun tagServed(
        page: RadioQueuePage,
        served: Served,
    ) {
        val detail = SurfaceTag.format(PlayOrigin(PlayPlace.RADIO, served = served))
        try {
            withTimeoutOrNull(TAG_TIMEOUT_MS) { page.songs.forEach { observer.tag(it.id, detail) } }
                ?: TurtleLog.info("radio: could not tag ${page.songs.size} songs as ${served.tag} in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("radio: could not tag songs as ${served.tag}", e)
        }
    }

    /** Re-ranks upstream's online search page; null keeps upstream's page. */
    private suspend fun rerankSearch(query: String, page: SearchSummaryPage): SearchSummaryPage? =
        try {
            search.rerank(query, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("search: re-ranking failed, kept upstream results", e)
            null
        }

    /** A like made anywhere in the app becomes a taste signal for the song playing now. */
    private suspend fun watchLikes() {
        var previous: Set<String>? = null
        snapshotFlow { SongLikeState.likedSongIds }.collect { liked ->
            val before = previous
            previous = liked
            if (before == null) return@collect
            val current = observer.currentSong()?.id ?: return@collect
            if (current in liked && current !in before) {
                observer.feedback(FeedbackKind.LIKED)
                observer.currentSong()?.let { infinity.feedback(it, liked = true) }
            }
            if (current !in liked && current in before) observer.feedback(FeedbackKind.UNLIKED)
        }
    }

    /** "Less like this" on a song: recorded on its play event and applied to the taste engine at once. */
    suspend fun lessLikeThis(song: SongItem) {
        // Restoring the saved controls replaces them, so a change made before that would be lost.
        tasteRestored.await()
        val meta = song.toTrackMeta()
        taste.lessLikeThis(LessLikeThis.Track(meta.key, meta.artistKey))
        saveTasteState()
        rememberHidden(songEntry(song))
        publishHidden()
        infinity.feedback(song, liked = false)
        if (observer.currentSong()?.id == song.id) observer.feedback(FeedbackKind.LESS_LIKE_THIS)
        refreshHome()
    }

    /**
     * "Not interested" on a Home item: a song is ruled out through a stored feedback event (its play is
     * not marked and it keeps playing), an artist is muted, a Turtle mix is dismissed (a no-op once the
     * mix has left Home). Turtle never writes YouTube dislikes.
     */
    suspend fun notInterested(target: NotInterested) {
        tasteRestored.await()
        val entry =
            when (target) {
                is NotInterested.Song -> {
                    val feedback = TrackFeedback(track = target.song.toTrackMeta(), kind = TrackFeedbackKind.NOT_INTERESTED, atMs = nowMs())
                    val id = store.appendFeedback(feedback)
                    taste.onFeedback(feedback.copy(id = id))
                    songEntry(target.song)
                }

                is NotInterested.Artist -> {
                    // Songs carry the raw artist name, so the mute is keyed on it; the alias is only the title.
                    val artist = ArtistKey.of(target.artist.title)
                    taste.updateControls { it.copy(mutedArtists = it.mutedArtists + artist) }
                    HiddenEntry(HiddenKind.ARTIST, artist.value, target.title, thumbnail = target.artist.thumbnail)
                }

                is NotInterested.Mix -> {
                    val shelf = home.engineShelf(target.homeKey) ?: return
                    taste.lessLikeThis(shelf.lessLikeThis)
                    HiddenEntry(HiddenKind.MIX, shelf.reason.key, target.title)
                }
            }
        saveTasteState()
        rememberHidden(entry)
        publishHidden()
        refreshHome()
    }

    /** "Show again" on the Hidden page: takes the entry back, play-level marks included. */
    suspend fun undoHidden(entry: HiddenEntry) {
        tasteRestored.await()
        when (entry.kind) {
            HiddenKind.SONG -> {
                val key = TrackKey(entry.key)
                val cached = if (key.scheme == TrackKey.YOUTUBE) cache.get(key.id) else null
                // The track's own artist, so the undo takes back the artist's mark too (a record may lack it).
                val track =
                    cached?.toTrackMeta()
                        ?: taste.profile().track(key)?.meta
                        ?: TrackMeta(key, entry.title, listOfNotNull(entry.artist))
                val feedback = TrackFeedback(track = track, kind = TrackFeedbackKind.UNDO_NOT_INTERESTED, atMs = nowMs())
                val id = store.appendFeedback(feedback)
                taste.onFeedback(feedback.copy(id = id))
            }

            HiddenKind.ARTIST -> {
                taste.updateControls { it.undo(Hidden.Artist(ArtistKey(entry.key))) }
            }

            HiddenKind.MIX -> {
                taste.updateControls { it.undo(Hidden.Shelf(entry.key)) }
            }
        }
        hiddenLock.withLock { hiddenRecords = hiddenRecords.filterNot { it.kind == entry.kind && it.key == entry.key } }
        saveTasteState()
        saveHiddenRecords()
        publishHidden()
        refreshHome()
    }

    /** Home follows the listener's new controls in the background: callers (the player's skip) must not wait on it. */
    private fun refreshHome() {
        turtleScope.scope.launch { homeRows.onControlsChanged() }
    }

    private fun songEntry(song: SongItem) =
        HiddenEntry(HiddenKind.SONG, song.toTrackMeta().key.value, song.title, song.artists.firstOrNull()?.name, song.thumbnail)

    private suspend fun rememberHidden(entry: HiddenEntry) {
        hiddenLock.withLock { hiddenRecords = hiddenRecords.filterNot { it.kind == entry.kind && it.key == entry.key } + entry }
        saveHiddenRecords()
    }

    private suspend fun saveHiddenRecords() {
        val text = hiddenLock.withLock { HiddenRecords.encode(hiddenRecords) }
        runCatching { store.setPref(PREF_HIDDEN, text) }
            .onFailure { if (it is CancellationException) throw it else TurtleLog.error("could not save the hidden list", it) }
    }

    private suspend fun restoreHidden() {
        try {
            val saved = HiddenRecords.decode(store.pref(PREF_HIDDEN))
            hiddenLock.withLock { hiddenRecords = saved }
            publishHidden()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("could not restore the hidden list", e)
        }
    }

    /**
     * Publishes what the listener hid in Turtle (never upstream's block list, which keeps its own pages):
     * the Hidden page's rows, titled from the saved records or the engine, and what Home filters out.
     */
    private suspend fun publishHidden() {
        try {
            // One at a time: each reads the controls when it starts, so the last to publish is the newest.
            publishLock.withLock { publishHiddenNow() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("could not publish the hidden list", e)
        }
    }

    private suspend fun publishHiddenNow() {
        // Records first: a record is added only after its control, so every record read here has its
        // control in [controls], and one added meanwhile is never pruned as stale.
        val records = hiddenLock.withLock { hiddenRecords }
        val controls = taste.listenerControls
        val entries =
            controls.hidden().map { h ->
                when (h) {
                    is Hidden.Track -> {
                        records.find(HiddenKind.SONG, h.track.value)
                            ?: HiddenEntry(HiddenKind.SONG, h.track.value, taste.displayName(h.track) ?: h.track.value)
                    }

                    is Hidden.Artist -> {
                        records.find(HiddenKind.ARTIST, h.artist.value)
                            ?: HiddenEntry(HiddenKind.ARTIST, h.artist.value, taste.displayName(h.artist) ?: h.artist.value)
                    }

                    is Hidden.Shelf -> {
                        records.find(HiddenKind.MIX, h.reasonKey) ?: HiddenEntry(HiddenKind.MIX, h.reasonKey, h.reasonKey)
                    }
                }
            }
        val listed = entries.mapTo(HashSet()) { it.kind to it.key }
        val stale =
            hiddenLock.withLock {
                val kept = hiddenRecords.filter { it !in records || (it.kind to it.key) in listed }
                (kept.size != hiddenRecords.size).also { hiddenRecords = kept }
            }
        if (stale) saveHiddenRecords()
        hiddenEntriesState.value = entries
        val dismissedRows =
            home.shelves.map { it.key }.filterTo(HashSet()) { key ->
                val reason = home.engineShelf(key)?.reason ?: return@filterTo false
                reason.key in controls.suppressedReasons
            }
        hiddenSetState =
            HiddenSet(
                songIds = controls.lessLikeThisTracks.filter { it.scheme == TrackKey.YOUTUBE }.mapTo(HashSet()) { it.id },
                artists = controls.mutedArtists,
                homeShelfKeys = dismissedRows,
            )
    }

    private fun List<HiddenEntry>.find(
        kind: HiddenKind,
        key: String,
    ) = firstOrNull { it.kind == kind && it.key == key }

    /** Discovery dial position: 0 familiar, 0.5 balanced, 1 adventurous. Saved across launches. */
    suspend fun setDiscoveryDial(position: Double) {
        val p = position.coerceIn(0.0, 1.0)
        taste.setDiscoveryDial(DiscoveryDial.fromPosition(p))
        runCatching { store.setPref(PREF_DIAL, p.toString()) }.onFailure { TurtleLog.error("could not save the discovery dial", it) }
    }

    /** "Less like this", mutes and the exploration state; without this they were lost at every restart. */
    private suspend fun saveTasteState() {
        // A play recorded at startup must not save defaults over the state (and the day's shelves) not yet read.
        tasteRestored.await()
        runCatching { writeTasteState(store, taste) }
            .onFailure { if (it is CancellationException) throw it else TurtleLog.error("could not save taste state", it) }
    }

    private suspend fun restoreTasteState() {
        val saved = runCatching { store.pref(PREF_TASTE_STATE) }.getOrNull() ?: return
        if (!taste.importState(saved)) TurtleLog.error("saved taste state unreadable; starting from defaults")
    }

    private suspend fun restoreDiscoveryDial() {
        val saved = runCatching { store.pref(PREF_DIAL)?.toDoubleOrNull() }.getOrNull() ?: return
        discoveryDialState.value = saved
        taste.setDiscoveryDial(DiscoveryDial.fromPosition(saved))
    }

    /**
     * The Infinity Mix's opening queue: the taste engine's Up Next as playable songs. Upstream's
     * autoplay keeps it going forever, and our radio ranking keeps it tailored as you like and skip.
     * Empty when the engine has nothing yet (no history); callers then fall back to quick picks.
     */
    suspend fun infinityMix(): List<SongItem> =
        try {
            infinity.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("infinity: could not build a queue", e)
            emptyList()
        }

    /**
     * Typo-tolerant, relevance-ranked library search replacing upstream's SQL `LIKE`. Returns null
     * (keep upstream's query) until our index has finished its first sync.
     */
    suspend fun searchLibrary(query: String, offset: Int): List<SongItem>? {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        if (!librarySync.isReady()) {
            TurtleLog.info("library search: index not ready, upstream query used")
            return null
        }
        val hits = library.read { lexical, _ ->
            // History-only songs share the index, so over-fetch before keeping library songs.
            lexical.search(LexicalQuery.of(term, asYouType = false), limit = (offset + PAGE_SIZE) * 2, types = setOf(DocType.TRACK))
        }
        return hits.asSequence()
            .mapNotNull { (it.item as? LocalItem.Track)?.track }
            .filter { LocalSource.LIBRARY in it.sources }
            .drop(offset)
            .take(PAGE_SIZE)
            .mapNotNull { it.meta.youtubeId }
            .toList()
            .mapNotNull { cache.get(it) }
            .also { TurtleLog.info("library search: ${it.size} results from Turtle index") }
    }

    private companion object {
        const val RANK_TIMEOUT_MS = 1_500L
        const val TAG_TIMEOUT_MS = 200L
        const val PAGE_SIZE = 200
        const val RESOLVER_WAIT_MS = 30_000L
        const val PREF_DIAL = "discovery_dial"
        const val PREF_HIDDEN = "hidden_items"
    }
}

/** The saved [TasteEngine.exportState] in our pref table; Home saves it after every shelf build too. */
internal const val PREF_TASTE_STATE = "taste_state"

/** Held from export to write, so an older export (a play's) never lands after a newer one (a build's). */
private val tasteStateWrites = Mutex()

/** Saves [taste]'s state under [PREF_TASTE_STATE]. The runtime saves after every play, Home after every build. */
internal suspend fun writeTasteState(
    store: SqlitePlayEventStore,
    taste: TasteEngine,
) {
    tasteStateWrites.withLock { store.setPref(PREF_TASTE_STATE, taste.exportState()) }
}

/** The discovery dial as the UI shows it; balanced until our saved value is restored. */
internal val discoveryDialState = kotlinx.coroutines.flow.MutableStateFlow(0.5)
