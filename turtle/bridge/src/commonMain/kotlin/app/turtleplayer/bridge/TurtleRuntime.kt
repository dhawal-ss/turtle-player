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
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.FeedbackKind
import app.turtleplayer.events.imports.ImportReport
import app.turtleplayer.events.imports.StagedImport
import app.turtleplayer.events.imports.commit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import app.turtleplayer.taste.eligibleExternal
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
    /** How long radio ranking may take before upstream's order is kept. */
    private val rankTimeoutMs: Long = RANK_TIMEOUT_MS,
    /** Upstream's content settings for a radio song, read when the page is served. Tests replace it. */
    private val blocked: (SongItem) -> Boolean = upstreamBlocked,
    /** The next page of a radio station; only used to page past a page of ruled-out songs. Tests replace it. */
    private val continueRadio: suspend (String) -> RadioQueuePage = youTubeRadioContinuation,
    /** Records likes and library adds with their time, for outcome measurement only (SaveWatcher.kt). */
    private val saves: SaveWatcher? = null,
) {
    private val started = AtomicBoolean(false)

    /** Completed once the saved taste state has been read (or found unreadable), so nothing saves defaults over it. */
    private val tasteRestored = CompletableDeferred<Unit>()

    /** Completed once the saved Hidden records are back (after [tasteRestored]), so radio can match other uploads of hidden songs. */
    private val hiddenRestored = CompletableDeferred<Unit>()

    // Display data of what the listener hid in Turtle (titles, thumbnails), saved under [PREF_HIDDEN].
    private val hiddenLock = Mutex()
    private var hiddenRecords: List<HiddenEntry> = emptyList()
    private val publishLock = Mutex()

    /** One history import write at a time ([commitHistoryImport], [removeImportedHistory]). */
    private val importWrites = Mutex()

    /** Repairing older skips and resetting taste: how taste reads stored history, never the history itself. */
    private val historyLens = TasteHistoryLens(store, taste, tasteRepairState)

    /** Skippable artist picks for a young profile (ArtistPicks.kt): saved with the taste state, Home rebuilt after. */
    val artistPicks =
        TurtleArtistPicks(
            taste, store, cache, coListening,
            awaitRestored = { tasteRestored.await() },
            saveTaste = { saveTasteState() },
            refreshHome = {
                home.refresh()
                homeRows.invalidate()
            },
        )

    /** Resumes after a crash or system kill mid-song, then keeps the playing mark (CrashResume.kt). */
    suspend fun crashResume(currentSong: StateFlow<SongItem?>, isPlaying: StateFlow<Boolean>) = CrashResume.run(store, currentSong, isPlaying)

    /** Idempotent. Called from the first player hook, which runs early on both platforms. */
    fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        val scope = turtleScope.scope
        DriveLog.start(scope)
        librarySync.start(scope)
        observer.startCheckpoints(scope)
        // Pausing listening history drops the play in progress at once, even when the player is in the
        // background with Shell gone. Explicit votes (likes, playlist adds, Not interested) still apply.
        observer.followHistoryPause(scope, snapshotFlow { listenHistoryPaused() })
        playlistAdds.start(scope)
        // Early: in-app like events are not replayed, so a like made before this collects is lost.
        saves?.start(scope)
        blocks.start(scope)
        scope.launch { TurtleDefaults.applyOnce(store) }
        // Load history and build the taste profile now, so the first radio page is not ranked cold.
        scope.launch {
            try {
                restoreTasteState()
            } finally {
                tasteRestored.complete(Unit)
            }
            try {
                restoreHidden()
            } finally {
                hiddenRestored.complete(Unit)
            }
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
        // Each new queue occurrence starts a fresh short-term session in the engine at once, before its
        // plays arrive, so its first ranking keeps what they teach (rankExternal's station is the backstop).
        // The first value too: otherwise the first radio page after launch, ranked for an occurrence the
        // engine was never told about, would reset away everything played since launch.
        scope.launch {
            observer.occurrences.collect { occ ->
                try {
                    taste.beginOccurrence(stationKey(occ))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TurtleLog.error("taste: could not begin queue occurrence $occ", e)
                }
            }
        }
        taste.observe(scope)
        coListening.start(scope, taste)
        artistPicks.start(scope, store.newEvents)
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
     * taste engine. [seedId] is the song the station was started from: it seeds the ranking only
     * when we know that song, and the page is ranked by taste alone otherwise.
     *
     * Every way out applies the same exclusions first ([eligibleExternal]): Not interested songs and
     * every other upload of them, muted artists, duplicate uploads, the seed's own song, upstream's
     * content settings as they are now, and, for a [songsOnly] queue, videos and podcast episodes.
     * Only ranking itself is optional: when it cannot finish in time, upstream's order is kept.
     * A page left with no song pages on (bounded), so a station of hidden songs does not stall.
     */
    suspend fun rankRadio(
        page: RadioQueuePage,
        seedId: String? = null,
        songsOnly: Boolean = false,
    ): RadioQueuePage {
        // The queue occurrence this page is for, read before anything slow: tags and the engine's station follow it.
        val gen = observer.occurrence()
        var seedFrom = "not looked up"
        var current = page
        var paged = 0
        var eligible: List<SongItem>? = null
        return try {
            cache.putAll(page.songs)
            // Hides and mutes are empty until the saved state and the Hidden records are back: wait, briefly (a cold start).
            withTimeoutOrNull(RESTORE_WAIT_MS) {
                tasteRestored.await()
                hiddenRestored.await()
            }
            // A station's own song when one was asked for, never the playing song instead of it and never
            // a candidate: callers took the station's song off the page, and the exclusions drop a candidate
            // seed. With no station asked for (a queue running on), the radio is the playing song's.
            val seedSong =
                withTimeoutOrNull(SEED_WAIT_MS) {
                    val playing = observer.currentSong()
                    when {
                        seedId == null -> playing?.also { seedFrom = "player ${it.id}" }
                        playing?.id == seedId -> playing.also { seedFrom = "player" }
                        else -> cache.get(seedId)?.also { seedFrom = "song cache" }
                    }
                }
            val seed = seedSong?.toTrackMeta()
            if (seed == null) seedFrom = "unknown, seedless"
            var songs = eligibleSongs(page, seedId, seed, songsOnly)
            eligible = songs
            if (songs.isEmpty() && page.continuation != null) {
                val more =
                    pageUntilEligible(page, MAX_EXTRA_PAGES, PAGING_DEADLINE_MS, continueRadio) { next ->
                        cache.putAll(next.songs)
                        eligibleSongs(next, seedId, seed, songsOnly)
                    }
                current = more.page
                songs = more.eligible
                eligible = songs
                paged = more.fetched
            }
            val label = seedLabel(seedId, seedFrom) + if (paged > 0) "; paged $paged" else ""
            if (songs.size < 2) {
                val kept = served(current, songs, Served.PROVIDER, gen)
                TurtleLog.info("radio: $label; kept a ${current.songs.size}-song page (${kept.songs.size} left)")
                return kept
            }
            if (observer.occurrence() != gen) {
                // The listener started something else while the page loaded: a page of the earlier queue
                // must not move the engine's session back to it, and its songs carry no tag.
                TurtleLog.info("radio: $label; the queue moved on while the page loaded, kept upstream order")
                return served(current, songs, Served.PROVIDER, gen)
            }
            val ranked =
                try {
                    withTimeoutOrNull(rankTimeoutMs) {
                        // Learned only from a known seed: an unknown one would link the wrong artist.
                        if (seed != null) coListening.observeRadio(seed, current.songs)
                        // With the profile now: it knows the recording of a hidden song the cache has forgotten.
                        val hidden = hiddenRecordings(taste.controls, taste.profile(), hiddenSongRecords(), cache)
                        val byId = songs.associateBy { it.id }
                        val metas = songs.map { it.toTrackMeta() }
                        taste.rankExternal(seed, metas, hiddenRecordings = hidden, station = stationKey(gen)).mapNotNull { item ->
                            item.track.youtubeId?.let(byId::get)?.let { song ->
                                song to SurfaceTag.format(PlayOrigin(PlayPlace.RADIO, item.source, item.slot, served = Served.RANKED))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TurtleLog.error("radio: $label; ranking failed, kept upstream order", e)
                    return served(current, songs, Served.FAILED, gen)
                }
            if (ranked == null) {
                TurtleLog.info(
                    "radio: $label; ranking exceeded ${rankTimeoutMs}ms, kept upstream order of ${songs.size} of ${current.songs.size}",
                )
                return served(current, songs, Served.TIMEOUT, gen)
            }
            // Tagged only once ranking is done, so a timeout never leaves songs marked as ranked; and only
            // for the occurrence the page was ranked for: a result that comes back after it ended is not.
            observer.tagAll(ranked.map { (song, tag) -> song.id to tag }, gen)
            val stale = if (observer.occurrence() != gen) " for an earlier queue, untagged" else ""
            TurtleLog.info("radio: $label; ranked ${ranked.size} of ${current.songs.size} candidates$stale")
            current.copy(songs = ranked.map { it.first })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the steps before ranking get here (the seed lookup, paging); the page keeps the basic exclusions.
            TurtleLog.error("radio: ${seedLabel(seedId, seedFrom)}; could not prepare the page, kept upstream order", e)
            served(current, eligible ?: basicExclusions(current, seedId, songsOnly), Served.FAILED, gen)
        }
    }

    private fun seedLabel(
        seedId: String?,
        from: String,
    ) = if (seedId == null) "no station asked, seed $from" else "seed $seedId ($from)"

    /** The saved Hidden page records (titles and artists of hidden songs), read under their lock. */
    private suspend fun hiddenSongRecords(): List<HiddenEntry> = hiddenLock.withLock { hiddenRecords }

    /**
     * [page]'s songs the listener may be offered, in upstream's order, by the rule ranking applies
     * ([eligibleExternal]) plus upstream's content settings ([blocked]) and, for a [songsOnly] queue,
     * no videos or episodes. The station's own song ([seedId]) is never a candidate, even when its
     * recording is unknown: pages fetched past the first skipped the caller's filter that drops it.
     * Lock-free on the engine: its controls are read without its mutex, and the profile is skipped.
     * Falls back to [basicExclusions] when a step fails.
     */
    private suspend fun eligibleSongs(
        page: RadioQueuePage,
        seedId: String?,
        seed: TrackMeta?,
        songsOnly: Boolean,
    ): List<SongItem> =
        try {
            val controls = taste.controls
            val hidden = hiddenRecordings(controls, profile = null, records = hiddenSongRecords(), cache = cache)
            val playable = page.songs.filterNot { it.id == seedId || (songsOnly && it.notASong()) || blocked(it) }
            eligibleExternal(seed, playable.map { it.toTrackMeta() }, controls, hidden).backTo(playable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("radio: could not apply every exclusion, kept Not interested songs and muted artists out", e)
            basicExclusions(page, seedId, songsOnly)
        }

    /**
     * The last resort: [page]'s songs without Not interested uploads and muted artists (and, for a
     * [songsOnly] queue, videos and episodes), the rule that needs nothing but the engine's controls.
     * Only when even that fails is the page kept as it came.
     */
    private fun basicExclusions(
        page: RadioQueuePage,
        seedId: String?,
        songsOnly: Boolean,
    ): List<SongItem> =
        try {
            val controls = taste.controls
            page.songs.filterNot { song ->
                if (song.id == seedId || (songsOnly && song.notASong())) return@filterNot true
                val meta = song.toTrackMeta()
                meta.artistKey in controls.mutedArtists || meta.key in controls.lessLikeThisTracks
            }
        } catch (e: Exception) {
            TurtleLog.error("radio: could not apply the listener's exclusions", e)
            page.songs
        }

    /**
     * [page] with [songs] (upstream's order, minus what the listener ruled out), tagged with why it
     * was not ranked ([served]). Chips and continuation stay, even when no song is left.
     */
    private suspend fun served(
        page: RadioQueuePage,
        songs: List<SongItem>,
        served: Served,
        gen: Long,
    ): RadioQueuePage {
        val kept = if (songs == page.songs) page else page.copy(songs = songs)
        tagServed(kept, served, gen)
        return kept
    }

    /**
     * Tags a radio page we return unranked with why ([served]); no source or slot, so per-source
     * learning ignores it. Only while occurrence [gen] lasts. Bounded and never throws: the
     * observer's lock can be busy saving a play.
     */
    private suspend fun tagServed(
        page: RadioQueuePage,
        served: Served,
        gen: Long,
    ) {
        val detail = SurfaceTag.format(PlayOrigin(PlayPlace.RADIO, served = served))
        try {
            withTimeoutOrNull(TAG_TIMEOUT_MS) { observer.tagAll(page.songs.map { it.id to detail }, gen) }
                ?: TurtleLog.info("radio: could not tag ${page.songs.size} songs as ${served.tag} in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("radio: could not tag songs as ${served.tag}", e)
        }
    }

    /**
     * The listener's own songs for [query], before YouTube answers (or without it): null until the
     * library index has finished its first sync, when nothing matches, or on failure.
     */
    suspend fun searchLocal(query: String): SearchSummaryPage? {
        if (query.isBlank() || !librarySync.isReady()) return null
        return try {
            search.local(query.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("search: local stage failed", e)
            null
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
                // Tunes the Infinity Mix only when this play is one of its own picks.
                observer.currentOrigin()?.takeIf { it.place == PlayPlace.INFINITY }?.let { origin ->
                    observer.currentSong()?.let { infinity.feedback(it, liked = true, origin = origin) }
                }
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
        // The playing song's own origin; another song is judged by the live mix's picks alone.
        infinity.feedback(song, liked = false, origin = if (observer.currentSong()?.id == song.id) observer.currentOrigin() else null)
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

    /** Reads the saved taste repair options and counts the older skips, for the settings. */
    suspend fun loadTasteRepair() {
        historyLens.load()
    }

    /** "Repair older skips" on or off: taste, Home and search read history again. */
    suspend fun setRepairOlderSkips(on: Boolean) {
        // The reset's exploration snapshot and the rebuild save the taste state: never before it is restored.
        tasteRestored.await()
        if (historyLens.setRepairOlderSkips(on)) historyReadingChanged()
    }

    /** "Reset taste": plays stored so far stop counting (they are kept, and the reset can be undone). */
    suspend fun resetTaste() {
        tasteRestored.await()
        if (historyLens.reset()) historyReadingChanged()
    }

    /** "Undo reset". */
    suspend fun undoTasteReset() {
        tasteRestored.await()
        if (historyLens.undoReset()) historyReadingChanged()
    }

    /**
     * Stores a previewed history import (Spotify, ListenBrainz: stage it with the Koin importer, show
     * [StagedImport.preview], then call this on the listener's confirmation), then rebuilds taste,
     * Home and search from it. The write and the rebuild finish even if the caller is canceled, so
     * an import is never stored without the app reading it. Throws [IllegalStateException] when the
     * preview cannot be committed or is stale; stored history is then unchanged.
     */
    suspend fun commitHistoryImport(staged: StagedImport): ImportReport {
        tasteRestored.await()
        return withContext(NonCancellable) {
            // One write at a time: a preview's stale check and its replacement never interleave with another.
            val report = importWrites.withLock { store.commit(staged) }
            TurtleLog.info("history import: stored ${report.plays} plays (${report.distinctTracks} songs)")
            historyReadingChanged()
            report
        }
    }

    /** Removes every imported play from [source] (never this device's own plays) and rebuilds from what is left. */
    suspend fun removeImportedHistory(source: EventSource) {
        require(source != EventSource.LOCAL) { "plays recorded on this device are not an import" }
        tasteRestored.await()
        withContext(NonCancellable) {
            importWrites.withLock { store.replaceSource(source, emptyList()) }
            TurtleLog.info("history import: removed imported plays")
            historyReadingChanged()
        }
    }

    /** How taste reads history changed: rebuild the profile now, then Home, Jump back in and search. */
    private suspend fun historyReadingChanged() {
        runCatching { taste.refresh() }
            .onFailure { if (it is CancellationException) throw it else TurtleLog.error("taste: could not rebuild after a history change", it) }
        // Shelves kept for the day or week were built from the old reading.
        taste.refreshShelves()
        saveTasteState()
        search.historyChanged()
        turtleScope.scope.launch {
            homeRows.onControlsChanged()
            home.refresh()
        }
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
        const val RESTORE_WAIT_MS = 1_000L

        /** How long the radio seed lookup may wait on the player's lock before ranking goes seedless. */
        const val SEED_WAIT_MS = 500L

        /** Continuation pages fetched past a page of ruled-out songs, and their total time: the listener waits on them. */
        const val MAX_EXTRA_PAGES = 3
        const val PAGING_DEADLINE_MS = 2_000L
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
