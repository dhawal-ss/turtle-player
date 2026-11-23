package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class YouTubeFreshReleasesTest {
    private val a = ArtistKey.of("Artist A")
    private val b = ArtistKey.of("Artist B")
    private val unknown = ArtistKey.of("Nobody")
    private val offline = ArtistKey.of("Flaky")
    private val artists = listOf(a, b, unknown, offline)

    private var now = Instant.parse("2026-06-15T12:00:00Z").toEpochMilliseconds()
    private val clock = Clock { now }

    private class FakeCatalog : ReleaseCatalog {
        val pages = mutableMapOf<String, List<ArtistRelease>>()
        var calls = 0

        override suspend fun followedArtists() = emptyList<ArtistKey>()

        override suspend fun channelId(artist: ArtistKey): String? {
            calls++
            return when (artist) {
                ArtistKey.of("Artist A") -> "UCa"
                ArtistKey.of("Artist B") -> "UCb"
                ArtistKey.of("Flaky") -> throw IOException("offline")
                else -> null
            }
        }

        override suspend fun releases(channelId: String, artist: ArtistKey): List<ArtistRelease> {
            calls++
            return pages.getValue(channelId)
        }

        override suspend fun tracks(releaseId: String): List<SongItem> {
            calls++
            val artist = if (releaseId.startsWith("a-")) ArtistTiny("UCa", "Artist A") else ArtistTiny("UCb", "Artist B")
            return (1..5).map { SongItem("$releaseId-$it", "Track $it", null, listOf(artist), null) }
        }
    }

    private fun radar(store: SqlitePlayEventStore, catalog: ReleaseCatalog, scope: CoroutineScope, updates: MutableList<Int>) =
        YouTubeFreshReleases(store, SongCache(), scope, catalog, clock) { updates += 1 }

    private suspend fun YouTubeFreshReleases.fetch() = freshReleases(artists, now - 28 * DAY_MS, 50)

    @Test
    fun firstScanRecordsTheCatalogueAndLaterReleasesBecomeFresh() = runTest {
        val file = File.createTempFile("turtle-radar", ".db").apply { delete(); deleteOnExit() }
        val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
        val catalog = FakeCatalog()
        catalog.pages["UCa"] = listOf(
            ArtistRelease("a-latest", 2026, latest = true),
            ArtistRelease("a-spring", 2026),
            ArtistRelease("a-old", 2023),
            ArtistRelease("a-undated", null),
        )
        catalog.pages["UCb"] = listOf(ArtistRelease("b-badged", 2026, isNew = true), ArtistRelease("b-older", 2025))
        val updates = mutableListOf<Int>()
        // Real time: the refresh's timeouts must not fire while the test waits on SQLite.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = radar(store, catalog, scope, updates)

        // Cold: answers at once from memory, then refreshes in the background and rebuilds Home.
        assertTrue(first.fetch().isEmpty())
        first.awaitRefresh()
        assertEquals(1, updates.size)
        val initial = first.fetch()
        // First sight: only the latest-release card and YouTube's "new" badge count, at most 3 tracks each.
        assertEquals(setOf("a-latest", "b-badged"), initial.map { it.youtubeId!!.substringBeforeLast('-') }.toSet())
        assertEquals(6, initial.size)
        assertTrue(initial.all { it.releasedAtMs == now })
        assertEquals(setOf(a, b), initial.map { it.artistKey }.toSet())

        // Within the cache window nothing is fetched again, failures included.
        val calls = catalog.calls
        now += 30 * 60_000L
        first.fetch()
        first.awaitRefresh()
        assertEquals(calls, catalog.calls)

        // After a restart, 13 hours later: a release that appeared since is fresh; the catalogue seen
        // at first launch stays old.
        now += 13 * HOUR_MS
        val seenAt = now
        catalog.pages["UCa"] = listOf(ArtistRelease("a-new", 2026)) + catalog.pages.getValue("UCa")
        val restarted = radar(store, catalog, scope, updates)
        restarted.fetch()
        restarted.awaitRefresh()
        val later = restarted.fetch()
        val releases = later.map { it.youtubeId!!.substringBeforeLast('-') }
        assertEquals(setOf("a-new", "a-latest", "b-badged"), releases.toSet())
        assertEquals("a-new", releases.first(), "newest first")
        assertEquals(seenAt, later.first().releasedAtMs)

        // Releases leave the shelf once the window has passed.
        now += 29 * DAY_MS
        assertTrue(restarted.fetch().isEmpty())
        restarted.awaitRefresh()
        scope.cancel()
        store.close()
    }

    /** Every artist is on YouTube; only [releasing] has a release, which YouTube badges as new. */
    private class ManyArtistsCatalog(val releasing: ArtistKey) : ReleaseCatalog {
        /** Artists whose page was read. */
        val scanned: MutableList<ArtistKey> = Collections.synchronizedList(mutableListOf())

        override suspend fun followedArtists() = emptyList<ArtistKey>()

        override suspend fun channelId(artist: ArtistKey) = "UC-${artist.value}"

        override suspend fun releases(channelId: String, artist: ArtistKey): List<ArtistRelease> {
            scanned += artist
            return if (artist == releasing) listOf(ArtistRelease("new-single", 2026, isNew = true)) else emptyList()
        }

        override suspend fun tracks(releaseId: String) =
            listOf(SongItem("$releaseId-1", "Track 1", null, listOf(ArtistTiny("UC-last", "Artist 20")), null))
    }

    @Test
    fun refreshesRotateThroughEveryRequestedArtistAcrossRestarts() = runTest {
        val file = File.createTempFile("turtle-radar", ".db").apply { delete(); deleteOnExit() }
        val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
        val wanted = (1..20).map { ArtistKey.of("Artist $it") }
        val catalog = ManyArtistsCatalog(releasing = wanted.last())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val since = now - 28 * DAY_MS

        // Each refresh stays within the per-refresh budget, so the last artist waits its turn.
        val first = radar(store, catalog, scope, mutableListOf())
        first.freshReleases(wanted, since, 50)
        first.awaitRefresh()
        assertEquals(YouTubeFreshReleases.MAX_ARTISTS, catalog.scanned.size)
        assertFalse(wanted.last() in catalog.scanned)

        // After a restart the artists not checked yet come next, not the same top artists again.
        catalog.scanned.clear()
        val restarted = radar(store, catalog, scope, mutableListOf())
        restarted.freshReleases(wanted, since, 50)
        restarted.awaitRefresh()
        assertTrue(catalog.scanned.size <= YouTubeFreshReleases.MAX_ARTISTS)
        assertTrue(wanted.takeLast(5).all { it in catalog.scanned }, "unchecked artists first: ${catalog.scanned}")
        assertEquals(listOf("new-single-1"), restarted.freshReleases(wanted, since, 50).map { it.youtubeId })

        // Within one run the next refresh covers whoever is left.
        restarted.awaitRefresh()
        assertEquals(wanted.toSet(), catalog.scanned.toSet())
        scope.cancel()
        store.close()
    }
}
