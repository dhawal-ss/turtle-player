package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
