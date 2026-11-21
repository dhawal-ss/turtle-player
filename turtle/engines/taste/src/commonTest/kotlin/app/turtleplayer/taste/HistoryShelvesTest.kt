package app.turtleplayer.taste

import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home shelves while the co-listening packs are missing: an EMPTY embedding store, real history
 * and an upstream (YouTube-like) radio.
 */
class HistoryShelvesTest {
    private val world = SyntheticWorld()
    private val user = world.user(0)
    private val history = world.history(user)
    private val clock = MutableClock(history.last().startedAtMs + 30 * MINUTE_MS)

    /** Deterministic fake radio: tracks of the seed's genre, then of the next genre, rotated per seed. Records every call. */
    private class FakeRadio(private val world: SyntheticWorld, private val latencyMs: Long = 0) : RecommendationSource {
        val calls = ArrayList<TrackKey>()

        override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> {
            calls += seed.key
            if (latencyMs > 0) delay(latencyMs)
            val g = world.trackGenre.getValue(seed.key)
            val genre = world.tracksOfGenre(g)
            val shift = genre.indexOf(seed).coerceAtLeast(0)
            val pool = genre.drop(shift) + genre.take(shift) + world.tracksOfGenre((g + 1) % world.genres)
            return pool.filter { it.key != seed.key }.take(limit)
        }
    }

    private suspend fun engine(
        events: List<PlayEvent> = history,
        upstream: RecommendationSource? = FakeRadio(world),
        store: EmbeddingStore = InMemoryEmbeddingStore(world.dim),
        controls: UserControls = UserControls(),
    ) = TasteEngine(
        events = InMemoryPlayEventStore().also { it.appendAll(events) },
        store = store, catalog = world.catalog, upstream = upstream, clock = clock, random = Random(7), controls = controls,
    )

    private fun assertSpacing(items: List<QueueItem>, spacing: Int) {
        val artists = items.map { it.track.artistKey }
        for (i in artists.indices) assertFalse(artists[i] in artists.subList(maxOf(0, i - spacing), i), "spacing broken: $artists")
    }

    @Test
    fun homeWithoutVectorsHasAllHistoryShelvesInOrder() = runTest {
        val radio = FakeRadio(world)
        val engine = engine(upstream = radio)
        val profile = engine.profile()
        assertTrue(profile.clusters.isEmpty(), "no vectors, no clusters")
        val shelves = engine.homeShelves()
        val kinds = shelves.map { it.kind }.distinct()
        val order = listOf(ShelfKind.DAILY_MIX, ShelfKind.MORE_LIKE, ShelfKind.ON_REPEAT, ShelfKind.WEEKLY_DISCOVERY, ShelfKind.FRESH_FOR_YOU, ShelfKind.REDISCOVER)
        assertEquals(order.take(4), kinds.take(4), "kinds: $kinds")
        assertEquals(kinds.sortedBy { order.indexOf(it) }, kinds)
        assertTrue(shelves.all { it.items.isNotEmpty() })
        assertEquals(shelves.size, shelves.map { it.id }.toSet().size, "unique ids")
        assertTrue(radio.calls.size <= 5, "bounded upstream calls: ${radio.calls.size}")
        assertEquals(radio.calls.size, radio.calls.toSet().size, "each seed fetched once")
    }

    @Test
    fun onRepeatHoldsTheirMostFinishedRecentTracks() = runTest {
        val engine = engine()
        val profile = engine.profile()
        val shelf = engine.homeShelves().single { it.kind == ShelfKind.ON_REPEAT }
        assertEquals(Reason.OnRepeat, shelf.reason)
        assertTrue(shelf.items.size in 10..20, "size ${shelf.items.size}")
        val cutoff = clock.now - 30 * DAY_MS
        for (item in shelf.items) {
            val s = assertNotNull(profile.track(item.key))
            assertTrue(s.lastPlayedMs >= cutoff && s.finishes > 0 && s.affinity > 0, "recent and finished: $s")
            assertEquals(SlotKind.FAMILIAR, item.slot)
        }
        // The top item is among the most-finished tracks of the last 30 days.
        val finishes30 = history.filter { it.startedAtMs >= cutoff && it.isFinished }.groupingBy { it.trackKey }.eachCount()
        val best = finishes30.values.max()
        assertTrue(finishes30.getValue(shelf.items.first().key) >= best - 2, "top: ${finishes30[shelf.items.first().key]} of max $best")
        assertTrue(shelf.items.zipWithNext().none { (a, b) -> a.track.artistKey == b.track.artistKey }, "no artist back to back")

        val dropped = shelf.items.first()
        engine.lessLikeThis(dropped.lessLikeThis)
        assertTrue(engine.onRepeat().items.none { it.key == dropped.key })
    }

