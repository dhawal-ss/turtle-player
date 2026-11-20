package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
)

/**
 * The on-device recommendation engine: the one entry point the app talks to.
 *
 * Typical use from the bridge:
 * - construct once with the app's [PlayEventStore], an [EmbeddingStore] loaded from the downloaded
 *   packs, the [TrackCatalog] and the optional seams;
 * - call [observe] (or [onPlayEvent] for each recorded event) and [nowPlaying] on track changes;
 * - [startAutoplay] / [startRadio] when playback starts, [upNext] for the queue, [homeShelves] for home;
 * - persist [controls], [explorationState] and [recentlyRecommended] between runs.
 *
 * All public functions are serialized by one mutex; generators run concurrently inside a refresh.
 */
class TasteEngine(
    private val events: PlayEventStore,
    private val store: EmbeddingStore,
    private val catalog: TrackCatalog,
    freshReleases: FreshReleaseSource? = null,
    upstream: RecommendationSource? = null,
    private val representation: TrackRepresentation = TrackRepresentation(store),
    private val ranker: Ranker = HandTunedRanker(),
    private val config: TasteConfig = TasteConfig(),
    private val clock: Clock = Clock.System,
    random: Random = Random.Default,
    controls: UserControls = UserControls(),
    exploration: ExplorationState = ExplorationState(),
    recentlyRecommended: List<TrackKey> = emptyList(),
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

    private var builder = TasteProfileBuilder(config.affinity)
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

    /** Starts autoplay after [nowPlaying] (or from long-term taste when null). Returns Up Next. */
    suspend fun startAutoplay(nowPlaying: TrackMeta? = null, context: PlayContext? = null): List<QueueItem> = mutex.withLock {
        ensureLoaded()
        context?.let { this.context = it }
        resetSessionLocked()
        nowPlayingTrack = nowPlaying
        nowPlaying?.let { representation.vectorFor(it) }?.let { session.seed(it, mass = 1.0) }
        radioSeed = null
        autoplayActive = true
        rerankLocked()
        queue
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

    /** Home: Daily Mixes, Because you replayed, Weekly Discovery, Fresh for You, Rediscover. Empty shelves are omitted. */
    suspend fun homeShelves(): List<Shelf> = mutex.withLock {
        ensureLoaded()
        buildList {
            addAll(dailyMixesLocked())
            moreLikeLocked()?.let(::add)
            add(weeklyDiscoveryLocked())
            freshForYouLocked()?.let(::add)
            add(rediscoverLocked())
        }.filter { it.items.isNotEmpty() && it.reason.key !in controls.suppressedReasons }
    }

    suspend fun dailyMixes(): List<Shelf> = mutex.withLock {
        ensureLoaded()
        dailyMixesLocked()
    }

    suspend fun weeklyDiscovery(): Shelf = mutex.withLock {
        ensureLoaded()
        weeklyDiscoveryLocked()
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

    // ---- Internals -----------------------------------------------------------------------------

    private suspend fun ensureLoaded() {
        if (!loaded) refreshLocked()
    }

    private suspend fun refreshLocked() {
        val now = clock.nowMs()
        val history = events.since(now - config.historyDays * DAY_MS)
        builder = TasteProfileBuilder(config.affinity).also { it.addAll(history) }
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

    /** Lays out a shelf with the queue rules (spacing, album cap) and a familiar share. */
    /** [artistSpacing] 3 for mixes that play as a queue; 1 (no back-to-back) for browsable shelves. */
    private fun compose(ranked: List<ScoredCandidate>, familiarShare: Double, maxPerArtist: Int, nowMs: Long, artistSpacing: Int = 1): List<QueueItem> {
        val rules = config.queueRules.copy(maxPerArtist = maxPerArtist, minRepeatGapMs = 0, artistSpacing = artistSpacing)
        return QueueAssembler(rules).assemble(
            emptyList(), ranked, policy.planSlots(config.shelfSize, 1.0 - familiarShare), emptyList(), null, RecentlyRecommended(1), nowMs,
        )
    }

    private fun shelfId(kind: ShelfKind, suffix: String) = "${kind.name.lowercase()}:$suffix"

    private suspend fun dailyMixesLocked(): List<Shelf> {
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

    /** "Because you replayed X": tracks like the one they most recently kept coming back to. */
    private suspend fun moreLikeLocked(): Shelf? {
        val now = clock.nowMs()
        val recentCutoff = now - 30 * DAY_MS
        val pick = profile.tracks.values.filter { it.lastPlayedMs >= recentCutoff && it.affinity > 0 && !it.lessLikeThis }
            .filter { it.replays > 0 || it.likes > 0 }
            .maxWithOrNull(compareBy<TrackStats> { if (it.replays > 0) 1 else 0 }.thenBy { it.affinity }.thenByDescending { it.key.value })
            ?: return null
        val v = representation.vectorFor(pick.meta) ?: return null
        val reason = if (pick.replays > 0) Reason.BecauseYouReplayed(pick.key) else Reason.BecauseYouLiked(pick.key)
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

    private suspend fun weeklyDiscoveryLocked(): Shelf {
        val now = clock.nowMs()
        val request = GenerationRequest(profile, controls, now, sessionVector = profile.longTermVector)
        val ranked = rankLocked(discoveryCollector, request).filter { !profile.hasHeard(it.candidate.key) }
        return Shelf(shelfId(ShelfKind.WEEKLY_DISCOVERY, "weekly"), ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, compose(ranked, 0.0, 2, now))
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
}
