package app.turtleplayer.search.golden

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.search.AlbumResult
import app.turtleplayer.search.ArtistResult
import app.turtleplayer.search.FakeOnlineProvider
import app.turtleplayer.search.HistoryAffinityProvider
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import app.turtleplayer.search.MixResult
import app.turtleplayer.search.ResultItem
import app.turtleplayer.search.SearchEngine
import app.turtleplayer.search.SearchResults
import app.turtleplayer.search.TrackResult
import app.turtleplayer.search.fmt
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs every golden query through the full engine (local + lyrics + fake online + history
 * affinity) and checks top-1 and top-3 hit rates. Failures are printed with the actual top 3 so a
 * regression is easy to diagnose.
 */
class GoldenQuerySetTest {
    private val now = 1_790_000_000_000L
    private val clock = Clock { now }

    private suspend fun buildEngine(scope: TestScope): SearchEngine {
        val library = LocalLibrary()
        val events = InMemoryPlayEventStore()
        var t = now - 30 * DAY_MS
        for (row in GoldenCatalog.library) {
            val sources = buildSet {
                if (row.inLibrary) add(LocalSource.LIBRARY)
                if (row.plays > 0) add(LocalSource.HISTORY)
            }
            library.upsert(row.meta, sources)
            GoldenCatalog.lyrics[row.meta.youtubeId]?.let { library.putLyrics(row.meta, it) }
            repeat(row.plays) { i ->
                t += 3 * 60_000L
                events.append(
                    PlayEvent(
                        track = row.meta, sessionId = "s${i % 3}", position = i, surface = Surface.LIBRARY,
                        startReason = StartReason.TAPPED, endReason = EndReason.COMPLETED, startedAtMs = t,
                        msPlayed = 200_000, durationMs = 200_000,
                    ),
                )
            }
        }
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return SearchEngine(
            library = library,
            online = FakeOnlineProvider(GoldenCatalog.online, delayMs = 150),
            affinity = HistoryAffinityProvider(events, clock),
            cpuDispatcher = dispatcher,
        )
    }

    private fun matches(item: ResultItem, expect: Expect): Boolean = when (expect) {
        is Expect.Track -> item is TrackResult && (item.track.youtubeId == expect.id || item.alternates.any { it.id == expect.id })
        is Expect.Artist -> item is ArtistResult && item.artist == ArtistKey.of(expect.name)
        is Expect.Album -> item is AlbumResult && item.key == "${ArtistKey.of(expect.artist).value}|${TextNormalizer.key(expect.title)}"
        Expect.Mix -> item is MixResult
    }

    private fun describe(item: ResultItem): String = when (item) {
        is TrackResult -> "track ${item.track.youtubeId} '${item.track.title}' ${item.score.fmt()}"
        is ArtistResult -> "artist ${item.name} ${item.score.fmt()}"
        is AlbumResult -> "album ${item.title} ${item.score.fmt()}"
        is MixResult -> "mix"
    }

    @Test
    fun goldenQuerySetMeetsHitRateThresholds() = runTest {
        val engine = buildEngine(this)
        var top1 = 0
        var top3 = 0
        val failures = ArrayList<String>()
        val perCategory = HashMap<Category, IntArray>() // [count, top1, top3]
        for (g in GoldenQueries.all) {
            val results: SearchResults = engine.search(g.query).last()
            val ranked = results.ranked
            val hit1 = ranked.firstOrNull()?.let { matches(it, g.expect) } == true
            val hit3 = ranked.take(3).any { matches(it, g.expect) }
            if (hit1) top1++
            if (hit3) top3++
            perCategory.getOrPut(g.category) { IntArray(3) }.let {
                it[0]++
                if (hit1) it[1]++
                if (hit3) it[2]++
            }
            if (!hit1) {
                failures += "${if (hit3) "top3 " else "MISS "}'${g.query}' expected ${g.expect} " +
                    "[intent=${results.query.intent} ${results.query.confidence.fmt(2)}] got: " +
                    ranked.take(3).joinToString(" | ") { describe(it) }
            }
        }
        val n = GoldenQueries.all.size
        val rate1 = top1.toDouble() / n
        val rate3 = top3.toDouble() / n
        println("Golden set: $n queries, top-1 ${rate1.fmt()}, top-3 ${rate3.fmt()}")
        perCategory.entries.sortedBy { it.key }.forEach { (c, v) -> println("  $c: ${v[1]}/${v[0]} top-1, ${v[2]}/${v[0]} top-3") }
        failures.forEach { println("  $it") }
        assertTrue(rate3 >= TOP3_THRESHOLD, "top-3 hit rate $rate3 < $TOP3_THRESHOLD")
        assertTrue(rate1 >= TOP1_THRESHOLD, "top-1 hit rate $rate1 < $TOP1_THRESHOLD")
    }

    private companion object {
        const val TOP3_THRESHOLD = 0.98
        const val TOP1_THRESHOLD = 0.96
    }
}
