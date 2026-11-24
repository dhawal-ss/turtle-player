package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.CandidateSource
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.LessLikeThis
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.SlotKind
import app.turtleplayer.taste.SurfaceTag
import app.turtleplayer.taste.TasteEngine
import app.turtleplayer.taste.UserControls
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Step 4a: Infinity's genre picks pass the same checks as everything else it plays. */
class InfinityMixTest {
    private val t0 = 1_793_577_600_000L
    private val stores = ArrayList<SqlitePlayEventStore>()

    @BeforeTest
    fun reset() {
        // Both are process-wide: a mix shares its state flow, and the dial is the listener's setting.
        infinityStateFlow.value = InfinityState()
        discoveryDialState.value = 1.0
    }

    @AfterTest
    fun tearDown() {
        stores.forEach { it.close() }
        infinityStateFlow.value = InfinityState()
        discoveryDialState.value = 0.5
    }

    private fun song(
        id: String,
        artist: String = "Artist $id",
        title: String = "Song $id",
    ) = SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    /** YouTube as the mix sees it; remembers every radio it was asked for. */
    private class FakeSource(
        var hits: List<SongItem>,
        var radio: (String) -> List<SongItem> = { emptyList() },
    ) : InfinitySource {
        val seeds = ArrayList<String>()

        override suspend fun search(query: String) = hits

        override suspend fun radio(seedId: String): List<SongItem> {
            seeds += seedId
            return radio.invoke(seedId)
        }
    }

    private class Votes(
        private val disliked: Set<String> = emptySet(),
        override val liked: Set<String> = emptySet(),
    ) : SongVotes {
        override fun isDisliked(id: String) = id in disliked
    }

    private class Mix(
        val mix: InfinityMix,
        val taste: TasteEngine,
        val observer: PlayerObserver,
    )

