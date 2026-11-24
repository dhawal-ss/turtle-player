package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.cleanArtistName
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.tanh
import kotlin.random.Random

data class TasteConfig(
    val affinity: AffinityWeights = AffinityWeights(),
    val session: SessionConfig = SessionConfig(),
    val clustering: ClusteringConfig = ClusteringConfig(),
    val queueRules: QueueRules = QueueRules(),
    val budget: GeneratorBudget = GeneratorBudget(),
    /** Candidates gathered per refresh across all generators. */
    val candidateTarget: Int = 500,
    /** Radio stays in the seed's neighbourhood: fewer, closer candidates. */
    val radioCandidateTarget: Int = 120,
    val generatorTimeoutMs: Long = 2_000,
    val upNextSize: Int = 20,
    val shelfSize: Int = 30,
    /** How much history the profile is built from. */
    val historyDays: Int = 730,
    val rediscoverAfterDays: Int = 60,
    val freshWindowDays: Int = 28,
    /** Finished plays after which onboarding artists stop carrying recommendations. */
    val coldStartPlays: Int = 50,
    val dailyMixFamiliarShare: Double = 0.7,
    /** On repeat: tracks finished within this window, at most [onRepeatSize]. */
    val onRepeatDays: Int = 30,
    val onRepeatSize: Int = 20,
    /** Artist mixes (Daily Mix fallback without clusters). */
    val artistMixes: Int = 3,
    val artistMixFamiliarShare: Double = 0.6,
    /** Artist mixes and Weekly Discovery from upstream radio are dropped below this size. */
    val minFallbackShelfSize: Int = 5,
    val weeklyDiscoveryFallbackSize: Int = 25,
    /** Upstream radio (network) calls per shelf refresh, and tracks asked per call. */
    val shelfUpstreamSeeds: Int = 5,
    val shelfUpstreamPerSeed: Int = 50,
    /** Which of upstream's Home rows are kept, and how they are scored ([TasteEngine.selectHomeRows]). */
    val homeRows: HomeRowConfig = HomeRowConfig(),
)

/**
 * The on-device recommendation engine: the one entry point the app talks to.
 *
 * Typical use from the bridge:
 * - construct once with the app's [PlayEventStore], an [EmbeddingStore] loaded from the downloaded
 *   packs, the [TrackCatalog] and the optional seams;
 * - call [observe] (or [onPlayEvent] for each recorded event) and [nowPlaying] on track changes;
 * - [startAutoplay] / [startRadio] when playback starts, [upNext] for the queue, [homeShelves] for home;
 * - title shelves from their typed [Reason]s, resolving keys with [displayName];
 * - persist [exportState] (controls, exploration, recent recommendations and kept shelves) between
 *   runs, and [importState] it at start.
 *
 * All public functions are serialized by one mutex; generators run concurrently inside a refresh.
 */
