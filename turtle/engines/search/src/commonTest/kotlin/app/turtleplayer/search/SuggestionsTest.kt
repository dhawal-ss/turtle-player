package app.turtleplayer.search

import app.turtleplayer.core.Clock
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionsTest {
    private var now = 1_800_000_000_000L
    private val clock = Clock { now }

    private val yellow = track("yellow", "Coldplay", "Yellow", "Parachutes")
    private val creep = track("creep", "Radiohead", "Creep")
    private val skipped = track("skip", "Somebody", "Skipped Song")

    private suspend fun suggester(
        history: SearchHistory = InMemorySearchHistory(clock),
        online: OnlineSearchProvider = OnlineSearchProvider.None,
    ): SearchSuggester {
        val library = LocalLibrary().apply { upsertAll(listOf(yellow, creep, skipped)) }
        val events = InMemoryPlayEventStore()
        fun play(t: app.turtleplayer.core.TrackMeta, at: Long, ms: Long, end: EndReason) = PlayEvent(
            track = t, sessionId = "s", position = 0, surface = Surface.LIBRARY, startReason = StartReason.TAPPED,
            endReason = end, startedAtMs = at, msPlayed = ms, durationMs = 200_000,
        )
        events.append(play(creep, now - 3_600_000, 200_000, EndReason.COMPLETED))
        events.append(play(yellow, now - 1_800_000, 200_000, EndReason.COMPLETED))
        events.append(play(skipped, now - 60_000, 5_000, EndReason.SKIPPED))
        return SearchSuggester(library, history, online, events, clock)
    }

    @Test
    fun emptyBoxShowsRecentSearchesThenRecentlyPlayed() = runTest {
        val history = InMemorySearchHistory(clock)
        history.record("radiohead")
        now += 1000
        history.record("coldplay yellow")
        val rows = suggester(history).suggest("").toList().single()
        assertEquals(
            listOf(SuggestionKind.RECENT_SEARCH to "coldplay yellow", SuggestionKind.RECENT_SEARCH to "radiohead",
                SuggestionKind.RECENTLY_PLAYED to "Yellow", SuggestionKind.RECENTLY_PLAYED to "Creep"),
            rows.map { it.kind to it.text },
            "most recent first; quick skips are not 'recently played'",
        )
    }

    @Test
    fun personalSuggestionsComeBeforeOnlineOnes() = runTest {
        val history = InMemorySearchHistory(clock).apply { record("yellow live") }
        val online = FakeOnlineProvider(emptyList(), delayMs = 100, completions = mapOf("yel" to listOf("yellow", "yellowcard", "yellow submarine")))
        val emissions = suggester(history, online).suggest("yel").toList()
        assertEquals(2, emissions.size)
        val personal = emissions.first()
        assertEquals(SuggestionKind.RECENT_SEARCH, personal.first().kind)
        assertTrue(personal.any { it.kind == SuggestionKind.LIBRARY_TRACK && it.track == yellow })
        val all = emissions.last()
        assertEquals(personal, all.take(personal.size))
        val onlineTexts = all.drop(personal.size).map { it.text }
        assertEquals(listOf("yellowcard", "yellow submarine"), onlineTexts, "online duplicates of personal rows are dropped")
        assertTrue(all.drop(personal.size).all { it.kind == SuggestionKind.ONLINE })
    }

    @Test
    fun recentSearchesMatchWithTyposAndPrefixes() = runTest {
        val history = InMemorySearchHistory(clock).apply {
            record("bohemian rhapsody")
            record("radiohead creep")
        }
        val rows = suggester(history).suggest("bohemain rhap").toList().first()
        assertEquals("bohemian rhapsody", rows.first { it.kind == SuggestionKind.RECENT_SEARCH }.text)
        assertTrue(rows.none { it.text == "radiohead creep" })
    }

    @Test
    fun historyDeduplicatesAndPersists() = runTest {
        val store = object : SearchHistoryPersistence {
            var saved: List<SearchHistoryEntry> = listOf(SearchHistoryEntry("old query", 1L))
            override suspend fun load() = saved
            override suspend fun save(entries: List<SearchHistoryEntry>) {
                saved = entries
            }
        }
        val history = InMemorySearchHistory(clock, capacity = 2, persistence = store)
        history.record("Coldplay")
        history.record("coldplay ") // same normalized query
        assertEquals(listOf("coldplay", "old query"), history.recent(10).map { it.query })
        assertEquals(2, history.recent(1).single().count)
        history.record("creep")
        assertEquals(listOf("creep", "coldplay"), store.saved.map { it.query }, "capacity enforced and written through")
        history.remove("CREEP")
        assertEquals(listOf("coldplay"), history.recent(10).map { it.query })
        history.clear()
        assertTrue(store.saved.isEmpty())
    }
}