    @Test
    fun artistMixesCoverDistinctTopArtistsWithMostlyDistinctTracks() = runTest {
        val engine = engine()
        val profile = engine.profile()
        val mixes = engine.homeShelves().filter { it.kind == ShelfKind.DAILY_MIX }
        assertEquals(3, mixes.size)
        val artists = mixes.map { (it.reason as Reason.DailyMix).seedArtist }
        assertEquals(3, artists.filterNotNull().toSet().size, "distinct artists: $artists")
        val top = profile.topArtists(10).map { it.artist }
        assertTrue(artists.all { it in top.take(5) }, "top-affinity artists: $artists vs $top")
        for (mix in mixes) {
            val reason = mix.reason as Reason.DailyMix
            assertEquals(listOf(reason.seedArtist!!), reason.topArtists)
            assertTrue(mix.items.size >= 20, "mix size ${mix.items.size}")
            assertTrue(mix.items.count { it.track.artistKey == reason.seedArtist } >= 3, "mix of ${reason.seedArtist} holds its artist: ${mix.items.map { it.track.artistKey }}")
            val familiar = mix.items.count { profile.hasHeard(it.key) }.toDouble() / mix.items.size
            // The plan is 60% familiar; this synthetic listener has heard nearly all of their genres,
            // so discovery slots run short of supply and fall back to familiar ones.
            assertTrue(familiar in 0.5..0.85, "familiar share $familiar")
            assertSpacing(mix.items, 3)
        }
        for (i in mixes.indices) for (j in i + 1 until mixes.size) {
            val shared = mixes[i].items.map { it.key }.intersect(mixes[j].items.map { it.key }.toSet()).size
            // Mixes 1 and 2 seed from the same 48-track genre here, so some sharing is unavoidable.
            assertTrue(shared * 3 <= minOf(mixes[i].items.size, mixes[j].items.size), "mixes $i and $j share $shared tracks")
        }
        assertEquals(mixes.map { it.id }, engine.dailyMixes().map { it.id })
    }

    @Test
    fun moreLikeWithoutVectorsUsesTheSeedRadioAndExcludesTheSeed() = runTest {
        val engine = engine()
        val shelf = engine.homeShelves().single { it.kind == ShelfKind.MORE_LIKE }
        val seed = when (val r = shelf.reason) {
            is Reason.BecauseYouReplayed -> r.track
            is Reason.BecauseYouLiked -> r.track
            else -> error("replayed or liked pick expected: $r")
        }
        assertTrue(shelf.items.size >= 15, "size ${shelf.items.size}")
        assertTrue(shelf.items.none { it.key == seed }, "seed excluded")
        assertTrue(shelf.items.any { it.source == CandidateSource.UPSTREAM_RADIO }, "upstream radio used")
        val seedGenre = world.trackGenre.getValue(seed)
        assertTrue(shelf.items.count { world.trackGenre[it.key] in setOf(seedGenre, (seedGenre + 1) % world.genres) } >= shelf.items.size * 0.8)
    }

    @Test
    fun moreLikeFallsBackToTheMostFinishedTrack() = runTest {
        val plain = history.map { it.copy(liked = false, replayed = false) }
        val engine = engine(events = plain)
        val shelf = engine.homeShelves().single { it.kind == ShelfKind.MORE_LIKE }
        val reason = shelf.reason as Reason.BecauseYouListened
        val cutoff = clock.now - 30 * DAY_MS
        val finishes30 = plain.filter { it.startedAtMs >= cutoff && it.isFinished }.groupingBy { it.trackKey }.eachCount()
        val top5 = finishes30.entries.sortedByDescending { it.value }.take(5).map { it.value }
        // A song found this week (its first play held a minute) comes before the most-finished ones.
        val stats = engine.profile().track(reason.track)!!
        val freshFind = (stats.foundMs ?: Long.MIN_VALUE) >= clock.now - 7 * DAY_MS && stats.plays <= 3
        assertTrue(freshFind || (finishes30[reason.track] ?: 0) >= top5.last(), "pick ${finishes30[reason.track]} vs top $top5")
        assertTrue(shelf.items.none { it.key == reason.track }, "seed excluded")
    }

