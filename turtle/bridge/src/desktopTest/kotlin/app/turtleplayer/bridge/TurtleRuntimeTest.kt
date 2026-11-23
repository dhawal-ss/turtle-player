package app.turtleplayer.bridge

import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.Served
import app.turtleplayer.taste.SurfaceTag
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.api.searchSummaryOverride
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.viewmodels.libraryContentSearchOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots our Koin module on a real (temporary) upstream database, the way the desktop app does, and
 * drives the hook entry points. Guards against a wiring mistake silently disabling Turtle features,
 * since every hook falls back to upstream behavior when our runtime is missing.
 */
class TurtleRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("turtle-runtime-test").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        // The runtime's own work outlives Koin otherwise, and would keep running into the next test.
        GlobalContext
            .getOrNull()
            ?.getOrNull<TurtleScope>()
            ?.scope
            ?.cancel()
        libraryContentSearchOverride = null
        searchSummaryOverride = null
        stopKoin()
        dir.deleteRecursively()
    }

    private fun song(id: String, title: String, artist: String) =
        SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    /**
     * Our database in a temporary file, never the listener's real one: the runtime, co-listening and
     * the session checkpoint open [SqlitePlayEventStore] directly, so overriding [PlayEventStore] is not enough.
     */
    private suspend fun turtleDb(): SqlitePlayEventStore =
        SqlitePlayEventStore(JdbcSQLiteDriver(), File(dir, "turtle.db").absolutePath).also {
            // TurtleDefaults' key, marked applied so a fresh database does not write the real desktop settings file.
            it.setPref("default_keep_playing_on_task_clear_v1", "applied")
        }

    private fun upstreamDb(): Module = module { single<MusicDatabase> { getDatabaseBuilder(File(dir, "metrolist.db")).build() } }

    private fun testStore(db: SqlitePlayEventStore): Module = module { single<SqlitePlayEventStore> { db } }

    /** The runtime as Koin builds it, with the radio ranking budget [rankTimeoutMs]. */
    private fun runtimeWith(rankTimeoutMs: Long): Module =
        module {
            single {
                TurtleRuntime(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    rankTimeoutMs = rankTimeoutMs,
                )
            }
        }

    /** A generous budget, so a slow cold start cannot turn a ranking test into a timeout test. */
    private suspend fun bootTurtle(rankTimeoutMs: Long = 30_000): Koin {
        val db = turtleDb()
        return startKoin { modules(listOf(upstreamDb(), turtleModule, testStore(db), runtimeWith(rankTimeoutMs))) }.koin
    }

    private suspend fun awaitPlaying(
        koin: Koin,
        id: String,
    ) = withTimeout(30_000) { while (koin.get<PlayerObserver>().currentSong()?.id != id) delay(20) }

    @Test
    fun runtimeWiresLibrarySearchAndRadioRanking() = runBlocking {
        // Desktop builds its player before startKoin runs; the hook must still attach.
        val current = MutableStateFlow<SongItem?>(null)
        TurtleBridge.observePlayer(scope, current, MutableStateFlow(true), MutableSharedFlow<Long>())

        val db = turtleDb()
        // startKoin publishes the global context before its modules finish loading: Koin "exists"
        // while our definitions are still missing. Reproduce that window explicitly.
        val koin = startKoin { modules(upstreamDb()) }.koin
        delay(500)
        loadKoinModules(listOf(turtleModule, testStore(db)))
        val dao = koin.get<MusicDatabase>().dao()
        val added = LocalDateTime(2026, 11, 1, 12, 0)
        dao.insert(SongEntity(id = "v1", title = "Bohemian Rhapsody", customArtist = "Queen", duration = 354, inLibrary = added))
        dao.insert(SongEntity(id = "v2", title = "Under Pressure", customArtist = "Queen", duration = 248, inLibrary = added))
        dao.insert(SongEntity(id = "v3", title = "Bohemian Like You", customArtist = "The Dandy Warhols", duration = 211))

        current.value = song("v1", "Bohemian Rhapsody", "Queen")

        // Library search: typo-tolerant, library songs only (v3 was only played, never added).
        val results = withTimeout(30_000) {
            while (libraryContentSearchOverride == null) delay(50)
            val search = assertNotNull(libraryContentSearchOverride)
            var r: List<SongItem>? = null
            while (r == null) {
                r = search("bohemain", 0)
                if (r == null) delay(100)
            }
            r
        }
        assertEquals(listOf("v1"), results.map { it.id })

        // The player hook attached once Koin came up: a played song lands in the event log.
        val store = koin.get<PlayEventStore>()
        current.value = song("v2", "Under Pressure", "Queen")
        withTimeout(10_000) { while (store.count() == 0L) delay(50) }
        assertEquals("yt:v1", store.since(0).first().trackKey.value)

        // Radio: same songs back, reordered at most, none invented or lost.
        val page = RadioQueuePage(
            songs = listOf(song("r1", "Song One", "Artist A"), song("r2", "Song Two", "Artist B"), song("r3", "Song Three", "Artist C")),
            chips = emptyList(),
            continuation = "next",
        )
        val ranked = TurtleBridge.rankRadio(page)
        assertEquals(page.songs.map { it.id }.toSet(), ranked.songs.map { it.id }.toSet())
        assertEquals("next", ranked.continuation)
        assertTrue(ranked.songs.size == 3)
    }

    @Test
    fun stationStartedOnAnotherSongIsSeededByThatSong() =
        runBlocking {
            val current = MutableStateFlow<SongItem?>(null)
            TurtleBridge.observePlayer(scope, current, MutableStateFlow(true), MutableSharedFlow<Long>())
            val koin = bootTurtle()
            // A still plays while the station on B loads its first page; B is a song the app has shown before.
            val a = song("a1", "Playing Before", "Old Artist")
            val b = song("b1", "Station Song", "Station Artist")
            current.value = a
            awaitPlaying(koin, a.id)
            koin.get<SongCache>().putAll(listOf(b))

            val page =
                RadioQueuePage(
                    songs = listOf(song("x1", "One", "Artist X"), song("y1", "Two", "Artist Y"), song("z1", "Three", "Artist Z")),
                    chips = emptyList(),
                    continuation = "next",
                )
            val ranked = TurtleBridge.rankRadio(page, seedId = b.id)

            assertEquals(page.songs.map { it.id }.toSet(), ranked.songs.map { it.id }.toSet())
            val edges = koin.get<CoListeningGraph>().export().edges
            val stationArtist = b.toTrackMeta().artistKey
            val playingArtist = a.toTrackMeta().artistKey
            assertTrue(edges.isNotEmpty() && edges.all { it.a == stationArtist || it.b == stationArtist }, "edges: $edges")
            assertFalse(edges.any { it.a == playingArtist || it.b == playingArtist }, "edges: $edges")
        }

    @Test
    fun unknownStationRanksWithoutDroppingTheFirstCandidate() =
        runBlocking {
            // A cold observer: attached, nothing playing.
            val current = MutableStateFlow<SongItem?>(null)
            TurtleBridge.observePlayer(scope, current, MutableStateFlow(true), MutableSharedFlow<Long>())
            val koin = bootTurtle()

            val first = song("x1", "One", "Artist X")
            val page =
                RadioQueuePage(
                    songs = listOf(first, song("y1", "Two", "Artist Y"), song("z1", "Three", "Artist Z")),
                    chips = emptyList(),
                    continuation = "next",
                )
            val ranked = TurtleBridge.rankRadio(page, seedId = null)

            assertEquals(page.songs.map { it.id }.toSet(), ranked.songs.map { it.id }.toSet())
            // An unknown seed teaches the co-listening graph nothing.
            val learned = koin.get<CoListeningGraph>().export().edges
            assertTrue(learned.isEmpty())

            // The first candidate came back from the ranked path, not a fallback: its play says so.
            current.value = first
            awaitPlaying(koin, first.id)
            current.value = song("w1", "Next", "Artist W")
            val store = koin.get<PlayEventStore>()
            val played =
                withTimeout(30_000) {
                    var event = store.since(0).firstOrNull { it.trackKey == first.toTrackMeta().key }
                    while (event == null) {
                        delay(20)
                        event = store.since(0).firstOrNull { it.trackKey == first.toTrackMeta().key }
                    }
                    event
                }
            assertEquals(Served.RANKED, SurfaceTag.parse(played.surfaceDetail)?.served)
        }

    @Test
    fun unrankedPagesStillLeaveOutNotInterestedSongs() =
        runBlocking {
            // A zero budget always takes the timeout path.
            val koin = bootTurtle(rankTimeoutMs = 0)
            val hidden = song("h1", "Hidden", "Artist H")
            TurtleBridge.notInterested(NotInterested.Song(hidden))
            assertTrue(hidden.toTrackMeta().key in koin.get<TasteEngine>().controls.lessLikeThisTracks)
            val chips = listOf(RadioQueueChip(id = "c1", title = "Chill", playlistId = "RDc1", params = null, isSelected = false))

            val single = TurtleBridge.rankRadio(RadioQueuePage(songs = listOf(hidden), chips = chips, continuation = "next"), seedId = "s1")
            assertEquals(emptyList(), single.songs)
            assertEquals("next", single.continuation)
            assertEquals(chips, single.chips)

            val page =
                RadioQueuePage(
                    songs = listOf(hidden, song("x1", "One", "Artist X"), song("y1", "Two", "Artist Y")),
                    chips = chips,
                    continuation = "more",
                )
            val timedOut = TurtleBridge.rankRadio(page, seedId = "s1")
            assertEquals(listOf("x1", "y1"), timedOut.songs.map { it.id })
            assertEquals("more", timedOut.continuation)
            assertEquals(chips, timedOut.chips)
        }
}
