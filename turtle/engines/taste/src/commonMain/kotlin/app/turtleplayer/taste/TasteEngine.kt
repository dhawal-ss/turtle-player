package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.cleanArtistName
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - persist [controls], [explorationState] and [recentlyRecommended] between runs.
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
) {
    private val mutex = Mutex()
    private val policy = ExplorationPolicy(random)
    private val assembler = QueueAssembler(config.queueRules)

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

    private var builder = TasteProfileBuilder(config.affinity)
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
    /** Slot of tracks we queued recently, to attribute exploration outcomes. */
    private val queuedSlots = LinkedHashMap<TrackKey, SlotKind>()
    private val recent = RecentlyRecommended(config.queueRules.recentMemory).also { it.restore(recentlyRecommended) }

    var controls: UserControls = controls
        private set
    var explorationState: ExplorationState = exploration
        private set

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
        if (event.lessLikeThis) controls = controls.apply(LessLikeThis.Track(event.trackKey, event.artistKey))
        val slot = SurfaceTag.parse(event.surfaceDetail)?.second ?: queuedSlots[event.trackKey]
        if (slot == SlotKind.DISCOVERY) explorationState = policy.update(explorationState, policy.outcomeOf(event))
        updateProfileLocked()
        session.onPlay(event, representation.vectorFor(event.track))
        if (nowPlayingTrack?.key == event.trackKey) nowPlayingTrack = null
        consumeThrough(event.trackKey)
        if (autoplayActive) rerankLocked()
        queue
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
     * tracks that break spacing rules go to the end rather than being lost.
     */
    suspend fun rankExternal(
        seed: TrackMeta,
        tracks: List<TrackMeta>,
        source: CandidateSource = CandidateSource.UPSTREAM_RADIO,
    ): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        val unique = tracks.distinctBy { it.key }
            .filter { it.artistKey !in controls.mutedArtists && it.key !in controls.lessLikeThisTracks }
        if (unique.isEmpty()) return@withLock emptyList()
        val now = clock.nowMs()
        val skippedNow = sessionSkippedArtistsLocked(now)
        val seedVector = representation.vectorFor(seed)
        val extractor = FeatureExtractor(profile, controls, session.vector(), now, context, seedVector)
        val reason = Reason.UpstreamRadio(seed.key)
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
        for (item in result.take(config.upNextSize)) {
            recent.add(item.key)
            queuedSlots.remove(item.key)
            queuedSlots[item.key] = item.slot
        }
        while (queuedSlots.size > 4 * config.upNextSize) queuedSlots.remove(queuedSlots.keys.first())
        result
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
        controls = controls.apply(target)
        if (target is LessLikeThis.Track) queue = queue.filterNot { it.key == target.track }
        if (target is LessLikeThis.ForReason) queue = queue.filterNot { it.reason.key == target.reason.key }
        if (autoplayActive) rerankLocked()
        queue
    }

    /** Applies a change to the listener's controls; an active Up Next is re-ranked beyond the next two. */
    suspend fun updateControls(transform: (UserControls) -> UserControls) {
        mutex.withLock {
            controls = transform(controls)
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
            }.filter { it.items.isNotEmpty() && it.reason.key !in controls.suppressedReasons }
        }
    }

    /** Forgets kept shelves, so the next [homeShelves] builds every shelf afresh (pull to refresh). */
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
        rankLocked(autoplayCollector, request).filter { includeHeard || !profile.hasHeard(it.candidate.key) }.take(limit)
    }

    // ---- Persistence ---------------------------------------------------------------------------

    /** The listener's controls, exploration state and recent recommendations, for the app to save. */
    suspend fun exportState(): String = mutex.withLock { TasteState(controls, explorationState, recent.toList()).encode() }

    /** Restores what [exportState] saved; keeps the current discovery dial. Returns false for unreadable text. */
    suspend fun importState(encoded: String): Boolean = mutex.withLock {
        val state = TasteState.decode(encoded) ?: return@withLock false
        controls = state.controls.copy(discoveryDial = controls.discoveryDial)
        explorationState = state.exploration
        recent.restore(state.recent)
        if (loaded) updateProfileLocked()
        true
    }

    // ---- Internals -----------------------------------------------------------------------------

    /** Artists quick-skipped at least twice in the last [SESSION_WINDOW_MS]: not wanted right now. */
    private fun sessionSkippedArtistsLocked(now: Long): Set<ArtistKey> =
        profile.recentPlays.asSequence()
            .filter { it.quickSkip && it.startedAtMs >= now - SESSION_WINDOW_MS }
            .groupingBy { it.artist }.eachCount()
            .filterValues { it >= 2 }.keys

    private suspend fun ensureLoaded() {
        if (!loaded) refreshLocked()
    }

    private suspend fun refreshLocked() {
        val now = clock.nowMs()
        val history = events.since(now - config.historyDays * DAY_MS)
        builder = TasteProfileBuilder(config.affinity).also { b ->
            b.addAll(history)
            saved.forEach(b::addSaved)
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
        session.reset()
        session.longTerm = profile.longTermVector
        queue = emptyList()
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
        for (item in queue) {
            recent.add(item.key)
            queuedSlots.remove(item.key)
            queuedSlots[item.key] = item.slot
        }
        while (queuedSlots.size > 4 * config.upNextSize) queuedSlots.remove(queuedSlots.keys.first())
    }

    private suspend fun rankLocked(collector: CandidateCollector, request: GenerationRequest): List<ScoredCandidate> {
        val candidates = collector.collect(request)
        val extractor = FeatureExtractor(request.profile, controls, request.sessionVector, request.nowMs, context, request.seedVector)
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
        return QueueAssembler(rules).assemble(
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
        return rankLocked(discoveryCollector, request).filter { !profile.hasHeard(it.candidate.key) }
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
        val ranked = rankLocked(collector(listOf(CachedRadiosGenerator(radios))), request).filter { !profile.hasHeard(it.candidate.key) }
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
            .filter { it.lastPlayedMs >= cutoff && it.finishes > 0 && it.recentFinishes >= floor && usable(it) }
            .sortedWith(compareByDescending<TrackStats> { it.recentFinishes + 0.3 * it.affinity }.thenBy { it.key.value })
            .toMutableList()
        val ordered = ArrayList<TrackStats>()
        while (ordered.size < config.onRepeatSize && picks.isNotEmpty()) {
            val last = ordered.lastOrNull()?.meta?.artistKey
            val idx = picks.indexOfFirst { it.meta.artistKey != last }.coerceAtLeast(0)
            ordered += picks.removeAt(idx)
        }
        val extractor = FeatureExtractor(profile, controls, profile.longTermVector, now, context)
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
    }
}