class TasteEngine(
    private val events: PlayEventStore,
    private val store: EmbeddingStore,
    private val catalog: TrackCatalog,
    freshReleases: FreshReleaseSource? = null,
    private val upstream: RecommendationSource? = null,
    private val representation: TrackRepresentation = TrackRepresentation(store),
    private val ranker: Ranker = HandTunedRanker(),
    private val config: TasteConfig = TasteConfig(),
    private val clock: Clock = Clock.System,
    random: Random = Random.Default,
    controls: UserControls = UserControls(),
    exploration: ExplorationState = ExplorationState(),
    recentlyRecommended: List<TrackKey> = emptyList(),
    /** Local calendar day (days since 1970-01-01 in the listener's time zone) of an epoch time, for shelf cadence. */
    private val localDay: (Long) -> Long = { it.floorDiv(DAY_MS) },
    /**
     * The listening context (local hour, weekday, device) at an epoch time, so rankings use the time
     * now rather than the last play's. Null: the last play's context is used.
     */
    private val contextAt: ((Long) -> PlayContext)? = null,
    /** The app's own block list, merged into [controls] live and never saved. */
    private val blocks: ExternalBlocks? = null,
) {
    private val mutex = Mutex()
    private val policy = ExplorationPolicy(random)
    /** Song identities across uploads (one cache for the profile, queues and shelves). */
    private val songs = SongKeys()
    private val assembler = QueueAssembler(config.queueRules, songs)

    private val sessionGen = SessionNeighborsGenerator(store, catalog)
    private val clusterGen = TasteClusterGenerator(store, catalog)
    private val favoritesGen = FavoritesGenerator()
    private val cfGen = CollaborativeFilteringGenerator(store, catalog)
    private val freshGen = freshReleases?.let { FreshReleaseGenerator(it, config.freshWindowDays) }
    private val rediscoveryGen = RediscoveryGenerator(config.rediscoverAfterDays)
    private val upstreamGen = upstream?.let { UpstreamRadioGenerator(it) }
    private val onboardingGen = OnboardingGenerator(store, catalog, config.coldStartPlays)

    /** Generator order is reason priority when several propose the same track. Onboarding is empty once warm. */
    private val autoplayCollector = collector(listOfNotNull(onboardingGen, sessionGen, clusterGen, cfGen, freshGen, rediscoveryGen, upstreamGen))
    private val radioCollector = collector(listOfNotNull(sessionGen, cfGen, upstreamGen), config.radioCandidateTarget)
    private val discoveryCollector = collector(listOf(onboardingGen, clusterGen, cfGen))

    /** Shelves kept for their cadence ([ShelfCadence]): the period they were built in, and the shelves. */
    private val stable = HashMap<ShelfCadence, Pair<Long, List<Shelf>>>()

    private var builder = TasteProfileBuilder(config.affinity, songs)
    private var saved: List<SavedTrack> = emptyList()
    private var clusters: List<TasteCluster> = emptyList()
    private var profile: TasteProfile = TasteProfile.empty()
    private var loaded = false

    private val session = SessionModel(representation.dim, config.session)
    private var queue: List<QueueItem> = emptyList()
    private var autoplayActive = false
    private var nowPlayingTrack: TrackMeta? = null
    private var radioSeed: RadioSeed? = null
    private var context: PlayContext? = null

    // Short-term session boundaries. The session model, the quick-skip run and the skipped-artist
    // window belong to one queue occurrence ([beginOccurrence]) and one listening session (the
    // tracker's sessionId, or idle time); controls, exploration and the profile carry on.
    private var occurrenceKey: String? = null

    // Occurrences already left, most recent last: a page ranked for one of them that comes back late
    // must not move the session back to it (and reset away what the current one taught).
    private val leftOccurrences = ArrayDeque<String>()
    private var sessionKey: String? = null
    private var lastActivityMs = Long.MIN_VALUE
    private var sessionStartMs = Long.MIN_VALUE
    private val recent = RecentlyRecommended(config.queueRules.recentMemory).also { it.restore(recentlyRecommended) }

    // The listener's own controls. Volatile, and the merge cache is one immutable holder, because
    // [controls] may be read outside the mutex.
    @Volatile
    private var own: UserControls = controls

    private class Merged(val own: UserControls, val blocks: BlockedSet, val result: UserControls)

    @Volatile
    private var merged: Merged? = null

    /** Only what the listener set in Turtle: what [exportState] saves and the Hidden page lists. */
    val listenerControls: UserControls get() = own

    /** What ranking uses: the listener's controls plus the app's block list. Safe to read from any thread. */
    val controls: UserControls
        get() {
            val o = own
            val b = blocks?.current() ?: return o
            merged?.let { if (it.own === o && it.blocks === b) return it.result }
            return o.withBlocks(b).also { merged = Merged(o, b, it) }
        }

    var explorationState: ExplorationState = exploration
        private set

    /**
     * Replaces the exploration posterior, e.g. a fresh one for a taste reset and the saved one for its
     * undo. Controls, recent picks and kept shelves are left as they are (unlike [importState]).
     */
    suspend fun setExploration(state: ExplorationState) = mutex.withLock { explorationState = state }

    private fun collector(generators: List<CandidateGenerator>, target: Int = config.candidateTarget) =
        CandidateCollector(generators, config.budget, target, config.generatorTimeoutMs)

    // ---- Lifecycle and events ------------------------------------------------------------------

    /** Rebuilds the profile and taste clusters from the event store. Call daily and after imports. */
    suspend fun refresh() = mutex.withLock { refreshLocked() }

    /**
     * The listener's liked and library songs (from YouTube Music too), so taste is not cold on day
     * one. Replaces the previous list; rebuilds the profile only when it changed.
     */
    suspend fun setSavedTracks(tracks: List<SavedTrack>) = mutex.withLock {
        if (tracks == saved) return@withLock
        saved = tracks
        if (loaded) refreshLocked()
    }

    /**
     * The [EmbeddingStore] changed (for example the co-listening graph was rebuilt): forgets cached
     * vectors and recomputes the profile's taste clusters and long-term direction.
     */
    suspend fun embeddingsChanged() = mutex.withLock {
        representation.clearCache()
        if (loaded) refreshLocked()
    }

    /** Collects new events from the store until [scope] is cancelled. Do not also call [onPlayEvent]. */
    fun observe(scope: CoroutineScope): Job = scope.launch { events.newEvents.collect { onPlayEvent(it) } }

    /**
     * Feeds one recorded play: updates affinities, the session model and the exploration
     * posterior, then re-ranks Up Next beyond the next two items. Returns the new Up Next.
     */
    suspend fun onPlayEvent(event: PlayEvent): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        builder.add(event)
        event.context?.let { context = it }
        if (event.lessLikeThis) own = own.apply(LessLikeThis.Track(event.trackKey, event.artistKey))
        // Only the play's own tag says it was a discovery pick: a song that was once ranked and is
        // played again from elsewhere (a search, a playlist) teaches the exploration policy nothing.
        val slot = SurfaceTag.parse(event.surfaceDetail)?.slot
        if (slot == SlotKind.DISCOVERY) explorationState = policy.update(explorationState, policy.outcomeOf(event))
        updateProfileLocked()
        if (event.source == EventSource.LOCAL) {
            val now = clock.nowMs()
            // A new listening session (the tracker's own boundary) starts a fresh short-term session.
            // The event arrives when the play closes, so the new session reaches back to its start.
            if (sessionKey != null && event.sessionId != sessionKey) resetShortTermLocked(now, since = minOf(now, event.startedAtMs))
            sessionKey = event.sessionId
            lastActivityMs = now
        }
        // A play that began before the short-term session did (the song that was playing when the listener
        // started something else, reported when it closes) belongs to the last occurrence: the profile
        // learns from it, the new session does not.
        if (event.startedAtMs >= sessionStartMs) session.onPlay(event, representation.vectorFor(event.track))
        if (nowPlayingTrack?.key == event.trackKey) nowPlayingTrack = null
        consumeThrough(event.trackKey)
        if (autoplayActive) rerankLocked()
        queue
    }

    /**
     * Applies a non-play feedback event the app has already stored: ADDED_TO_PLAYLIST raises affinity;
     * NOT_INTERESTED rules the track out (as [LessLikeThis.Track]) and drops it from Up Next;
     * UNDO_NOT_INTERESTED brings it back (controls and profile); SAVED is ignored (measurement only,
     * saves reach taste through [setSavedTracks]). Not collected by [observe].
     */
    suspend fun onFeedback(f: TrackFeedback) {
        if (f.kind == TrackFeedbackKind.SAVED) return
        mutex.withLock {
            // Not loaded yet: the load reads the stored event, so adding it here would count it twice.
            val wasLoaded = loaded
            ensureLoaded()
            if (wasLoaded) builder.addFeedback(f)
            when (f.kind) {
                TrackFeedbackKind.ADDED_TO_PLAYLIST -> Unit
                TrackFeedbackKind.NOT_INTERESTED -> {
                    own = own.apply(LessLikeThis.Track(f.track.key, f.track.artistKey))
                    queue = queue.filterNot { it.key == f.track.key }
                }
                TrackFeedbackKind.UNDO_NOT_INTERESTED -> own = own.undo(Hidden.Track(f.track.key, f.track.artistKey))
                TrackFeedbackKind.SAVED -> Unit
            }
            updateProfileLocked()
            if (autoplayActive && f.kind != TrackFeedbackKind.ADDED_TO_PLAYLIST) rerankLocked()
        }
    }

    /** Tells the engine which track is playing now; drops it (and anything skipped over) from Up Next. */
    suspend fun nowPlaying(track: TrackMeta, context: PlayContext? = null): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        context?.let { this.context = it }
        nowPlayingTrack = track
        val wasQueued = queue.any { it.key == track.key }
        consumeThrough(track.key)
        if (autoplayActive && !wasQueued) rerankLocked()
        queue
    }

    // ---- Autoplay and radio --------------------------------------------------------------------

    /**
     * Starts autoplay after [nowPlaying] (or from long-term taste when null). Returns Up Next.
     * With [continuous] false the queue is built once and not re-ranked on later plays, for callers
     * that take the list and manage playback themselves (each re-rank may call the upstream radio).
     */
    suspend fun startAutoplay(nowPlaying: TrackMeta? = null, context: PlayContext? = null, continuous: Boolean = true): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        context?.let { this.context = it }
        resetSessionLocked()
        nowPlayingTrack = nowPlaying
        nowPlaying?.let { representation.vectorFor(it) }?.let { session.seed(it, mass = 1.0) }
        radioSeed = null
        autoplayActive = true
        rerankLocked()
        autoplayActive = continuous
        queue
    }

    /** Stops re-ranking Up Next on every play (see [startAutoplay]). */
    suspend fun stopAutoplay() = mutex.withLock {
        autoplayActive = false
        radioSeed = null
    }

    /** Starts radio from any track or artist: a seeded session model. The shelf becomes Up Next. */
    suspend fun startRadio(seed: RadioSeed, context: PlayContext? = null): Shelf = mutex.withLock {
        ensureLoaded()
        context?.let { this.context = it }
        resetSessionLocked()
        radioSeed = seed
        nowPlayingTrack = (seed as? RadioSeed.Track)?.track
        seedVector(seed)?.let { session.seed(it) }
        autoplayActive = true
        rerankLocked()
        Shelf(shelfId(ShelfKind.RADIO, Reason.RadioFrom(seed).key), ShelfKind.RADIO, Reason.RadioFrom(seed), queue)
    }

    /**
     * Orders a candidate list someone else produced, such as YouTube radio that the app has already
     * filtered for blocked and queued songs. Uses our ranker, exploration share and queue rules but
     * mixes in nothing else, so the caller's filters stay authoritative. Muted artists are dropped;
     * tracks that break spacing rules go to the end rather than being lost. A null [seed] (the
     * requested song is unavailable) ranks by taste alone and drops no candidate as the seed.
     * [hiddenRecordings] are the song identities ([SongKeys.of]) of what the listener hid, so another
     * upload of a hidden song stays out too; see [eligibleExternal].
     *
     * [station] is the queue occurrence the page is for (see [beginOccurrence]); a key the engine
     * has not seen starts a fresh short-term session, as a backstop when [beginOccurrence] lags; one
     * already left (a page that came back after the listener moved on) leaves the session alone.
     * Null (smart shuffle) leaves the session alone. Either way, after [SessionConfig.idleResetMs]
     * without a play the session starts fresh.
     */
    suspend fun rankExternal(
        seed: TrackMeta?,
        tracks: List<TrackMeta>,
        source: CandidateSource = CandidateSource.UPSTREAM_RADIO,
        hiddenRecordings: Set<TrackKey> = emptySet(),
        station: String? = null,
    ): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        val now = clock.nowMs()
        if (station != null && station != occurrenceKey && station !in leftOccurrences) {
            enterOccurrenceLocked(station, now, now)
        } else if (lastActivityMs != Long.MIN_VALUE && now - lastActivityMs > config.session.idleResetMs) {
            // Nothing closed since lastActivityMs, so every play that began after it belongs to the new session.
            resetShortTermLocked(now, since = lastActivityMs - config.session.boundarySlackMs)
        }
        // One upload per song, never another upload of the seed itself, and nothing the listener ruled out.
        val unique = eligibleExternal(seed, tracks, controls, hiddenRecordings, songs)
        if (unique.isEmpty()) return@withLock emptyList()
        val skippedNow = sessionSkippedArtistsLocked(now)
        val seedVector = seed?.let { representation.vectorFor(it) }
        val extractor = FeatureExtractor(profile, controls, session.vector(), now, contextLocked(now), seedVector)
        val reason = Reason.UpstreamRadio(seed?.key)
        val scored = unique.mapIndexedNotNull { i, t ->
            val v = representation.vectorFor(t)
            if (controls.isInMutedCluster(v)) return@mapIndexedNotNull null
            val c = Candidate(t, source, reason, 1f - 0.5f * i / unique.size)
            val f = extractor.extract(c, v)
            ScoredCandidate(c, v, f, ranker.score(f))
        }.sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.candidate.key.value })
        // Artists skipped again and again this session wait at the back, as they would with a person picking.
        val (ranked, pushedBack) = scored.partition { it.candidate.track.artistKey !in skippedNow }
        val share = policy.sampleShare(explorationState, controls.discoveryDial)
        val slots = policy.planSlots(ranked.size, share)
        val keys = ranked.mapTo(HashSet()) { it.candidate.key }
        val laidOut = assembler.assemble(emptyList(), ranked, slots, profile.recentPlays, seed, recent, now, allowAgain = keys)
        val placed = laidOut.mapTo(HashSet()) { it.key }
        val leftovers = (ranked.filter { it.candidate.key !in placed } + pushedBack).map { QueueItem.of(it) }
        val result = laidOut + leftovers
        // Only the page head is likely to play soon; marking a whole 50-song page "recently recommended"
        // would starve other surfaces of those songs.
        for (item in result.take(config.upNextSize)) recent.add(item.key)
        result
    }

    /**
     * A new queue occurrence (a Home mix, a station, the Infinity Mix) begins under [key]: the
     * short-term session (session vector, quick-skip run, skipped-artist window, finished and
     * skipped lists) starts fresh, so the last occurrence does not steer this one. Controls, the
     * exploration posterior, the recently-recommended memory and the profile carry on. Idempotent
     * for the same key, so a later [rankExternal] with that station keeps what was learned since.
     * [atMs] is when the occurrence began; plays that started up to [SessionConfig.boundarySlackMs]
     * before it still count for it (the boundary is often noticed just after its first song starts).
     * A key already left is ignored.
     */
    suspend fun beginOccurrence(
        key: String,
        atMs: Long = clock.nowMs(),
    ) = mutex.withLock {
        if (key != occurrenceKey && key !in leftOccurrences) enterOccurrenceLocked(key, clock.nowMs(), atMs)
    }

    private fun enterOccurrenceLocked(
        key: String,
        now: Long,
        atMs: Long,
    ) {
        occurrenceKey?.let { left ->
            leftOccurrences.remove(left)
            leftOccurrences.addLast(left)
            while (leftOccurrences.size > MAX_LEFT_OCCURRENCES) leftOccurrences.removeFirst()
        }
        occurrenceKey = key
        resetShortTermLocked(now, since = minOf(now, atMs) - config.session.boundarySlackMs)
    }

    /** Current Up Next (starts autoplay from long-term taste if nothing is active). */
    suspend fun upNext(): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        if (!autoplayActive) {
            autoplayActive = true
            rerankLocked()
        }
        queue
    }

    /**
     * Long-term-taste picks laid out with the queue rules, for callers that play them themselves (the
     * Infinity Mix). Read-only: the session, Up Next, the radio seed and the recently-recommended
     * memory are untouched, so calling it while music plays changes nothing.
     */
    suspend fun forYou(limit: Int = config.upNextSize): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        val now = clock.nowMs()
        val request = GenerationRequest(profile, controls, now, sessionVector = profile.longTermVector)
        val ranked = rankLocked(autoplayCollector, request)
        val slots = policy.planSlots(limit, policy.sampleShare(explorationState, controls.discoveryDial))
        assembler.assemble(emptyList(), ranked, slots, profile.recentPlays, null, recent, now)
    }

    /** Mood chip: nudge the session toward [target] (null clears). Re-ranks beyond the next two. */
    suspend fun setMood(target: FloatArray?, strength: Float = 0.5f): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        session.setMood(target, strength)
        if (autoplayActive) rerankLocked()
        queue
    }

    // ---- User controls -------------------------------------------------------------------------

    /** "Less like this" on a track, artist or shelf. Removes affected tracks from Up Next and re-ranks. */
    suspend fun lessLikeThis(target: LessLikeThis): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        own = own.apply(target)
        if (target is LessLikeThis.Track) queue = queue.filterNot { it.key == target.track }
        if (target is LessLikeThis.ForReason) queue = queue.filterNot { it.reason.key == target.reason.key }
        if (autoplayActive) rerankLocked()
        queue
    }

    /** Applies a change to the listener's controls; an active Up Next is re-ranked beyond the next two. */
    suspend fun updateControls(transform: (UserControls) -> UserControls) {
        mutex.withLock {
            own = transform(own)
            if (loaded) {
                updateProfileLocked()
                if (autoplayActive) rerankLocked()
            }
        }
    }

    suspend fun setDiscoveryDial(dial: DiscoveryDial) = updateControls { it.copy(discoveryDial = dial) }

    /** Onboarding: favourite artists that carry recommendations until history exists. */
    suspend fun onboard(artists: List<ArtistKey>) = updateControls { it.copy(onboardingArtists = artists.distinct()) }

    suspend fun muteCluster(cluster: TasteCluster) = updateControls { it.copy(mutedClusters = it.mutedClusters + ClusterAnchor.of(cluster.centroid)) }

    suspend fun boostCluster(cluster: TasteCluster) = updateControls { it.copy(boostedClusters = it.boostedClusters + ClusterAnchor.of(cluster.centroid)) }

    /** Current profile snapshot (for the editable taste profile screen). */
    suspend fun profile(): TasteProfile = mutex.withLock {
        ensureLoaded()
        profile
    }

    /**
     * True while history is too thin to rank on (fewer than [TasteConfig.coldStartPlays] finished
     * plays): Home offers onboarding picks then. The one place that threshold is read from outside.
     */
    suspend fun coldStart(): Boolean = mutex.withLock {
        ensureLoaded()
        profile.finishedPlays < config.coldStartPlays
    }

    suspend fun recentlyRecommended(): List<TrackKey> = mutex.withLock { recent.toList() }

    // ---- Shelves -------------------------------------------------------------------------------

    /**
     * Home, in order: Daily Mixes (artist mixes without vectors), More like X, On repeat, Weekly
     * Discovery, Fresh for You, Rediscover. Empty and dismissed shelves are omitted.
     *
     * Without co-listening vectors the mixes, More like X and Weekly Discovery are built from
     * history plus the upstream radio. Upstream is called at most [TasteConfig.shelfUpstreamSeeds]
     * times per call, concurrently, failures tolerated, and never without listening history.
     */
    suspend fun homeShelves(): List<Shelf> {
        class Plan(
            val now: Long,
            val radios: RadioCache,
            val mixPlan: List<TrackStats>?,
            val pick: MoreLikePick?,
            val weeklyRanked: List<ScoredCandidate>?,
            val seeds: List<TrackMeta>,
        )
        val plan = mutex.withLock {
            ensureLoaded()
            val now = clock.nowMs()
            // Null = kept from earlier in its period: no work, no upstream calls.
            val mixPlan = if (keptLocked(ShelfCadence.DAILY, now) == null) artistMixPlanLocked() else null
            val pick = if (keptLocked(ShelfCadence.DAILY_MORE_LIKE, now) == null) moreLikePickLocked() else null
            val weeklyRanked = if (keptLocked(ShelfCadence.WEEKLY, now) == null) vectorWeeklyRankedLocked() else null
            val seeds = buildList {
                mixPlan?.forEach { add(it.meta) }
                if (pick != null && pick.vector == null) add(pick.stats.meta)
                if (weeklyRanked != null && weeklyRanked.isEmpty()) discoverySeedsLocked().forEach { add(it.meta) }
            }
            Plan(now, radioCacheLocked(), mixPlan, pick, weeklyRanked, seeds)
        }
        // The upstream radios load without holding the engine: play events and radio ranking go on meanwhile.
        plan.radios.prefetch(plan.seeds)
        return mutex.withLock {
            val now = plan.now
            buildList {
                addAll(stableLocked(ShelfCadence.DAILY, now) { plan.mixPlan?.let { dailyMixesLocked(it, plan.radios) }.orEmpty() })
                addAll(stableLocked(ShelfCadence.DAILY_MORE_LIKE, now) { listOfNotNull(plan.pick?.let { moreLikeLocked(it, plan.radios) }) })
                add(onRepeatLocked())
                addAll(stableLocked(ShelfCadence.WEEKLY, now) { plan.weeklyRanked?.let { listOf(weeklyDiscoveryLocked(it, plan.radios)) }.orEmpty() })
                addAll(stableLocked(ShelfCadence.DAILY_FRESH, now) { listOfNotNull(freshForYouLocked()) })
                addAll(stableLocked(ShelfCadence.DAILY_REDISCOVER, now) { listOf(rediscoverLocked()) })
            }.let(::visibleLocked)
        }
    }

    /**
     * Home's shelves that are already decided for their period (kept or restored), plus On repeat,
     * in [homeShelves] order and with the same filters. Builds nothing and calls nothing upstream,
     * so Home can show Turtle's rows at once after a restart.
     */
    suspend fun keptShelves(): List<Shelf> = mutex.withLock {
        ensureLoaded()
        val now = clock.nowMs()
        buildList {
            addAll(keptLocked(ShelfCadence.DAILY, now).orEmpty())
            addAll(keptLocked(ShelfCadence.DAILY_MORE_LIKE, now).orEmpty())
            add(onRepeatLocked())
            addAll(keptLocked(ShelfCadence.WEEKLY, now).orEmpty())
            addAll(keptLocked(ShelfCadence.DAILY_FRESH, now).orEmpty())
            addAll(keptLocked(ShelfCadence.DAILY_REDISCOVER, now).orEmpty())
        }.let(::visibleLocked)
    }

    /** What Home shows of [shelves]: not empty, and not dismissed by the listener. */
    private fun visibleLocked(shelves: List<Shelf>) =
        shelves.filter { it.items.isNotEmpty() && it.reason.key !in controls.suppressedReasons }

    /**
     * Forgets kept shelves, so the next [homeShelves] builds every shelf afresh. Deliberately not
     * wired to pull-to-refresh: Turtle's mixes are the day's and discovery the week's (personal-home
     * design, W2).
     */
    suspend fun refreshShelves() = mutex.withLock { stable.clear() }

    private fun periodOf(cadence: ShelfCadence, nowMs: Long): Long {
        val day = localDay(nowMs)
        return if (cadence.weekly) day - ((day + 3).mod(7L)) else day // weeks start on Monday (1970-01-01 was a Thursday)
    }

    /** The shelves kept for [cadence] in the current period, with what the listener has ruled out since removed. */
    private fun keptLocked(cadence: ShelfCadence, nowMs: Long): List<Shelf>? {
        val (period, shelves) = stable[cadence] ?: return null
        if (period != periodOf(cadence, nowMs)) return null
        return shelves.map { shelf ->
            shelf.copy(items = shelf.items.filter { it.key !in controls.lessLikeThisTracks && it.track.artistKey !in controls.mutedArtists })
        }
    }

    private suspend fun stableLocked(cadence: ShelfCadence, nowMs: Long, build: suspend () -> List<Shelf>): List<Shelf> {
        keptLocked(cadence, nowMs)?.let { return it }
        val built = build()
        // An empty build (offline, no history yet) is retried at the next refresh instead of kept.
        if (built.any { it.items.isNotEmpty() }) stable[cadence] = periodOf(cadence, nowMs) to built
        return built
    }

    /** Daily Mixes: one per taste cluster, or up to three artist mixes while there are no clusters. */
    suspend fun dailyMixes(): List<Shelf> {
        val (plan, radios) = mutex.withLock {
            ensureLoaded()
            artistMixPlanLocked() to radioCacheLocked()
        }
        radios.prefetch(plan.map(TrackStats::meta))
        return mutex.withLock { dailyMixesLocked(plan, radios) }
    }

    suspend fun weeklyDiscovery(): Shelf = mutex.withLock {
        ensureLoaded()
        val ranked = vectorWeeklyRankedLocked()
        val radios = radioCacheLocked()
        if (ranked.isEmpty()) radios.prefetch(discoverySeedsLocked().map(TrackStats::meta))
        weeklyDiscoveryLocked(ranked, radios)
    }

    /** On repeat: their most-finished tracks of the last [TasteConfig.onRepeatDays] days. */
    suspend fun onRepeat(): Shelf = mutex.withLock {
        ensureLoaded()
        onRepeatLocked()
    }

    /**
     * The title of a track the listener played (or the catalog knows), for shelf titles such as
     * "Because you replayed X". Names are data, not UI text; null when unknown.
     */
    suspend fun displayName(track: TrackKey): String? = mutex.withLock {
        ensureLoaded()
        profile.track(track)?.meta?.title ?: catalog.meta(track)?.title
    }

    /**
     * The artist's name as written in their most-played track (channel suffixes such as " - Topic"
     * removed), for titles such as "<artist> Mix". Null when the artist is unknown.
     */
    suspend fun displayName(artist: ArtistKey): String? = mutex.withLock {
        ensureLoaded()
        val meta = profile.tracks.values.filter { it.meta.artistKey == artist }
            .maxWithOrNull(compareBy<TrackStats> { it.plays }.thenByDescending { it.key.value })?.meta
            ?: catalog.tracksByArtist(artist, 1).firstOrNull()
        meta?.let { cleanArtistName(it.primaryArtist) }
    }

    suspend fun freshForYou(): Shelf? = mutex.withLock {
        ensureLoaded()
        freshForYouLocked()
    }

    suspend fun rediscover(): Shelf = mutex.withLock {
        ensureLoaded()
        rediscoverLocked()
    }

    /** Radio shelf from any seed without touching the live queue. */
    suspend fun radio(seed: RadioSeed, size: Int = config.upNextSize): Shelf = mutex.withLock {
        ensureLoaded()
        val v = seedVector(seed)
        val request = GenerationRequest(
            profile, controls, clock.nowMs(), sessionVector = v ?: profile.longTermVector,
            nowPlaying = (seed as? RadioSeed.Track)?.track, nowPlayingVector = (seed as? RadioSeed.Track)?.let { v },
            seed = seed, seedVector = v, exclude = setOfNotNull((seed as? RadioSeed.Track)?.track?.key),
        )
        val ranked = rankLocked(radioCollector, request)
        val share = policy.sampleShare(explorationState, controls.discoveryDial)
        val items = assembler.assemble(
            emptyList(), ranked, policy.planSlots(size, share), profile.recentPlays, request.nowPlaying, RecentlyRecommended(1), request.nowMs,
        )
        Shelf(shelfId(ShelfKind.RADIO, Reason.RadioFrom(seed).key), ShelfKind.RADIO, Reason.RadioFrom(seed), items)
    }

    /**
     * Ranked recommendations from long-term taste, without queue rules. Used by the offline
     * replay evaluator and "for you" grids.
     */
    suspend fun recommendations(limit: Int, includeHeard: Boolean = true): List<ScoredCandidate> = mutex.withLock {
        ensureLoaded()
        val request = GenerationRequest(profile, controls, clock.nowMs(), sessionVector = profile.longTermVector)
        rankLocked(autoplayCollector, request).filter { includeHeard || !profile.hasHeard(it.candidate.track) }.take(limit)
    }

    // ---- Home rows -----------------------------------------------------------------------------

    /** How well a Home row's [items] match the listener's taste. */
    suspend fun scoreRow(items: List<RowItem>): RowScore = mutex.withLock {
        ensureLoaded()
        homeRowScorerLocked().score(items)
    }

    /**
     * Which of upstream's Home rows to show, in order: at most [HomeRowConfig.maxRows] that match the
     * listener's taste, the [sticky] rows already on screen keeping their slots while they still pass.
     * Below [TasteConfig.coldStartPlays] finished plays, the first rows in input order, unscored.
     */
    suspend fun selectHomeRows(
        candidates: List<RowCandidate>,
        sticky: List<String> = emptyList(),
    ): RowSelection = mutex.withLock {
        ensureLoaded()
        homeRowScorerLocked().select(candidates, sticky, coldStart = profile.finishedPlays < config.coldStartPlays)
    }

    /** For each item, whether it matches the listener's taste (known, liked, not disliked); all true at cold start. */
    suspend fun matchesTaste(items: List<RowItem>): List<Boolean> = mutex.withLock {
        ensureLoaded()
        if (profile.finishedPlays < config.coldStartPlays) return@withLock items.map { true }
        val scorer = homeRowScorerLocked()
        items.map(scorer::matches)
    }

    /**
     * How well the profile supports each of a row's [tracks], for [orderRowSongs]; null below
     * [TasteConfig.coldStartPlays] finished plays, where rows keep their provider order.
     */
    suspend fun rowSongSupport(tracks: List<TrackMeta>): List<SongSupport>? = mutex.withLock {
        ensureLoaded()
        if (profile.finishedPlays < config.coldStartPlays) return@withLock null
        val scorer = RowScorer(profile, controls, config.homeRows, songs)
        val now = clock.nowMs()
        tracks.map { scorer.support(it, now) }
    }

    /**
     * Whether the Matched row [rowKey] shows its songs reordered today ([HomeRowConfig.songOrder]).
     * With [SongOrder.ALTERNATE] each row flips between ordered and provider order every local day,
     * about half the rows in each arm on any day, so plays from both orders get collected.
     */
    fun ordersRow(rowKey: String): Boolean =
        when (config.homeRows.songOrder) {
            SongOrder.OFF -> false
            SongOrder.ON -> true
            SongOrder.ALTERNATE -> (rowKey.hashCode().toLong() + localDay(clock.nowMs())).mod(2L) == 0L
        }

    private fun homeRowScorerLocked() = RowScorer(profile, controls, config.homeRows)

    /**
     * The listener's most recent distinct finished songs, newest first; reads the event log directly,
     * without the engine lock. Covers the last [days] days of plays recorded here or imported from
     * Metrolist (both on-device), minus ruled-out songs and muted artists.
     */
    suspend fun jumpBackIn(
        limit: Int,
        days: Int = 30,
    ): List<TrackMeta> {
        // Deliberately outside the mutex: a profile refresh holds it for seconds. [controls] is safe to
        // read from any thread, and [songs] is not, so a local identity cache is used.
        val recent = events.since(clock.nowMs() - days * DAY_MS)
        return jumpBackInOf(recent.sortedBy { it.startedAtMs }, controls, limit, SongKeys())
    }

    // ---- Persistence ---------------------------------------------------------------------------

    /** The listener's controls, exploration state, recent recommendations and kept shelves, for the app to save. */
    suspend fun exportState(): String = mutex.withLock {
        val kept = stable.entries.sortedBy { it.key.ordinal }.map { (cadence, p) -> KeptShelves(cadence, p.first, p.second) }
        TasteState(own, explorationState, recent.toList(), kept).encode()
    }

    /**
     * Restores what [exportState] saved; keeps the current discovery dial. Returns false for
     * unreadable text. Kept shelves come back as built, so controls still apply when they are read;
     * a cadence already built in this run keeps its build, and a restored period with nothing in it
     * is built again rather than kept.
     */
    suspend fun importState(encoded: String): Boolean = mutex.withLock {
        val state = TasteState.decode(encoded) ?: return@withLock false
        own = state.controls.copy(discoveryDial = own.discoveryDial)
        explorationState = state.exploration
        recent.restore(state.recent)
        for (group in state.kept) {
            if (group.shelves.none { it.items.isNotEmpty() } || group.cadence in stable) continue
            stable[group.cadence] = group.period to group.shelves
        }
        if (loaded) updateProfileLocked()
        true
    }

    // ---- Internals -----------------------------------------------------------------------------

    /** Context at [nowMs]: the hour and weekday now, keeping the device and network of the last play. */
    private fun contextLocked(nowMs: Long): PlayContext? {
        val fresh = contextAt?.invoke(nowMs) ?: return context
        val last = context ?: return fresh
        return fresh.copy(device = last.device, network = last.network)
    }

    /** Artists quick-skipped at least twice in the last [SESSION_WINDOW_MS] of this session: not wanted right now. */
    private fun sessionSkippedArtistsLocked(now: Long): Set<ArtistKey> =
        profile.recentPlays.asSequence()
            .filter { it.quickSkip && it.startedAtMs >= now - SESSION_WINDOW_MS && it.startedAtMs >= sessionStartMs }
            .groupingBy { it.artist }.eachCount()
            .filterValues { it >= 2 }.keys

    private suspend fun ensureLoaded() {
        if (!loaded) refreshLocked()
    }

    private suspend fun refreshLocked() {
        val now = clock.nowMs()
        val history = events.since(now - config.historyDays * DAY_MS)
        builder = TasteProfileBuilder(config.affinity, songs).also { b ->
            b.addAll(history)
            saved.forEach(b::addSaved)
            events.feedbackSince(now - config.historyDays * DAY_MS).forEach(b::addFeedback)
        }
        val full = builder.build(now, representation, config.clustering)
        clusters = full.clusters
        profile = withColdStart(full)
        session.longTerm = profile.longTermVector
        loaded = true
    }

    private fun updateProfileLocked() {
        profile = withColdStart(builder.snapshot(clock.nowMs(), representation, clusters))
        session.longTerm = profile.longTermVector
    }

    /**
     * Cold start: blend the onboarding artists' co-listening vectors into long-term taste (fading
     * out as finished plays accumulate) and, with no clusters yet, use one pseudo-cluster per artist.
     */
    private fun withColdStart(p: TasteProfile): TasteProfile {
        val onboard = controls.onboardingArtists.mapNotNull { a -> store.artistVector(a)?.let { a to it } }
        if (onboard.isEmpty()) return p
        val coldness = (1.0 - p.finishedPlays.toDouble() / config.coldStartPlays).coerceIn(0.0, 1.0)
        val pseudoClusters = onboard.mapIndexed { i, (a, v) -> TasteCluster(i, v.normalized(), 1.0 / onboard.size, emptyList(), listOf(a), SkipStats()) }
        val clusters = p.clusters.ifEmpty { pseudoClusters }
        if (coldness <= 0.0) return p.copy(clusters = clusters)
        val onboardVec = weightedMeanDirection(onboard.map { it.second }, dim = representation.dim)
        val own = p.longTermVector
        val longTerm = if (own == null || onboardVec == null) {
            own ?: onboardVec
        } else {
            weightedMeanDirection(listOf(own, onboardVec), listOf(1.0 - coldness, coldness), representation.dim)
        }
        return p.copy(clusters = clusters, longTermVector = longTerm)
    }

    private fun resetSessionLocked() {
        resetShortTermLocked(clock.nowMs())
        queue = emptyList()
    }

    /**
     * Forgets the short-term session: the session model, and skips older than [since] in the
     * skipped-artist window. Never touches controls, exploration, the recently-recommended memory
     * or the profile.
     */
    private fun resetShortTermLocked(now: Long, since: Long = now) {
        session.reset()
        session.longTerm = profile.longTermVector
        sessionStartMs = since
        lastActivityMs = now
    }

    private fun seedVector(seed: RadioSeed): FloatArray? = when (seed) {
        is RadioSeed.Track -> representation.vectorFor(seed.track)
        is RadioSeed.Artist -> store.artistVector(seed.artist)
    }

    /** Drops queue items up to and including [key] (the listener reached or skipped past them). */
    private fun consumeThrough(key: TrackKey) {
        val idx = queue.indexOfFirst { it.key == key }
        if (idx >= 0) queue = queue.drop(idx + 1)
    }

    private suspend fun rerankLocked() {
        val rules = config.queueRules
        val frozen = queue.take(rules.frozenCount)
        val tail = queue.drop(rules.frozenCount).map { it.key }.toSet()
        val np = nowPlayingTrack
        val seed = radioSeed
        val now = clock.nowMs()
        val request = GenerationRequest(
            profile = profile,
            controls = controls,
            nowMs = now,
            sessionVector = session.vector(),
            nowPlaying = np,
            nowPlayingVector = np?.let { representation.vectorFor(it) },
            anchorTrack = np?.key ?: session.finished.lastOrNull() ?: (seed as? RadioSeed.Track)?.track?.key,
            seed = seed,
            seedVector = seed?.let(::seedVector),
            exclude = session.quickSkipped + frozen.map { it.key } + listOfNotNull(np?.key),
        )
        val ranked = rankLocked(if (seed != null) radioCollector else autoplayCollector, request)
        val share = policy.sampleShare(explorationState, controls.discoveryDial)
        val slots = policy.planSlots((config.upNextSize - frozen.size).coerceAtLeast(0), share)
        queue = assembler.assemble(frozen, ranked, slots, profile.recentPlays, np, recent, now, allowAgain = tail)
        for (item in queue) recent.add(item.key)
    }

    private suspend fun rankLocked(collector: CandidateCollector, request: GenerationRequest): List<ScoredCandidate> {
        val candidates = collector.collect(request)
        val extractor = FeatureExtractor(request.profile, controls, request.sessionVector, request.nowMs, contextLocked(request.nowMs), request.seedVector)
        return candidates.mapNotNull { c ->
            val v = representation.vectorFor(c.track)
            if (controls.isInMutedCluster(v)) return@mapNotNull null
            val f = extractor.extract(c, v)
            ScoredCandidate(c, v, f, ranker.score(f))
        }.sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.candidate.key.value })
    }

    /**
     * Lays out a shelf with the queue rules (spacing, album cap) and a familiar share.
     * [artistSpacing] 3 for mixes that play as a queue; 1 (no back-to-back) for browsable shelves.
     * [head] items are kept as they are and the shelf is filled up to [size] after them.
     */
    private fun compose(
        ranked: List<ScoredCandidate>,
        familiarShare: Double,
        maxPerArtist: Int,
        nowMs: Long,
        artistSpacing: Int = 1,
        size: Int = config.shelfSize,
        head: List<QueueItem> = emptyList(),
    ): List<QueueItem> {
        val rules = config.queueRules.copy(maxPerArtist = maxPerArtist, minRepeatGapMs = 0, artistSpacing = artistSpacing)
        return QueueAssembler(rules, songs).assemble(
            head, ranked, policy.planSlots((size - head.size).coerceAtLeast(0), 1.0 - familiarShare), emptyList(), null, RecentlyRecommended(1), nowMs,
        )
    }

    private fun shelfId(kind: ShelfKind, suffix: String) = "${kind.name.lowercase()}:$suffix"

    /** A fresh per-refresh upstream budget. */
    private fun radioCacheLocked() =
        RadioCache(upstream.takeIf { profile.tracks.isNotEmpty() }, config.shelfUpstreamSeeds, config.shelfUpstreamPerSeed, config.generatorTimeoutMs)

    /** A track they still like: positive affinity, not marked "less like this", artist not muted. */
    private fun usable(s: TrackStats) =
        s.affinity > 0 && !s.lessLikeThis && s.key !in controls.lessLikeThisTracks && s.meta.artistKey !in controls.mutedArtists

    /**
     * Heard and net disliked (mostly skipped). Without vectors the ranker cannot tell such tracks
     * from favourites in familiar slots, so history-based shelves drop them.
     */
    private fun disliked(c: ScoredCandidate) = (profile.track(c.candidate.key)?.affinity ?: 0.0) < 0

    /** Value of [TrackStats.recentFinishes] for one finished play at the edge of the On repeat window. */
    private fun recentFinishFloor() = config.affinity.recentDecay(config.onRepeatDays * DAY_MS) * 0.999

    /**
     * The most-played track of each top-affinity artist (distinct, not muted, no "less like this"
     * marks), best artist first: seeds for artist mixes and fallback discovery.
     */
    private fun topArtistSeedsLocked(limit: Int): List<TrackStats> {
        if (limit <= 0) return emptyList()
        val byArtist = profile.tracks.values.filter(::usable).groupBy { it.meta.artistKey }
        return profile.topArtists(Int.MAX_VALUE).asSequence()
            .map { it.artist }
            .filter { it !in controls.mutedArtists && (controls.lessLikeThisArtists[it] ?: 0) == 0 }
            .mapNotNull { a ->
                byArtist[a]?.maxWithOrNull(
                    compareBy<TrackStats> { it.finishes }.thenBy { it.plays }.thenBy { it.affinity }.thenByDescending { it.key.value },
                )
            }
            .take(limit)
            .toList()
    }

    /** Artist mix seeds, only while there are no taste clusters (no vectors). */
    private fun artistMixPlanLocked(): List<TrackStats> =
        if (profile.clusters.isNotEmpty()) emptyList() else topArtistSeedsLocked(config.artistMixes)

    private fun discoverySeedsLocked(): List<TrackStats> = topArtistSeedsLocked(config.shelfUpstreamSeeds)

    private suspend fun dailyMixesLocked(plan: List<TrackStats>, radios: RadioCache): List<Shelf> =
        if (profile.clusters.isEmpty()) artistMixesLocked(plan, radios) else clusterMixesLocked()

    /**
     * Without clusters: one mix per top artist, seeded from their most-played track. Candidates are
     * the seed's upstream radio, their tracks by that artist and related favourites. The artist's
     * own tracks lead; tracks already placed in an earlier mix, and tracks by the other mixes'
     * artists, go last, so the mixes stay mostly distinct and each stays centred on its artist.
     */
    private suspend fun artistMixesLocked(plan: List<TrackStats>, radios: RadioCache): List<Shelf> {
        val now = clock.nowMs()
        val used = HashSet<TrackKey>()
        val mixArtists = plan.mapTo(HashSet()) { it.meta.artistKey }
        return plan.mapIndexedNotNull { i, seed ->
            val artist = seed.meta.artistKey
            val reason = Reason.DailyMix(i, listOf(artist), seedArtist = artist)
            val request = GenerationRequest(profile, controls, now, sessionVector = null, nowPlaying = seed.meta, anchorTrack = seed.key)
            val generators = listOf(
                LibraryGenerator(catalog, radios, Reason.FromYourTaste(i, listOf(artist))),
                UpstreamRadioGenerator(radios),
            )
            val ranked = rankLocked(collector(generators), request).filterNot(::disliked)
            // The artist's own tracks lead (spacing and the cap of 6 interleave them), then the rest.
            val (own, others) = ranked.partition { it.track.artistKey == artist }
            val (fresh, reused) = others.partition { it.candidate.key !in used }
            val (preferred, otherMixArtists) = fresh.partition { it.track.artistKey !in mixArtists }
            val rest = otherMixArtists + reused
            val spacing = config.queueRules.artistSpacing
            val distinct = compose(own + preferred, config.artistMixFamiliarShare, 6, now, artistSpacing = spacing)
            // Distinct mixes beat long ones: other mixes' tracks only top a short mix up to two thirds of a shelf.
            val minSize = config.shelfSize * 2 / 3
            val items = if (distinct.size >= minSize) {
                distinct
            } else {
                compose(rest, config.artistMixFamiliarShare, 6, now, artistSpacing = spacing, size = minSize, head = distinct)
            }
            if (items.size < config.minFallbackShelfSize) return@mapIndexedNotNull null
            items.forEach { used += it.key }
            Shelf(shelfId(ShelfKind.DAILY_MIX, "artist:${artist.value}"), ShelfKind.DAILY_MIX, reason, items)
        }
    }

    private suspend fun clusterMixesLocked(): List<Shelf> {
        val now = clock.nowMs()
        val visible = profile.clusters.filterNot { controls.isInMutedCluster(it.centroid) }
            .sortedByDescending { if (controls.isInBoostedCluster(it.centroid)) 1 else 0 }
        return visible.map { c ->
            val mixProfile = profile.copy(clusters = listOf(c))
            val request = GenerationRequest(mixProfile, controls, now, sessionVector = c.centroid)
            val artistSeeds = c.topArtists.take(3).map { it to 1f }
            val cfForMix = object : CandidateGenerator {
                override val source = CandidateSource.COLLABORATIVE
                override suspend fun generate(request: GenerationRequest, limit: Int) = cfGen.fromArtists(artistSeeds, request, limit)
            }
            val ranked = rankLocked(collector(listOf(favoritesGen, clusterGen, cfForMix)), request)
            val reason = Reason.DailyMix(c.id, c.topArtists)
            Shelf(shelfId(ShelfKind.DAILY_MIX, c.id.toString()), ShelfKind.DAILY_MIX, reason, compose(ranked, config.dailyMixFamiliarShare, 4, now, artistSpacing = config.queueRules.artistSpacing))
        }
    }

    private class MoreLikePick(val stats: TrackStats, val reason: Reason, val vector: FloatArray?)

    /**
     * The track they most recently kept coming back to (replayed, else liked, in the last 30 days);
     * failing that, their most-finished recent track ("Because you listened to X").
     */
    private fun moreLikePickLocked(): MoreLikePick? {
        val recentCutoff = clock.nowMs() - 30 * DAY_MS
        val eligible = profile.tracks.values.filter { it.lastPlayedMs >= recentCutoff && usable(it) }
        // Order: replayed, then a song found this week (its first play held a minute), then liked.
        val foundCutoff = clock.nowMs() - 7 * DAY_MS
        val found = eligible.filter { it.replays == 0 && it.plays <= 3 && (it.foundMs ?: Long.MIN_VALUE) >= foundCutoff && it.affinity > 0 }
            .maxWithOrNull(compareBy<TrackStats> { it.foundMs ?: 0L }.thenByDescending { it.key.value })
        if (eligible.none { it.replays > 0 } && found != null) return MoreLikePick(found, Reason.BecauseYouListened(found.key), representation.vectorFor(found.meta))
        val loved = eligible.filter { it.replays > 0 || it.likes > 0 }
            .maxWithOrNull(compareBy<TrackStats> { if (it.replays > 0) 1 else 0 }.thenBy { it.affinity }.thenByDescending { it.key.value })
        val pick = loved ?: eligible.filter { it.recentFinishes >= recentFinishFloor() }
            .maxWithOrNull(compareBy<TrackStats> { it.recentFinishes }.thenBy { it.affinity }.thenByDescending { it.key.value })
            ?: return null
        val reason = when {
            loved == null -> Reason.BecauseYouListened(pick.key)
            pick.replays > 0 -> Reason.BecauseYouReplayed(pick.key)
            else -> Reason.BecauseYouLiked(pick.key)
        }
        return MoreLikePick(pick, reason, representation.vectorFor(pick.meta))
    }

    /** "Because you replayed X": tracks like [p]. Without a vector: X's upstream radio plus their own tracks. */
    private suspend fun moreLikeLocked(p: MoreLikePick, radios: RadioCache): Shelf {
        val now = clock.nowMs()
        val pick = p.stats
        val reason = p.reason
        val v = p.vector
        if (v == null) {
            val request = GenerationRequest(
                profile, controls, now, sessionVector = null, nowPlaying = pick.meta, anchorTrack = pick.key, exclude = setOf(pick.key),
            )
            val ranked = rankLocked(collector(listOf(LibraryGenerator(catalog, radios, reason), UpstreamRadioGenerator(radios))), request).filterNot(::disliked)
            return Shelf(shelfId(ShelfKind.MORE_LIKE, pick.key.value), ShelfKind.MORE_LIKE, reason, compose(ranked, 0.3, 2, now))
        }
        val request = GenerationRequest(
            profile, controls, now, sessionVector = v, nowPlaying = pick.meta, nowPlayingVector = v,
            anchorTrack = pick.key, exclude = setOf(pick.key),
        )
        val cfForSeed = object : CandidateGenerator {
            override val source = CandidateSource.COLLABORATIVE
            override suspend fun generate(request: GenerationRequest, limit: Int) = cfGen.fromArtists(listOf(pick.meta.artistKey to 1f), request, limit)
        }
        val ranked = rankLocked(collector(listOf(sessionGen, cfForSeed)), request)
        return Shelf(shelfId(ShelfKind.MORE_LIKE, pick.key.value), ShelfKind.MORE_LIKE, reason, compose(ranked, 0.3, 2, now))
    }

    /** Never-heard candidates from the vector generators (empty without vectors or onboarding). */
    private suspend fun vectorWeeklyRankedLocked(): List<ScoredCandidate> {
        val request = GenerationRequest(profile, controls, clock.nowMs(), sessionVector = profile.longTermVector)
        return rankLocked(discoveryCollector, request).filter { !profile.hasHeard(it.candidate.track) }
    }

    /**
     * [vectorRanked] when the vector generators found anything; otherwise never-heard tracks from
     * the prefetched upstream radios of their top tracks, never-heard artists first.
     */
    private suspend fun weeklyDiscoveryLocked(vectorRanked: List<ScoredCandidate>, radios: RadioCache): Shelf {
        val now = clock.nowMs()
        val id = shelfId(ShelfKind.WEEKLY_DISCOVERY, "weekly")
        if (vectorRanked.isNotEmpty()) {
            return Shelf(id, ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, compose(vectorRanked, 0.0, 2, now))
        }
        val request = GenerationRequest(profile, controls, now, sessionVector = profile.longTermVector)
        val ranked = rankLocked(collector(listOf(CachedRadiosGenerator(radios))), request).filter { !profile.hasHeard(it.candidate.track) }
        val (newArtists, knownArtists) = ranked.partition { !profile.hasHeardArtist(it.track.artistKey) }
        val items = compose(newArtists + knownArtists, 0.0, 2, now, size = config.weeklyDiscoveryFallbackSize)
            .takeIf { it.size >= config.minFallbackShelfSize }.orEmpty()
        return Shelf(id, ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, items)
    }

    /**
     * Their most-finished tracks lately: finished within [TasteConfig.onRepeatDays], ranked by
     * recent finishes plus affinity. Browsable, so spacing is relaxed to "no artist back to back
     * when another artist is left".
     */
    private fun onRepeatLocked(): Shelf {
        val now = clock.nowMs()
        val cutoff = now - config.onRepeatDays * DAY_MS
        val floor = recentFinishFloor()
        val picks = profile.tracks.values
            // Songs they keep coming back to: at least two recent finishes (one is just "recently played").
            .filter { it.lastPlayedMs >= cutoff && it.finishes >= 2 && it.recentFinishes >= 2 * floor && usable(it) }
            .sortedWith(compareByDescending<TrackStats> { it.recentFinishes + 0.3 * it.affinity }.thenBy { it.key.value })
            .toMutableList()
        val ordered = ArrayList<TrackStats>()
        while (ordered.size < config.onRepeatSize && picks.isNotEmpty()) {
            val last = ordered.lastOrNull()?.meta?.artistKey
            val idx = picks.indexOfFirst { it.meta.artistKey != last }.coerceAtLeast(0)
            ordered += picks.removeAt(idx)
        }
        val extractor = FeatureExtractor(profile, controls, profile.longTermVector, now, contextLocked(now))
        val items = ordered.map { s ->
            val c = Candidate(s.meta, CandidateSource.LIBRARY, Reason.OnRepeat, tanh(s.affinity / 3).toFloat())
            QueueItem(s.meta, c.source, c.reason, SlotKind.FAMILIAR, ranker.score(extractor.extract(c, representation.vectorFor(s.meta))))
        }
        return Shelf(shelfId(ShelfKind.ON_REPEAT, "on_repeat"), ShelfKind.ON_REPEAT, Reason.OnRepeat, items)
    }

    private suspend fun freshForYouLocked(): Shelf? {
        val gen = freshGen ?: return null
        val now = clock.nowMs()
        val request = GenerationRequest(profile, controls, now, sessionVector = profile.longTermVector)
        val ranked = rankLocked(collector(listOf(gen)), request)
        return Shelf(shelfId(ShelfKind.FRESH_FOR_YOU, "fresh"), ShelfKind.FRESH_FOR_YOU, Reason.FreshForYou, compose(ranked, 0.0, 3, now))
    }

    private suspend fun rediscoverLocked(): Shelf {
        val now = clock.nowMs()
        val request = GenerationRequest(profile, controls, now, sessionVector = profile.longTermVector)
        val ranked = rankLocked(collector(listOf(rediscoveryGen)), request)
        return Shelf(shelfId(ShelfKind.REDISCOVER, "rediscover"), ShelfKind.REDISCOVER, Reason.RediscoverShelf, compose(ranked, 1.0, 2, now))
    }

    private companion object {
        /** How far back "this session" reaches for repeated skips of one artist. */
        const val SESSION_WINDOW_MS = 2 * HOUR_MS

        /** Left occurrences remembered, so a late page for one of them cannot reopen it. */
        const val MAX_LEFT_OCCURRENCES = 64
    }
}
