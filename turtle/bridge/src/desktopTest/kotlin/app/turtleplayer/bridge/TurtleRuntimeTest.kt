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
        val dbFile = File.createTempFile("metrolist", ".db").apply { delete(); deleteOnExit() }
        val koin = startKoin {
            modules(
                module { single<MusicDatabase> { getDatabaseBuilder(dbFile).build() } },
                turtleModule,
                // Keep the test off the user's real Turtle database.
                module { single<PlayEventStore> { InMemoryPlayEventStore() } },
            )
        }.koin
        val dao = koin.get<MusicDatabase>().dao()
        val added = LocalDateTime(2026, 11, 1, 12, 0)
        dao.insert(SongEntity(id = "v1", title = "Bohemian Rhapsody", customArtist = "Queen", duration = 354, inLibrary = added))
        dao.insert(SongEntity(id = "v2", title = "Under Pressure", customArtist = "Queen", duration = 248, inLibrary = added))
        dao.insert(SongEntity(id = "v3", title = "Bohemian Like You", customArtist = "The Dandy Warhols", duration = 211))

        val current = MutableStateFlow<SongItem?>(null)
        TurtleBridge.observePlayer(scope, current, MutableStateFlow(true), MutableSharedFlow<Long>())
        current.value = song("v1", "Bohemian Rhapsody", "Queen")

        // Library search: typo-tolerant, library songs only (v3 was only played, never added).
        val search = assertNotNull(libraryContentSearchOverride, "runtime did not install the library search hook")
        val results = withTimeout(30_000) {
            var r: List<SongItem>? = null
            while (r == null) {
                r = search("bohemain", 0)
                if (r == null) delay(100)
            }
            r
        }
        assertEquals(listOf("v1"), results.map { it.id })

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
