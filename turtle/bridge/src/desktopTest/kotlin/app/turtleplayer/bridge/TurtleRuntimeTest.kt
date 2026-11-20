package app.turtleplayer.bridge

import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEventStore
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
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
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots our Koin module on a real (temporary) upstream database, the way the desktop app does, and
 * drives the hook entry points. Guards against a wiring mistake silently disabling Turtle features,
 * since every hook falls back to upstream behavior when our runtime is missing.
 */
class TurtleRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        libraryContentSearchOverride = null
        stopKoin()
    }

    private fun song(id: String, title: String, artist: String) =
        SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    @Test
    fun runtimeWiresLibrarySearchAndRadioRanking() = runBlocking {
        // Desktop builds its player before startKoin runs; the hook must still attach.
        val current = MutableStateFlow<SongItem?>(null)
        TurtleBridge.observePlayer(scope, current, MutableStateFlow(true), MutableSharedFlow<Long>())

        val dbFile = File.createTempFile("metrolist", ".db").apply { delete(); deleteOnExit() }
        // startKoin publishes the global context before its modules finish loading: Koin "exists"
        // while our definitions are still missing. Reproduce that window explicitly.
        val koin = startKoin { modules(module { single<MusicDatabase> { getDatabaseBuilder(dbFile).build() } }) }.koin
        delay(500)
        loadKoinModules(
            listOf(
                turtleModule,
                // Keep the test off the user's real Turtle database.
                module { single<PlayEventStore> { InMemoryPlayEventStore() } },
            ),
        )
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
}
