package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.LessLikeThis
import app.turtleplayer.taste.TasteEngine
import app.turtleplayer.taste.UserControls
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    ) : SongVotes {
        override val liked: Set<String> = emptySet()

        override fun isDisliked(id: String) = id in disliked
    }

    private class Mix(
        val mix: InfinityMix,
        val taste: TasteEngine,
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
        return Mix(mix, taste)
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
            val liked = m.mix.next(1, emptySet()).single()
            m.mix.feedback(liked, liked = true)
            m.taste.lessLikeThis(LessLikeThis.Track(TrackKey.youtube(liked.id), ArtistKey.of(liked.artists.first().name)))
            val seededBefore = source.seeds.size
            val played = mutableSetOf(liked.id)
            repeat(4) { played += m.mix.next(12, played).map { it.id } }
            assertTrue(source.seeds.size > seededBefore, "no refill happened")
            assertTrue(liked.id !in source.seeds.drop(seededBefore), "seeded ${source.seeds.drop(seededBefore)}")
        }
}