    @Test
    fun weeklyDiscoveryWithoutVectorsIsNeverHeardFromUpstream() = runTest {
        val engine = engine()
        val profile = engine.profile()
        val weekly = engine.homeShelves().single { it.kind == ShelfKind.WEEKLY_DISCOVERY }
        assertEquals(Reason.NewForYou, weekly.reason)
        assertTrue(weekly.items.size in 5..25, "size ${weekly.items.size}")
        assertTrue(weekly.items.none { profile.hasHeard(it.key) }, "never-heard only")
        assertTrue(weekly.items.all { it.reason is Reason.UpstreamRadio })
        // Never-heard artists come first (no artist back to back, so a known one may sit between them).
        val newArtist = weekly.items.map { !profile.hasHeardArtist(it.track.artistKey) }
        assertTrue(newArtist.first(), "first is a new artist: $newArtist")
        assertTrue(newArtist.lastIndexOf(true) < 2 * newArtist.count { it }, "new artists lead: $newArtist")
        assertEquals(weekly.items.map { it.key }, engine.weeklyDiscovery().items.map { it.key })
    }

    @Test
    fun noHistoryMeansNoUpstreamCallsAndNoShelves() = runTest {
        val radio = FakeRadio(world)
        val engine = engine(events = emptyList(), upstream = radio)
        assertTrue(engine.homeShelves().isEmpty())
        assertTrue(engine.dailyMixes().isEmpty())
        assertTrue(engine.weeklyDiscovery().items.isEmpty())
        assertEquals(emptyList(), radio.calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun upstreamCallsRunConcurrentlyAndFailuresAreTolerated() = runTest {
        val slow = FakeRadio(world, latencyMs = 1_000)
        val engine = engine(upstream = slow)
        engine.profile()
        val t0 = currentTime
        assertTrue(engine.homeShelves().isNotEmpty())
        assertEquals(1_000, currentTime - t0, "radios fetched in parallel")
        assertEquals(5, slow.calls.size)

        val broken = object : RecommendationSource {
            override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> = error("YouTube changed again")
        }
        val shelves = engine(upstream = broken).homeShelves()
        assertTrue(shelves.any { it.kind == ShelfKind.ON_REPEAT })
        assertTrue(shelves.none { it.kind == ShelfKind.WEEKLY_DISCOVERY }, "nothing to discover without radio")

        val hanging = FakeRadio(world, latencyMs = 60_000)
        val t1 = currentTime
        val timedOut = engine(upstream = hanging).homeShelves()
        assertTrue(currentTime - t1 <= 2_000, "timeout bounds the refresh")
        assertTrue(timedOut.any { it.kind == ShelfKind.ON_REPEAT })
    }

    @Test
    fun withVectorsHomeKeepsClusterMixesAndSkipsUpstream() = runTest {
        val radio = FakeRadio(world)
        val engine = engine(upstream = radio, store = world.store)
        val profile = engine.profile()
        val shelves = engine.homeShelves()
        val mixes = shelves.filter { it.kind == ShelfKind.DAILY_MIX }
        assertEquals(profile.clusters.size, mixes.size)
        assertTrue(mixes.all { (it.reason as Reason.DailyMix).seedArtist == null })
        assertTrue(shelves.single { it.kind == ShelfKind.WEEKLY_DISCOVERY }.items.none { it.source == CandidateSource.UPSTREAM_RADIO })
        assertTrue(ShelfKind.ON_REPEAT in shelves.map { it.kind })
        assertEquals(emptyList(), radio.calls, "vectors cover every shelf: no network")
    }

    @Test
    fun displayNamesComeFromTheProfileAndCatalog() = runTest {
        val engine = engine()
        val t = user.heavyRotation[0]
        assertEquals(t.title, engine.displayName(t.key))
        assertEquals(t.artists.first(), engine.displayName(t.artistKey))
        val unheard = world.tracks.first { it.artistKey !in user.favoriteArtists }
        assertEquals(unheard.title, engine.displayName(unheard.key))
        assertNull(engine.displayName(TrackKey("mbid:nope")))
    }
}
