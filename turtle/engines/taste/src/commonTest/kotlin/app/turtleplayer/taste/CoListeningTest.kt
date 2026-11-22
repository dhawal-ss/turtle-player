package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The co-listening graph learned from radio pages, with the synthetic world's genres as ground
 * truth: YouTube's radio for a song mostly stays in its genre, with a little noise.
 */
class CoListeningTest {
    private val world = SyntheticWorld()

    /** Playable copies (the graph only remembers songs with a YouTube id). */
    private fun TrackMeta.playable() = copy(youtubeId = key.value)

    /** A radio page for [seed]: songs of its genre, shuffled, plus two from a random other genre. */
    private fun radioPage(seed: TrackMeta, random: Random): List<TrackMeta> {
        val g = world.trackGenre.getValue(seed.key)
        val own = world.tracksOfGenre(g).filter { it.key != seed.key }.shuffled(random).take(20)
        val other = world.tracksOfGenre((g + 1 + random.nextInt(world.genres - 1)) % world.genres).shuffled(random).take(2)
        return (own + other).shuffled(random).map { it.playable() }
    }

    private suspend fun learnedGraph(pagesPerArtist: Int = 2): CoListeningGraph {
        val graph = CoListeningGraph()
        val random = Random(3)
        for (a in world.artists) repeat(pagesPerArtist) { graph.observeRadio(world.tracksOf(a)[it].playable(), radioPage(world.tracksOf(a)[it], random)) }
        return graph
    }

    @Test
    fun artistsOfOneGenreEndUpCloserThanArtistsOfOthers() = runTest {
        val store = CoListeningStore(learnedGraph()).also { it.rebuild() }
        var within = 0.0
        var across = 0.0
        var nWithin = 0
        var nAcross = 0
        for (a in world.artists) for (b in world.artists) {
            if (a == b) continue
            val s = store.artistVector(a)!!.cosine(store.artistVector(b)!!).toDouble()
            if (world.artistGenre[a] == world.artistGenre[b]) { within += s; nWithin++ } else { across += s; nAcross++ }
        }
        assertTrue(within / nWithin > across / nAcross + 0.3, "within ${within / nWithin}, across ${across / nAcross}")

        // Nearest artists are mostly the same genre.
        val seed = world.artists[0]
        val near = store.nearestArtists(store.artistVector(seed)!!, 6) { it == seed }
        assertTrue(near.count { world.artistGenre[it.key] == world.artistGenre[seed] } >= 5, "near: $near")
        // Nearest songs too, and they are songs the graph saw.
        val songs = store.nearestTracks(store.artistVector(seed)!!, 10)
        assertEquals(10, songs.size)
        assertTrue(songs.count { world.trackGenre[it.key] == world.artistGenre[seed] } >= 8)
    }

    @Test
    fun songsFinishedBackToBackLinkTheirArtistsButSkipsDoNot() = runTest {
        val graph = CoListeningGraph()
        val (a, b, c) = listOf(world.artists[0], world.artists[20], world.artists[40]).map { world.tracksOf(it).first().playable() }
        graph.observePlay(play(a, 0).copy(sessionId = "s"))
        graph.observePlay(play(b, 1).copy(sessionId = "s"))
        graph.observePlay(play(c, 2, EndReason.SKIPPED, msPlayed = 5_000).copy(sessionId = "s"))
        val edges = graph.export().edges.map { setOf(it.a, it.b) }
        assertEquals(listOf(setOf(a.artistKey, b.artistKey)), edges)
    }

    @Test
    fun savedGraphRestoresTheSameVectors() = runTest {
        val graph = learnedGraph(pagesPerArtist = 1)
        val store = CoListeningStore(graph).also { it.rebuild() }
        val restored = CoListeningGraph().also { it.load(graph.export()) }
        val restoredStore = CoListeningStore(restored).also { it.rebuild() }
        // Equal up to float summation order.
        for (a in world.artists) assertTrue(store.artistVector(a)!!.cosine(restoredStore.artistVector(a)!!) > 0.9999f)
        assertEquals(store.trackCount, restoredStore.trackCount)
    }

    @Test
    fun graphStaysWithinItsBounds() = runTest {
        val graph = CoListeningGraph(maxArtists = 20, maxNeighbours = 5, maxTracksPerArtist = 2)
        val random = Random(1)
        for (a in world.artists) graph.observeRadio(world.tracksOf(a).first().playable(), radioPage(world.tracksOf(a).first(), random))
        val data = graph.export()
        val degree = HashMap<ArtistKey, Int>()
        for (e in data.edges) { degree.merge(e.a, 1, Int::plus); degree.merge(e.b, 1, Int::plus) }
        assertTrue(degree.size <= 20, "artists ${degree.size}")
        assertTrue(degree.values.all { it <= 5 })
        assertTrue(data.tracks.groupBy { it.artistKey }.values.all { it.size <= 2 })
    }

    @Test
    fun learnedNeighboursPowerTheEnginesClustersAndListenersAlsoPlay() = runTest {
        val user = world.user(0)
        val history = world.history(user)
        val clock = MutableClock(history.last().startedAtMs + 30 * MINUTE_MS)
        val graph = learnedGraph()
        val store = CoListeningStore(graph).also { it.rebuild() }
        val engine = TasteEngine(
            events = InMemoryPlayEventStore().also { it.appendAll(history) },
            store = store,
            catalog = CatalogWithObserved(InMemoryTrackCatalog(emptyList()), graph),
            clock = clock,
            random = Random(7),
        )
        engine.embeddingsChanged()
        val profile = engine.profile()
        assertTrue(profile.clusters.size >= 2, "clusters ${profile.clusters.size}")
        // The two heaviest clusters are the listener's two genres, one each. (Smaller ones hold the odd
        // songs they also liked from other genres, which is right.)
        val main = profile.clusters.sortedByDescending { it.weight }.take(2).map { c -> c.topArtists.map { world.artistGenre.getValue(it) }.toSet() }
        assertTrue(main.all { it.size == 1 }, "main clusters mix genres: $main")
        assertEquals(user.likedGenres, main.flatten().toSet())

        val mixes = engine.homeShelves().filter { it.kind == ShelfKind.DAILY_MIX }
        assertTrue(mixes.isNotEmpty())
        val items = mixes.flatMap { it.items }
        val inTaste = items.count { world.trackGenre.getValue(it.key) in user.likedGenres }
        assertTrue(inTaste >= items.size * 0.85, "in taste $inTaste/${items.size}")
        // Mixes reach beyond what they have heard, through neighbour artists the graph learned.
        assertTrue(items.any { !profile.hasHeard(it.key) })
    }
}