    private suspend fun mix(
        source: InfinitySource,
        votes: SongVotes = Votes(),
        controls: UserControls = UserControls(),
        known: List<SongItem> = emptyList(),
        seed: Int = 7,
    ): Mix {
        val file =
            File.createTempFile("infinity", ".db").apply {
                delete()
                deleteOnExit()
            }
        val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).also(stores::add)
        val clock = Clock { t0 }
        val taste =
            TasteEngine(
                events = InMemoryPlayEventStore(),
                store = InMemoryEmbeddingStore(),
                catalog = InMemoryTrackCatalog(emptyList()),
                clock = clock,
                random = Random(seed),
                controls = controls,
            )
        val cache = SongCache().apply { putAll(known) }
        val observer =
            PlayerObserver(
                ListeningSessionTracker(InMemoryPlayEventStore()),
                controller = { null },
                isBookChapter = { false },
                spokenReady = {},
            )
        val mix = InfinityMix(store, taste, cache, observer, source, Random(seed), votes, clock)
        mix.setGenres(listOf("metal"))
        return Mix(mix, taste, observer)
    }

    private val hits = List(8) { song("h$it") }

    private fun radioOf(seed: String) = List(8) { song("$seed-r$it") }

    @Test
    fun aSeedGivesTheSameBatch() =
        runBlocking {
            suspend fun batch(): List<String> {
                infinityStateFlow.value = InfinityState()
                return mix(FakeSource(hits) { radioOf(it) }).mix.start().map { it.id }
            }
            val first = batch()
            assertTrue(first.isNotEmpty())
            assertEquals(first, batch())
        }

    @Test
    fun aRuledOutHitNeverPlaysNorSeedsARadio() =
        runBlocking {
            val disliked = song("d1")
            val notInterested = song("n1", artist = "Band N", title = "Song N")
            // The same recording on the artist's "- Topic" channel: another upload, another id.
            val twin = song("n1t", artist = "Band N - Topic", title = "Song N")
            val muted = song("m1", artist = "Muted Band")
            val excluded = setOf(disliked.id, notInterested.id, twin.id, muted.id)
            // One eligible hit among four ruled out: any seed taken without the checks would be a ruled-out one.
            val good = listOf(song("o0"))
            val source = FakeSource(listOf(disliked, notInterested, twin, muted) + good) { listOf(disliked, twin, muted) + radioOf(it) }
            val controls =
                UserControls(
                    lessLikeThisTracks = setOf(TrackKey.youtube(notInterested.id)),
                    mutedArtists = setOf(ArtistKey.of("Muted Band")),
                )
            // The Not-interested song was never played and is not cached: its twin is caught from the same response.
            val m = mix(source, Votes(disliked = setOf(disliked.id)), controls)

            val played = m.mix.start().map { it.id } + m.mix.next(12, emptySet()).map { it.id }
            assertTrue(played.isNotEmpty())
            assertEquals(emptySet(), played.toSet() intersect excluded, "played $played")
            assertEquals(emptySet(), source.seeds.toSet() intersect excluded, "seeded ${source.seeds}")
        }

    @Test
    fun aPoolOfRuledOutSongsStillRefillsWithEligibleOnes() =
        runBlocking {
            val later = List(3) { song("q$it") }
            val source = FakeSource(hits) { radioOf(it) }
            val m = mix(source)
            val first = m.mix.next(1, emptySet())
            assertEquals(1, first.size)
            // Everything stocked so far is ruled out before the next song is needed.
            val stocked = hits + source.seeds.flatMap(::radioOf)
            for (s in stocked) m.taste.lessLikeThis(LessLikeThis.Track(TrackKey.youtube(s.id), ArtistKey.of(s.artists.first().name)))
            source.hits = later
            source.radio = { emptyList() }

            val next = m.mix.next(1, first.map { it.id }.toSet())
            assertEquals(1, next.size, "nothing eligible came back")
            assertTrue(next.single().id in later.map { it.id }, "${next.single().id} was ruled out")
        }

    @Test
    fun aGenreWhoseHitsAllPlayedKeepsSeedingRadios() =
        runBlocking {
            // Each radio call brings three songs not heard from it before, as YouTube's radios do.
            var calls = 0
            val source = FakeSource(hits.take(2)) { seed -> calls++.let { n -> List(3) { song("$seed-c$n-$it") } } }
            val m = mix(source)
            // Both hits are served, so neither can play again, but they may still seed a radio.
            val served =
                m.mix
                    .next(12, emptySet())
                    .map { it.id }
                    .toMutableSet()
            served += hits.take(2).map { it.id }
            val more = m.mix.next(4, served)
            assertTrue(more.isNotEmpty(), "the genre dried up")
            assertTrue(more.none { it.id in served })
        }

    @Test
    fun aLikedSongRuledOutLaterSeedsNothing() =
        runBlocking {
            val source = FakeSource(hits) { radioOf(it) }
            val m = mix(source)
            // Liked while the mix plays: a like only tunes the live mix.
            val opening = m.mix.start().map { it.id }.toSet()
            val liked = m.mix.next(1, opening).single()
            m.mix.feedback(liked, liked = true)
            assertEquals(1, m.mix.state.value.likes, "the like reached the live mix")
            m.taste.lessLikeThis(LessLikeThis.Track(TrackKey.youtube(liked.id), ArtistKey.of(liked.artists.first().name)))
            val seededBefore = source.seeds.size
            val played = mutableSetOf(liked.id)
            repeat(4) { played += m.mix.next(12, played).map { it.id } }
            assertTrue(source.seeds.size > seededBefore, "no refill happened")
            assertTrue(liked.id !in source.seeds.drop(seededBefore), "seeded ${source.seeds.drop(seededBefore)}")
        }

    // ---- Learning bound to the mix's own plays ---------------------------------------------------

    private val metalTag =
        SurfaceTag.format(PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, row = "genre:metal"))

    private fun finished(
        song: SongItem,
        tag: String?,
        reason: StartReason = StartReason.AUTOPLAY,
        replayed: Boolean = false,
    ) = PlayEvent(
        track = song.toTrackMeta(),
        sessionId = "s",
        position = 0,
        surface = Surface.AUTOPLAY,
        startReason = reason,
        endReason = EndReason.COMPLETED,
        startedAtMs = t0,
        msPlayed = 200_000,
        durationMs = 200_000,
        replayed = replayed,
        surfaceDetail = tag,
    )

    private fun Mix.metalWeight() = mix.state.value.weights["metal"] ?: 1.0

    /** A song the opening batch picked from the metal genre. */
    private fun Mix.metalPick(batch: List<SongItem>) = batch.first { mix.state.value.why[it.id] == InfinityWhy.Genre("metal") }

    @Test
    fun aMixSongPlayedFromElsewhereTunesNothing() =
        runBlocking {
            val m = mix(FakeSource(hits) { radioOf(it) })
            val pick = m.metalPick(m.mix.start())
            val start = m.metalWeight()
            // Played again later from a playlist: no Infinity tag on the play.
            m.mix.onPlayEvent(finished(pick, null, StartReason.TAPPED))
            assertEquals(start, m.metalWeight())
            // A replay or a step back to it, even with the mix's tag.
            m.mix.onPlayEvent(finished(pick, metalTag, replayed = true))
            m.mix.onPlayEvent(finished(pick, metalTag, StartReason.USER_PREVIOUS))
            assertEquals(start, m.metalWeight())
            // Control: the mix's own play, heard to the end, tunes its genre up.
            m.mix.onPlayEvent(finished(pick, metalTag))
            assertTrue(m.metalWeight() > start, "weight ${m.metalWeight()} after a finished mix play (was $start)")
        }

    @Test
    fun aLikeOutsideTheLiveMixTunesNothing() =
        runBlocking {
            val m = mix(FakeSource(hits) { radioOf(it) })
            val batch = m.mix.start()
            val pick = m.metalPick(batch)
            // The listener left the mix (Shell's Start radio, a Home mix): its songs are just songs now.
            m.observer.beginOccurrence()
            val before = m.mix.state.value
            m.mix.feedback(pick, liked = true)
            m.mix.feedback(batch.last { it.id != pick.id }, liked = false)
            val after = m.mix.state.value
            assertEquals(before.likes, after.likes)
            assertEquals(before.passes, after.passes)
            assertEquals(before.weights, after.weights)
            // Its own play's origin still counts (a like on the playing mix song).
            val origin = SurfaceTag.parse(metalTag)
            m.mix.feedback(pick, liked = true, origin = origin)
            assertEquals(before.likes + 1, m.mix.state.value.likes)
        }

    @Test
    fun aRefillAfterTheMixWasLeftIsNotTagged() =
        runBlocking {
            val m = mix(FakeSource(hits) { radioOf(it) })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val current = MutableStateFlow<SongItem?>(null)
                m.observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
                val batch = m.mix.start()
                current.value = batch.first()
                withTimeout(5_000) { while (m.observer.currentSong()?.id != batch.first().id) delay(10) }
                assertEquals(PlayPlace.INFINITY, m.observer.currentOrigin()?.place, "the mix's own song is tagged")
                m.observer.beginOccurrence()
                val more = m.mix.next(6, batch.mapTo(HashSet()) { it.id })
                assertTrue(more.isNotEmpty())
                current.value = more.first()
                withTimeout(5_000) { while (m.observer.currentSong()?.id != more.first().id) delay(10) }
                assertNull(m.observer.currentOrigin(), "a refill after the mix was left was tagged")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun resteerKeepsListenerAddsAndTheNextTwoMixSongs() =
        runBlocking {
            val m = mix(FakeSource(hits) { radioOf(it) })
            val batch = m.mix.start().map { it.id }
            assertTrue(batch.size >= 5)
            val (cur, m1, m2, m3, m4) = batch
            val queue = listOf(cur, "user1", m1, m2, m3, "user2", m4)
            assertEquals(setOf(4, 6), m.mix.resteerRemovals(queue, currentIndex = 0))
            // Once the listener has left the mix, re-steering removes nothing.
            m.observer.beginOccurrence()
            assertEquals(emptySet(), m.mix.resteerRemovals(queue, currentIndex = 0))
        }

    // ---- The blend shown against the dial's aim (first session) --------------------------------

    @Test
    fun aSparseProfileReportsZeroFavouritesAgainstItsTarget() =
        runBlocking {
            discoveryDialState.value = 0.5
            val m = mix(FakeSource(hits) { radioOf(it) })
            assertEquals(0, m.mix.favouriteCount())
            val batch = m.mix.start()
            assertTrue(batch.isNotEmpty())
            val blend = assertNotNull(m.mix.state.value.blend)
            assertEquals(0.475, blend.target, 1e-9)
            assertEquals(0, blend.familiar)
            assertEquals(0, blend.favouritesAvailable)
            assertEquals(batch.size, blend.total)
        }

    @Test
    fun likedSongsCountAsFavourites() =
        runBlocking {
            val liked = List(3) { song("fav$it") }
            // A liked song the song cache has never seen cannot be queued, so it is not counted.
            val votes = Votes(liked = liked.map { it.id }.toSet() + "fav-unknown")
            val m = mix(FakeSource(hits) { radioOf(it) }, votes, known = liked)
            assertEquals(3, m.mix.favouriteCount())
            discoveryDialState.value = 0.0
            m.mix.start()
            val blend = assertNotNull(m.mix.state.value.blend)
            assertEquals(3, blend.favouritesAvailable)
            assertEquals(3, blend.familiar, "every favourite is used when the dial asks for mostly favourites")
        }

    @Test
    fun countingFavouritesLeavesTheNextBatchUnchanged() =
        runBlocking {
            val liked = List(4) { song("fav$it") }

            suspend fun batch(count: Boolean): List<String> {
                infinityStateFlow.value = InfinityState()
                val m = mix(FakeSource(hits) { radioOf(it) }, Votes(liked = liked.map { it.id }.toSet()), known = liked)
                if (count) repeat(3) { m.mix.favouriteCount() }
                return m.mix.start().map { it.id }
            }
            assertEquals(batch(count = false), batch(count = true), "counting drew random numbers")
        }
}
