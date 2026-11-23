package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasteEngineTest {
    private val world = SyntheticWorld()
    private val user = world.user(0)
    private val history = world.history(user)
    private val clock = MutableClock(history.last().startedAtMs + 30 * MINUTE_MS)
    private val representation = TrackRepresentation(world.store)

    private suspend fun engineWithHistory(
        controls: UserControls = UserControls(),
        fresh: FreshReleaseSource? = null,
        upstream: RecommendationSource? = null,
    ): TasteEngine {
        val store = InMemoryPlayEventStore()
        store.appendAll(history)
        return world.engine(store, clock, controls = controls, fresh = fresh, upstream = upstream)
    }

    private fun genreOf(t: TrackMeta) = world.trackGenre.getValue(t.key)

    private fun assertQueueRules(queue: List<QueueItem>, nowPlaying: TrackMeta? = null) {
        val artists = listOfNotNull(nowPlaying?.artistKey) + queue.map { it.track.artistKey }
        for (i in artists.indices) assertFalse(artists[i] in artists.subList(maxOf(0, i - 3), i), "spacing broken: $artists")
        assertEquals(queue.size, queue.map { it.key }.toSet().size, "duplicates in queue")
        // 18 tracks of 200 s span under an hour: no album more than twice in any such window.
        for (window in queue.windowed(18, partialWindows = true)) {
            val albums = window.mapNotNull { it.track.albumKey }.groupingBy { it }.eachCount()
            assertTrue(albums.values.all { it <= 2 }, "album cap broken: $albums")
        }
    }

    /** Plays [item] to its end (or skips it) at the current clock and advances the clock. */
    private suspend fun TasteEngine.playThrough(item: QueueItem, skip: Boolean): List<QueueItem> {
        nowPlaying(item.track)
        val ms = if (skip) 5_000L else 200_000L
        val event = play(item.track, clock.now, if (skip) EndReason.SKIPPED else EndReason.COMPLETED, msPlayed = ms, surfaceDetail = item.surfaceDetail)
        clock.now += ms
        return onPlayEvent(event)
    }

    @Test
    fun autoplayFollowsTasteAndObeysQueueRules() = runTest {
        val engine = engineWithHistory()
        val seed = user.heavyRotation[0]
        val queue = engine.startAutoplay(seed)
        assertEquals(20, queue.size)
        assertQueueRules(queue, seed)
        val inTaste = queue.count { genreOf(it.track) in user.likedGenres }
        assertTrue(inTaste >= 18, "in-taste $inTaste/20")
        assertTrue(queue.any { it.slot == SlotKind.DISCOVERY } && queue.any { it.slot == SlotKind.FAMILIAR })
        // Tracks finished in the last 3 hours are not queued again.
        val lastDay = history.filter { it.startedAtMs > clock.now - 3 * 60 * MINUTE_MS }.map { it.trackKey }.toSet()
        assertTrue(queue.none { it.key in lastDay })
    }

    @Test
    fun nextTwoItemsStayFrozenWhenReRankingAfterEachEvent() = runTest {
        val engine = engineWithHistory()
        var queue = engine.startAutoplay(user.heavyRotation[0])
        repeat(6) { step ->
            val head = queue.first()
            val expectedFrozen = queue.subList(1, 3)
            queue = engine.playThrough(head, skip = step % 2 == 0)
            assertEquals(expectedFrozen.map { it.key }, queue.take(2).map { it.key }, "step $step")
            assertTrue(queue.size >= 18)
            assertQueueRules(queue)
        }
    }

    @Test
    fun threeQuickSkipsChangeTheVibeAndReRankTheTail() = runTest {
        val engine = engineWithHistory()
        // The listener likes two genres; this session leans hard into one of them.
        val (focus, other) = user.likedGenres.toList()
        val focusTracks = world.tracksOfGenre(focus)
        val baseline = engine.startAutoplay().drop(2).count { genreOf(it.track) == focus }
        for (i in 0 until 4) {
            val t = focusTracks[i * 6] // different artists
            engine.nowPlaying(t)
            engine.onPlayEvent(play(t, clock.now))
            clock.now += 200_000
        }
        val drifted = engine.upNext().drop(2).count { genreOf(it.track) == focus }
        assertTrue(drifted > baseline, "session follows finished tracks: $baseline -> $drifted")

        var queue = engine.upNext()
        repeat(3) { i ->
            val t = focusTracks[(4 + i) * 6 + 1]
            engine.nowPlaying(t)
            queue = engine.onPlayEvent(play(t, clock.now, EndReason.SKIPPED, msPlayed = 4_000))
            clock.now += 4_000
        }
        val after = queue.drop(2).count { genreOf(it.track) == focus }
        assertTrue(after < drifted, "focus genre in tail: $drifted before vibe change, $after after")
        assertTrue(queue.drop(2).any { genreOf(it.track) == other }, "back to long-term taste: both liked genres")
    }

    @Test
    fun lessLikeThisRemovesTrackAndPenalisesArtist() = runTest {
        val engine = engineWithHistory()
        val queue = engine.startAutoplay(user.heavyRotation[0])
        val target = queue[5]
        val after = engine.lessLikeThis(target.lessLikeThis)
        assertTrue(after.none { it.key == target.key })
        assertTrue(target.key in engine.controls.lessLikeThisTracks)
        assertEquals(1, engine.controls.lessLikeThisArtists[target.track.artistKey])
        val recs = engine.recommendations(300)
        assertTrue(recs.none { it.candidate.key == target.key })
        assertEquals(1f, recs.first { it.track.artistKey == target.track.artistKey }.features[Feature.LESS_LIKE_THIS_MARKS])
    }

    @Test
    fun mutedArtistsAndClustersDisappear() = runTest {
        val engine = engineWithHistory()
        val profile = engine.profile()
        val muted = profile.topArtists(1).single().artist
        engine.updateControls { it.copy(mutedArtists = setOf(muted)) }
        assertTrue(engine.startAutoplay().none { it.track.artistKey == muted })
        val cluster = profile.clusters.first()
        engine.muteCluster(cluster)
        assertTrue(engine.dailyMixes().none { (it.reason as Reason.DailyMix).topArtists == cluster.topArtists })
        assertTrue(engine.upNext().drop(2).none { engine.controls.isInMutedCluster(representation.vectorFor(it.track)) })
    }

    @Test
    fun freshForYouDropsReleasesWithoutAKnownDate() = runTest {
        val artistTracks = world.tracksOf(user.favoriteArtists.first())
        fun release(id: String, releasedAtMs: Long?) = artistTracks.first().copy(
            key = TrackKey("mbid:$id"), title = "Release $id", album = "Release $id", releasedAtMs = releasedAtMs,
        )
        val undated = release("undated", null)
        val old = release("old", clock.now - 400 * DAY_MS)
        val fresh = release("fresh", clock.now - 2 * DAY_MS)
        listOf(undated, old, fresh).forEach(world.catalog::put)
        // A source that leaves the window to the engine, as a year-only catalogue would.
        val source = object : FreshReleaseSource {
            override suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int) = listOf(undated, old, fresh)
        }
        val shelf = engineWithHistory(fresh = source).freshForYou()
        assertEquals(listOf(fresh.key), shelf?.items?.map { it.key })

        val onlyUndated = object : FreshReleaseSource {
            override suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int) = listOf(undated)
        }
        val engine = engineWithHistory(fresh = onlyUndated)
        assertTrue(engine.freshForYou()?.items.isNullOrEmpty())
        assertTrue(engine.homeShelves().none { it.kind == ShelfKind.FRESH_FOR_YOU })
    }

    @Test
    fun homeShelvesCarryTypedReasons() = runTest {
        val fresh = world.tracksOf(user.favoriteArtists.first()).first().copy(
            key = TrackKey("mbid:fresh-single"), title = "New single", album = "New single", releasedAtMs = clock.now - 3 * DAY_MS,
        )
        world.catalog.put(fresh)
        val source = object : FreshReleaseSource {
            override suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int) =
                listOf(fresh).filter { it.artistKey in artists && it.releasedAtMs!! >= sinceMs }
        }
        val engine = engineWithHistory(fresh = source)
        val profile = engine.profile()
        val shelves = engine.homeShelves()
        val kinds = shelves.map { it.kind }

        val mixes = shelves.filter { it.kind == ShelfKind.DAILY_MIX }
        assertEquals(profile.clusters.size, mixes.size)
        for (mix in mixes) {
            assertTrue(mix.items.size >= 20, "mix size ${mix.items.size}")
            val familiar = mix.items.count { profile.hasHeard(it.key) }.toDouble() / mix.items.size
            assertTrue(familiar in 0.55..0.85, "daily mix familiar share $familiar")
            assertTrue(mix.reason is Reason.DailyMix)
        }

        val weekly = shelves.single { it.kind == ShelfKind.WEEKLY_DISCOVERY }
        assertEquals(Reason.NewForYou, weekly.reason)
        assertTrue(weekly.items.isNotEmpty() && weekly.items.none { profile.hasHeard(it.key) }, "weekly discovery must be never-heard only")
        assertTrue(weekly.items.count { genreOf(it.track) in user.likedGenres } >= weekly.items.size * 0.8)

        val freshShelf = shelves.single { it.kind == ShelfKind.FRESH_FOR_YOU }
        assertEquals(Reason.FreshFrom(fresh.artistKey), freshShelf.items.single().reason)

        assertTrue(ShelfKind.MORE_LIKE in kinds)
        val moreLike = shelves.single { it.kind == ShelfKind.MORE_LIKE }
        assertTrue(moreLike.reason is Reason.BecauseYouReplayed || moreLike.reason is Reason.BecauseYouLiked)
        // Ids are unique, every item carries a reason.
        assertEquals(shelves.size, shelves.map { it.id }.toSet().size)

        // "Less like this" on a shelf hides it.
        engine.lessLikeThis(weekly.lessLikeThis)
        assertTrue(engine.homeShelves().none { it.kind == ShelfKind.WEEKLY_DISCOVERY })
    }

    @Test
    fun rediscoverShelfHoldsLovedButLongUnheardTracks() = runTest {
        val store = InMemoryPlayEventStore()
        val old = world.artists.take(3).map { world.tracksOf(it)[0] }
        val recentT = world.tracksOf(world.artists[9]).take(3)
        for ((i, t) in old.withIndex()) repeat(4) { store.append(play(t, clock.now - (80 + i + it) * DAY_MS)) }
        for ((i, t) in recentT.withIndex()) repeat(4) { store.append(play(t, clock.now - (5 + i + it) * DAY_MS)) }
        val engine = world.engine(store, clock)
        val shelf = engine.rediscover()
        assertEquals(old.map { it.key }.toSet(), shelf.items.map { it.key }.toSet())
        assertTrue(shelf.items.all { it.reason is Reason.Rediscover })
    }

    @Test
    fun radioFromAnArtistStaysNearIt() = runTest {
        val engine = engineWithHistory()
        val outGenre = (0 until world.genres).first { it !in user.likedGenres }
        val seed = world.artists.first { world.artistGenre[it] == outGenre }
        val radio = engine.radio(RadioSeed.Artist(seed))
        assertEquals(ShelfKind.RADIO, radio.kind)
        assertEquals(Reason.RadioFrom(RadioSeed.Artist(seed)), radio.reason)
        assertTrue(radio.items.size >= 15)
        val near = radio.items.count { genreOf(it.track) == outGenre }
        assertTrue(near >= radio.items.size * 0.7, "radio in seed genre: $near/${radio.items.size}")
        assertQueueRules(radio.items)

        val started = engine.startRadio(RadioSeed.Track(world.tracksOf(seed)[0]))
        assertTrue(started.items.count { genreOf(it.track) == outGenre } >= started.items.size * 0.7)
    }

    @Test
    fun coldStartUsesOnboardingArtistsAndCoListening() = runTest {
        val picks = listOf(world.artists[3], world.artists[3 * world.artistsPerGenre + 2]) // genres 0 and 3
        val engine = world.engine(InMemoryPlayEventStore(), clock)
        engine.onboard(picks)
        val queue = engine.upNext()
        assertTrue(queue.size >= 15, "cold start queue ${queue.size}: $queue")
        val pickedGenres = picks.map { world.artistGenre.getValue(it) }.toSet()
        assertTrue(queue.count { genreOf(it.track) in pickedGenres } >= queue.size * 0.8, "picked genres: ${queue.map { genreOf(it.track) }}")
        assertTrue(queue.any { it.reason is Reason.BecauseYouPicked }, "reasons: ${queue.map { it.reason }}")
        assertTrue(queue.all { it.slot == SlotKind.DISCOVERY }, "nothing is familiar without history")
        assertEquals(2, engine.dailyMixes().size, "one pseudo-cluster per onboarding artist")
        assertTrue(engine.weeklyDiscovery().items.isNotEmpty(), "weekly discovery empty")
    }

    @Test
    fun discoveryOutcomesMoveTheExplorationPosterior() = runTest {
        val engine = engineWithHistory()
        val start = engine.explorationState
        val queue = engine.startAutoplay(user.heavyRotation[0])
        val discovery = queue.first { it.slot == SlotKind.DISCOVERY }
        engine.playThrough(discovery, skip = false)
        assertTrue(engine.explorationState.alpha > start.alpha)
        val next = engine.upNext().first { it.slot == SlotKind.DISCOVERY }
        engine.playThrough(next, skip = true)
        assertTrue(engine.explorationState.beta > start.beta)
        // Familiar plays do not move it.
        val before = engine.explorationState
        engine.playThrough(engine.upNext().first { it.slot == SlotKind.FAMILIAR }, skip = true)
        assertEquals(before, engine.explorationState)
    }

    @Test
    fun homeShelfTagsAttributeDiscoveryLikeEngineTags() = runTest {
        val track = world.tracks.last()
        val viaEngine = engineWithHistory()
        val viaHome = engineWithHistory()
        viaEngine.onPlayEvent(play(track, clock.now, EndReason.SKIPPED, msPlayed = 3_000, surfaceDetail = "taste/cf/d"))
        viaHome.onPlayEvent(play(track, clock.now, EndReason.SKIPPED, msPlayed = 3_000, surfaceDetail = "home-turtle/cf/d/weekly_discovery:weekly"))
        assertTrue(viaHome.explorationState != ExplorationState())
        assertEquals(viaEngine.explorationState, viaHome.explorationState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun generatorsRunConcurrentlyAndFailuresAreIsolated() = runTest {
        val slowFresh = object : FreshReleaseSource {
            override suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int): List<TrackMeta> {
                delay(1_500)
                return emptyList()
            }
        }
        val slowUpstream = object : RecommendationSource {
            override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> {
                delay(1_500)
                return world.tracksOfGenre(user.likedGenres.first()).take(limit)
            }
        }
        val engine = engineWithHistory(fresh = slowFresh, upstream = slowUpstream)
        engine.profile() // load history outside the timed section
        val t0 = currentTime
        val queue = engine.startAutoplay(user.heavyRotation[0])
        assertEquals(1_500, currentTime - t0, "two 1.5 s seams ran in parallel")
        assertTrue(queue.size == 20)

        val broken = object : RecommendationSource {
            override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> = error("YouTube changed again")
        }
        val hanging = object : FreshReleaseSource {
            override suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int): List<TrackMeta> {
                delay(60_000)
                return emptyList()
            }
        }
        val resilient = engineWithHistory(fresh = hanging, upstream = broken)
        assertEquals(20, resilient.startAutoplay(user.heavyRotation[0]).size)
    }

    @Test
    fun stateRoundTripsThroughPlainData() = runTest {
        val engine = engineWithHistory()
        engine.startAutoplay(user.heavyRotation[0])
        engine.setDiscoveryDial(DiscoveryDial.ADVENTUROUS)
        val restored = world.engine(
            InMemoryPlayEventStore().also { it.appendAll(history) }, clock,
            controls = engine.controls,
        )
        assertNotNull(restored.profile())
        assertEquals(DiscoveryDial.ADVENTUROUS, restored.controls.discoveryDial)
        assertTrue(engine.recentlyRecommended().size >= 20)
    }

    @Test
    fun rankExternalOrdersOnlyTheGivenTracksByTaste() = runTest {
        val engine = engineWithHistory()
        val seed = user.heavyRotation[0]
        val disliked = (0 until world.genres).first { it !in user.likedGenres }
        // Distinct artists, so the artist-spacing rule does not interleave them by necessity.
        val inTaste = user.likedGenres.flatMap { world.tracksOfGenre(it) }.filter { it.artistKey != seed.artistKey }.distinctBy { it.artistKey }.take(10)
        val outOfTaste = world.tracksOfGenre(disliked).distinctBy { it.artistKey }.take(8)
        // Upstream order deliberately puts the out-of-taste tracks first.
        val upstream = outOfTaste + inTaste

        val ranked = engine.rankExternal(seed, upstream)

        assertEquals(upstream.map { it.key }.toSet(), ranked.map { it.key }.toSet(), "nothing added or lost")
        val firstHalf = ranked.take(inTaste.size).count { genreOf(it.track) in user.likedGenres }
        assertTrue(firstHalf >= inTaste.size - 2, "in-taste tracks first: $firstHalf/${inTaste.size}")
        assertTrue(ranked.all { it.source == CandidateSource.UPSTREAM_RADIO })

        val muted = engineWithHistory(controls = UserControls(mutedArtists = setOf(inTaste[0].artistKey)))
        assertTrue(muted.rankExternal(seed, upstream).none { it.track.artistKey == inTaste[0].artistKey })
    }

    @Test
    fun lessLikeThisSurvivesARestartAndKeepsTheTrackOutOfRadio() = runTest {
        val engine = engineWithHistory()
        val seed = user.heavyRotation[0]
        val candidates = user.likedGenres.flatMap { world.tracksOfGenre(it) }.filter { it.artistKey != seed.artistKey }.distinctBy { it.artistKey }.take(10)
        val unwanted = candidates[3]
        engine.lessLikeThis(LessLikeThis.Track(unwanted.key, unwanted.artistKey))
        engine.startAutoplay(seed)
        val saved = engine.exportState()

        val restarted = engineWithHistory()
        assertTrue(restarted.importState(saved))
        assertTrue(unwanted.key in restarted.controls.lessLikeThisTracks)
        assertEquals(engine.recentlyRecommended(), restarted.recentlyRecommended())
        assertTrue(restarted.rankExternal(seed, candidates).none { it.key == unwanted.key })
        assertFalse(restarted.importState("not a saved state"))
    }

    @Test
    fun tasteStateRoundTripsEveryControl() {
        val state = TasteState(
            UserControls(
                mutedArtists = setOf(ArtistKey("a")),
                boostedArtists = setOf(ArtistKey("b")),
                mutedClusters = listOf(ClusterAnchor(listOf(0.6f, 0.8f))),
                lessLikeThisTracks = setOf(TrackKey("yt:x")),
                lessLikeThisArtists = mapOf(ArtistKey("c") to 3),
                suppressedReasons = setOf("weekly"),
                onboardingArtists = listOf(ArtistKey("d"), ArtistKey("e")),
            ),
            ExplorationState(3.5, 7.25),
            listOf(TrackKey("yt:1"), TrackKey("yt:2")),
        )
        assertEquals(state, TasteState.decode(state.encode()))
        assertEquals(TasteState(), TasteState.decode(TasteState().encode()))
    }

    @Test
    fun undoRestoresWhatWasRuledOut() {
        val a = ArtistKey("a")
        val t = TrackKey.youtube("t")
        val m = ArtistKey("m")
        val c = UserControls().apply(LessLikeThis.Track(t, a)).apply(LessLikeThis.Track(t, a))
            .copy(mutedArtists = setOf(m), suppressedReasons = setOf("mix:x"))
        assertEquals(listOf(Hidden.Track(t, null), Hidden.Artist(m), Hidden.Shelf("mix:x")), c.hidden())
        val u = c.undo(Hidden.Track(t, a)).undo(Hidden.Artist(m)).undo(Hidden.Shelf("mix:x"))
        assertEquals(UserControls(lessLikeThisArtists = mapOf(a to 1)), u)
        // The mark count never goes below zero.
        assertEquals(emptyMap(), c.undo(Hidden.Track(t, a)).undo(Hidden.Track(t, a)).undo(Hidden.Track(t, a)).lessLikeThisArtists)
    }

    @Test
    fun notInterestedFeedbackRulesOutAndUndoBringsBack() = runTest {
        val engine = engineWithHistory()
        val target = engine.startAutoplay(user.heavyRotation[0])[2].track
        engine.onFeedback(TrackFeedback(track = target, kind = TrackFeedbackKind.NOT_INTERESTED, atMs = clock.now))
        assertTrue(target.key in engine.controls.lessLikeThisTracks)
        assertTrue(engine.upNext().none { it.key == target.key })
        assertTrue(engine.profile().track(target.key)!!.lessLikeThis)
        clock.now += MINUTE_MS
        engine.onFeedback(TrackFeedback(track = target, kind = TrackFeedbackKind.UNDO_NOT_INTERESTED, atMs = clock.now))
        assertFalse(target.key in engine.controls.lessLikeThisTracks)
        assertFalse(engine.profile().track(target.key)!!.lessLikeThis)
    }

    @Test
    fun forYouLeavesTheLiveSessionAlone() = runTest {
        val engine = engineWithHistory()
        val upNext = engine.startAutoplay(user.heavyRotation[0])
        val recent = engine.recentlyRecommended()
        val picks = engine.forYou(12)
        assertEquals(12, picks.size)
        assertQueueRules(picks)
        assertEquals(upNext, engine.upNext())
        assertEquals(recent, engine.recentlyRecommended())
    }

    @Test
    fun appBlocksApplyLiveAndAreNeverSaved() = runTest {
        var blocked = BlockedSet.NONE
        val store = InMemoryPlayEventStore().also { it.appendAll(history) }
        val engine = world.engine(store, clock, blocks = ExternalBlocks { blocked })
        val seed = user.heavyRotation[0]
        val tracks = engine.startAutoplay(seed).map { it.track }
        val artist = tracks.first().artistKey
        val song = tracks.last { it.artistKey != artist }
        blocked = BlockedSet(tracks = setOf(song.key), artists = setOf(artist))
        val ranked = engine.rankExternal(seed, tracks)
        assertTrue(ranked.none { it.track.artistKey == artist || it.key == song.key })
        assertTrue(artist in engine.controls.mutedArtists)
        assertFalse(artist in engine.listenerControls.mutedArtists)
        val saved = TasteState.decode(engine.exportState())!!
        assertFalse(artist in saved.controls.mutedArtists || song.key in saved.controls.lessLikeThisTracks)
        blocked = BlockedSet.NONE // unblocked upstream
        val again = engine.rankExternal(seed, tracks)
        assertTrue(again.any { it.track.artistKey == artist } && again.any { it.key == song.key })
    }

    @Test
    fun storedFeedbackShapesTheProfileAfterARestartAndCountsOnce() = runTest {
        val store = InMemoryPlayEventStore().also { it.appendAll(history) }
        val fresh = world.tracks.first { t -> history.none { it.trackKey == t.key } }
        val w = AffinityWeights()
        store.appendFeedback(TrackFeedback(track = fresh, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = clock.now - DAY_MS))
        assertEquals(w.addedToPlaylist * w.decay(DAY_MS), world.engine(store, clock).profile().track(fresh.key)!!.affinity, 1e-9)
        // The app stores an add, then hands it to an engine that has not loaded yet: it must count once, not twice.
        val live = TrackFeedback(track = fresh, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = clock.now)
        store.appendFeedback(live)
        val engine = world.engine(store, clock)
        engine.onFeedback(live)
        assertEquals(w.addedToPlaylist * (w.decay(DAY_MS) + 1.0), engine.profile().track(fresh.key)!!.affinity, 1e-9)
    }

    @Test
    fun aRebuildBetweenStoringAndHandingInFeedbackCountsItOnce() = runTest {
        val store = InMemoryPlayEventStore().also { it.appendAll(history) }
        val fresh = world.tracks.first { t -> history.none { it.trackKey == t.key } }
        val engine = world.engine(store, clock)
        engine.profile()
        val add = TrackFeedback(track = fresh, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = clock.now)
        val stored = add.copy(id = store.appendFeedback(add))
        engine.refresh() // e.g. the library sync or a co-listening rebuild, before the app hands the add in
        engine.onFeedback(stored)
        assertEquals(AffinityWeights().addedToPlaylist, engine.profile().track(fresh.key)!!.affinity, 1e-9)
    }

    private val fullTrack = TrackMeta(
        TrackKey("yt:a1"), "Song", listOf("Artist A", "Guest"), album = "LP", durationMs = 201_000,
        releasedAtMs = 1_700_000_000_000, youtubeId = "a1", isrc = "USX1", explicit = true, popularity = 0.25f,
    )
    private val bareTrack = TrackMeta(TrackKey("meta:artist b|song,live"), "Bare", listOf("Artist B"), album = "")
    private fun item(t: TrackMeta, reason: Reason = Reason.SimilarTo(ArtistKey("a"))) =
        QueueItem(t, CandidateSource.SESSION, reason, SlotKind.FAMILIAR, 1f)

    @Test
    fun tasteStateRoundTripsKeptShelves() {
        val items = listOf(
            QueueItem(fullTrack, CandidateSource.UPSTREAM_RADIO, Reason.UpstreamRadio(TrackKey("yt:seed")), SlotKind.DISCOVERY, 0.8125f),
            QueueItem(bareTrack, CandidateSource.LIBRARY, Reason.Rediscover(TrackKey("yt:r"), 1_600_000_000_000), SlotKind.FAMILIAR, -1.5f),
        )
        val mix = Shelf("daily_mix:artist:artist a", ShelfKind.DAILY_MIX, Reason.DailyMix(0, listOf(ArtistKey("artist a")), seedArtist = ArtistKey("artist a")), items)
        val weekly = Shelf("weekly_discovery:weekly", ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, items.reversed())
        val state = TasteState(
            UserControls(mutedArtists = setOf(ArtistKey("m"))),
            kept = listOf(KeptShelves(ShelfCadence.DAILY, 20_000, listOf(mix)), KeptShelves(ShelfCadence.WEEKLY, 19_997, listOf(weekly))),
        )
        assertEquals(state, TasteState.decode(state.encode()))
        assertTrue(state.encode().startsWith("turtle-taste-state 1\n"), "header unchanged, so older builds still read it")
    }

    @Test
    fun everyHomeReasonKeepsItsKeyAcrossARoundTrip() {
        val a = ArtistKey("a")
        val b = ArtistKey("b")
        val t = TrackKey("yt:t")
        val reasons = listOf(
            Reason.BecauseYouListened(t), Reason.BecauseYouReplayed(t), Reason.BecauseYouLiked(t), Reason.SimilarTo(a),
            Reason.ListenersAlsoPlay(a), Reason.FromYourTaste(2, listOf(a, b)), Reason.FreshFrom(a), Reason.Rediscover(t, 42),
            Reason.UpstreamRadio(t), Reason.BecauseYouPicked(a), Reason.DailyMix(1, listOf(a, b)), Reason.DailyMix(0, listOf(a), seedArtist = a),
            Reason.OnRepeat, Reason.NewForYou, Reason.FreshForYou, Reason.RediscoverShelf,
        )
        for (r in reasons) {
            val shelf = Shelf("s", ShelfKind.MORE_LIKE, r, listOf(item(fullTrack, r)))
            val decoded = TasteState.decode(TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY_MORE_LIKE, 1, listOf(shelf)))).encode())!!
            val back = decoded.kept.single().shelves.single()
            assertEquals(r, back.reason)
            assertEquals(r.key, back.reason.key, "suppressedReasons match by key")
            assertEquals(r, back.items.single().reason)
        }
    }

    @Test
    fun versionOneStateWithoutShelvesStillLoads() {
        val v1 = "turtle-taste-state 1\nmuted\tartist x\nboosted\t\nlessTracks\tyt:gone\nlessArtists\tartist y\u001d2\n" +
            "suppressed\tmix:artist z\nonboarding\t\nmutedClusters\t\nboostedClusters\t\nexploration\t2.0\u001f3.0\nrecent\tyt:1\u001fyt:2"
        val expected = TasteState(
            UserControls(
                mutedArtists = setOf(ArtistKey("artist x")), lessLikeThisTracks = setOf(TrackKey("yt:gone")),
                lessLikeThisArtists = mapOf(ArtistKey("artist y") to 2), suppressedReasons = setOf("mix:artist z"),
            ),
            ExplorationState(2.0, 3.0),
            listOf(TrackKey("yt:1"), TrackKey("yt:2")),
        )
        assertEquals(expected, TasteState.decode(v1))
        assertNull(TasteState.decode(v1.replace("turtle-taste-state 1", "turtle-taste-state 9")))
    }

    @Test
    fun unsafeTextOrAnUnreadableItemCostsOnlyThatItem() {
        val good1 = item(TrackMeta(TrackKey("yt:g1"), "Fine", listOf("A")))
        val good2 = item(bareTrack) // '|' and ',' in a meta key, and an empty (not null) album
        val bad = listOf(
            item(TrackMeta(TrackKey("yt:b1"), "Tab\there", listOf("A"))),
            item(TrackMeta(TrackKey("yt:b2"), "Fine", listOf("A\u001eB"))),
            item(TrackMeta(TrackKey("yt:b3"), "Fine", listOf("A"), album = "\u001c")),
            item(TrackMeta(TrackKey("yt:b4"), "Fine", listOf("A")), Reason.RadioFrom(RadioSeed.Artist(ArtistKey("a")))),
            item(TrackMeta(TrackKey("yt:b5"), "Fine", listOf("A")), Reason.UpNext),
        )
        val shelf = Shelf("more_like:yt:g1", ShelfKind.MORE_LIKE, Reason.BecauseYouReplayed(TrackKey("yt:g1")), listOf(good1) + bad + good2)
        val unsafeShelf = shelf.copy(id = "bad\nid")
        val state = TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY_MORE_LIKE, 20_000, listOf(shelf, unsafeShelf))))
        assertEquals(listOf(shelf.copy(items = listOf(good1, good2))), TasteState.decode(state.encode())!!.kept.single().shelves)

        // A damaged item line (its key lost the scheme) drops only that item.
        val clean = TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY_MORE_LIKE, 20_000, listOf(shelf.copy(items = listOf(good1, good2))))))
        val damaged = clean.encode().replace("item\tyt:g1\u001f", "item\tnoscheme\u001f")
        assertEquals(listOf(good2), TasteState.decode(damaged)!!.kept.single().shelves.single().items)
    }

    @Test
    fun unreadableShelvesKeepTheListenersControls() {
        val shelf = Shelf("weekly_discovery:weekly", ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, listOf(item(fullTrack)))
        val state = TasteState(
            UserControls(mutedArtists = setOf(ArtistKey("m")), lessLikeThisTracks = setOf(TrackKey("yt:x"))),
            kept = listOf(KeptShelves(ShelfCadence.WEEKLY, 19_997, listOf(shelf))),
        )
        val text = state.encode()
        val withoutShelves = state.copy(kept = emptyList())
        assertEquals(withoutShelves, TasteState.decode(text.replace("shelves\t1", "shelves\t2")), "future shelf format")
        assertEquals(withoutShelves, TasteState.decode(text.replace("kept\tWEEKLY\u001f19997", "kept\tNOT_A_CADENCE\u001fx")), "unknown cadence")
        assertEquals(withoutShelves, TasteState.decode(text.replace("\u001fWEEKLY_DISCOVERY\u001f", "\u001fGONE_KIND\u001f")), "unknown kind")
    }

    @Test
    fun artistSkippedTwiceThisSessionWaitsAtTheBackOfRadio() = runTest {
        val engine = engineWithHistory()
        val seed = user.heavyRotation[0]
        val candidates = user.likedGenres.flatMap { world.tracksOfGenre(it) }.filter { it.artistKey != seed.artistKey }.distinctBy { it.artistKey }.take(10)
        val before = engine.rankExternal(seed, candidates)
        val skipped = before.first().track.artistKey
        val theirs = world.tracks.filter { it.artistKey == skipped }.take(2)
        for (t in theirs) {
            engine.onPlayEvent(play(t, clock.now, EndReason.SKIPPED, msPlayed = 5_000))
            clock.now += 5_000
        }
        val after = engine.rankExternal(seed, candidates)
        assertEquals(candidates.map { it.key }.toSet(), after.map { it.key }.toSet(), "nothing added or lost")
        assertEquals(skipped, after.last().track.artistKey)
    }

    @Test
    fun oneShotAutoplayDoesNotCallUpstreamOnLaterPlays() = runTest {
        var calls = 0
        val counting = object : RecommendationSource {
            override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> {
                calls++
                return world.tracksOfGenre(user.likedGenres.first()).take(limit)
            }
        }
        val engine = engineWithHistory(upstream = counting)
        engine.startAutoplay(continuous = false)
        val afterStart = calls
        repeat(3) {
            engine.onPlayEvent(play(user.heavyRotation[it], clock.now))
            clock.now += 200_000
        }
        assertEquals(afterStart, calls)
    }
}
