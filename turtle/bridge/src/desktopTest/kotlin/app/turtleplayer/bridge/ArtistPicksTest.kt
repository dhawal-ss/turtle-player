package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.taste.CatalogWithObserved
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** First session: artist picks seed Made for you, persist, and never touch listening history. */
class ArtistPicksTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file =
        File.createTempFile("picks", ".db").apply {
            delete()
            deleteOnExit()
        }
    private val db = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun song(
        id: String,
        artist: String,
    ) = SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    private val alpha = ArtistItem(id = "UCalpha", title = "Alpha", thumbnail = "https://example.test/a.jpg")
    private val beta = ArtistItem(id = "UCbeta", title = "Beta", thumbnail = null)

    /** YouTube as the picks see it: each artist has three top songs; every radio brings two neighbour artists. */
    private class FakeSource : ArtistPicksSource {
        val searched = ArrayList<String>()

        override suspend fun searchArtists(query: String): List<ArtistItem> {
            searched += query
            return listOf(ArtistItem(id = "UC$query", title = query, thumbnail = null))
        }

        override suspend fun topSongs(artist: ArtistItem) =
            List(3) { SongItem(id = "${artist.id}-top$it", title = "Top $it", thumbnail = null, artists = listOf(ArtistTiny(null, artist.title)), album = null, duration = 200) }

        override suspend fun radio(videoId: String) =
            listOf("Near One", "Near Two").mapIndexed { i, name ->
                SongItem(id = "$videoId-r$i", title = "Radio $i", thumbnail = null, artists = listOf(ArtistTiny(null, name)), album = null, duration = 200)
            }
    }

    private inner class World(
        val learning: Boolean = true,
        val restored: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) {
        val events = InMemoryPlayEventStore()
        val graph = CoListeningGraph()
        val vectors = CoListeningStore(graph)
        val cache = SongCache()
        val taste = TasteEngine(events = events, store = vectors, catalog = CatalogWithObserved(InMemoryTrackCatalog(emptyList()), graph))
        val sync = CoListeningSync(graph, vectors, db, events, cache, learning = { learning }).also { it.start(scope, taste) }
        var saves = 0
        var homeRefreshes = 0
        val source = FakeSource()

        fun picks() =
            TurtleArtistPicks(
                taste, db, cache, sync,
                awaitRestored = { restored.await() },
                saveTaste = { saves++ },
                refreshHome = { homeRefreshes++ },
                source = source,
                learningPaused = { !learning },
            )
    }

    @Test
    fun aFreshProfileIsOfferedThePicks() =
        runBlocking {
            val picks = World().picks()
            picks.refresh()
            val state = assertNotNull(picks.state)
            assertTrue(state.sparse)
            assertTrue(state.offer)
            assertFalse(state.learningPaused)
        }

    @Test
    fun saveSeedsCatalogAndOnboards() =
        runBlocking {
            val w = World()
            val picks = w.picks()
            picks.save(listOf(alpha, beta, alpha))
            assertEquals(listOf(ArtistKey.of("Alpha"), ArtistKey.of("Beta")), w.taste.controls.onboardingArtists)
            val catalog = CatalogWithObserved(InMemoryTrackCatalog(emptyList()), w.graph)
            val known = catalog.tracksByArtist(ArtistKey.of("Alpha"), 10).mapNotNull { it.youtubeId }.toSet()
            assertEquals(setOf("UCalpha-top0", "UCalpha-top1", "UCalpha-top2"), known)
            assertNotNull(w.cache.get("UCalpha-top1"), "picked songs are playable")
            // The radio linked the pick to its neighbours, and the vectors were rebuilt at once.
            assertNotNull(w.vectors.artistVector(ArtistKey.of("Alpha")))
            assertEquals(1, w.saves, "the taste state was saved")
            assertEquals(1, w.homeRefreshes)
            val state = assertNotNull(picks.state)
            assertEquals(listOf("UCalpha", "UCbeta"), state.picked.map { it.id })
            assertFalse(state.offer)
            // Kept across launches, artwork included.
            val again = w.picks().apply { refresh() }
            assertEquals(listOf(PickedArtist("UCalpha", "Alpha", "https://example.test/a.jpg"), PickedArtist("UCbeta", "Beta", null)), again.state?.picked)
        }

    @Test
    fun saveIsCappedAtFiveArtists() =
        runBlocking {
            val w = World()
            val many = List(7) { ArtistItem(id = "UC$it", title = "Artist $it", thumbnail = null) }
            w.picks().save(many)
            assertEquals(TurtleArtistPicks.MAX_PICKS, w.taste.controls.onboardingArtists.size)
        }

    @Test
    fun saveWaitsForRestore() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val w = World(restored = gate)
            val saving = scope.async { w.picks().save(listOf(alpha)) }
            delay(200)
            assertEquals(emptyList(), w.taste.controls.onboardingArtists, "picks applied before the saved state was read")
            assertEquals(0, w.saves)
            gate.complete(Unit)
            saving.await()
            assertEquals(listOf(ArtistKey.of("Alpha")), w.taste.controls.onboardingArtists)
        }

    @Test
    fun skipPersists() =
        runBlocking {
            val w = World()
            w.picks().skip()
            val later = w.picks().apply { refresh() }
            val state = assertNotNull(later.state)
            assertTrue(state.dismissed)
            assertTrue(state.sparse)
            assertFalse(state.offer, "a skipped card does not come back")
        }

    @Test
    fun clearRemovesOnboardingButKeepsEvents() =
        runBlocking {
            val w = World()
            w.events.append(
                app.turtleplayer.events.PlayEvent(
                    track = song("p1", "Alpha").toTrackMeta(),
                    sessionId = "s",
                    position = 0,
                    surface = app.turtleplayer.events.Surface.AUTOPLAY,
                    startReason = app.turtleplayer.events.StartReason.AUTOPLAY,
                    endReason = app.turtleplayer.events.EndReason.COMPLETED,
                    startedAtMs = 1_000L,
                    msPlayed = 200_000,
                    durationMs = 200_000,
                ),
            )
            val before = w.events.count()
            val picks = w.picks()
            picks.save(listOf(alpha))
            assertEquals(before, w.events.count(), "picking writes no plays")
            picks.clear()
            assertEquals(emptyList(), w.taste.controls.onboardingArtists)
            assertEquals(before, w.events.count())
            assertEquals(emptyList(), w.picks().apply { refresh() }.state?.picked)
        }

    @Test
    fun pausedHistoryStillOnboardsButLearnsNothing() =
        runBlocking {
            val w = World(learning = false)
            val picks = w.picks()
            picks.save(listOf(alpha))
            assertEquals(listOf(ArtistKey.of("Alpha")), w.taste.controls.onboardingArtists)
            assertEquals(0L, w.graph.version(), "radio pages taught the graph while history was paused")
            assertTrue(assertNotNull(picks.state).learningPaused)
        }

    @Test
    fun searchSkipsBlankQueries() =
        runBlocking {
            val w = World()
            val picks = w.picks()
            assertEquals(emptyList(), picks.search("  "))
            assertEquals(listOf("UCDaft Punk"), picks.search(" Daft Punk ").map { it.id })
            assertEquals(listOf("Daft Punk"), w.source.searched)
        }
}
