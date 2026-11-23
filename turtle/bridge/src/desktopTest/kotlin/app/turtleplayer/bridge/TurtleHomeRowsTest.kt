package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.HomeRowConfig
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.LessLikeThis
import app.turtleplayer.taste.SongOrder
import app.turtleplayer.taste.TasteConfig
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfStyle
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurtleHomeRowsTest {
    private val t0 = 1_793_577_600_000L

    private fun song(
        id: String,
        podcastId: String? = null,
        artist: String = "Queen",
    ) = SongItem(
        id = id,
        title = "t$id",
        thumbnail = null,
        artists = listOf(ArtistTiny(null, artist)),
        album = null,
        duration = 200,
        podcastId = podcastId,
    )

    private fun finished(
        id: String,
        atMs: Long,
        end: EndReason = EndReason.COMPLETED,
        msPlayed: Long = 200_000,
    ) = PlayEvent(
        track = TrackMeta(TrackKey.youtube(id), "t$id", listOf("Queen"), durationMs = 200_000, youtubeId = id),
        sessionId = "s",
        position = 0,
        surface = Surface.AUTOPLAY,
        startReason = StartReason.AUTOPLAY,
        endReason = end,
        startedAtMs = atMs,
        msPlayed = msPlayed,
        durationMs = 200_000,
    )

    private fun engine(
        store: PlayEventStore,
        songOrder: SongOrder = SongOrder.OFF,
    ) = TasteEngine(
        store,
        InMemoryEmbeddingStore(),
        InMemoryTrackCatalog(emptyList()),
        config = TasteConfig(homeRows = HomeRowConfig(songOrder = songOrder)),
        clock = Clock { t0 + HOUR_MS },
    )

    @Test
    fun jumpBackInResolvesFromCacheAndDatabaseAndSkipsSpokenWord() =
        runBlocking {
            // Review Focus 2 and 5
            val dbFile =
                File.createTempFile("metrolist", ".db").apply {
                    delete()
                    deleteOnExit()
                }
            val db = getDatabaseBuilder(dbFile).build()
            db.dao().insert(SongEntity(id = "b1", title = "tb1", customArtist = "Queen", duration = 200)) // only in upstream's table
            val cache = SongCache().apply { putAll(listOf(song("a1"), song("pod1", podcastId = "P1"))) }
            val store =
                InMemoryPlayEventStore().apply {
                    appendAll(
                        listOf(
                            finished("a1", t0),
                            finished("b1", t0 + 5 * MINUTE_MS),
                            finished("pod1", t0 + 10 * MINUTE_MS),
                            finished("gone1", t0 + 15 * MINUTE_MS),
                        ),
                    )
                }
            // A chapter of a Turtle audiobook, stored before W3 kept spoken word out of the log.
            store.appendAll(listOf(finished("ch1", t0 + 17 * MINUTE_MS)))
            cache.putAll(listOf(song("ch1")))
            store.appendAll(listOf(finished("blk1", t0 + 20 * MINUTE_MS))) // on upstream's Block list
            cache.putAll(listOf(song("blk1")))
            val home =
                TurtleHomeRows(
                    engine(store),
                    store,
                    cache,
                    { db },
                    blockChanges = emptyFlow<Unit>(),
                    isBlocked = { it.id == "blk1" },
                    isSpokenWord = { it.isPodcast || it.id == "ch1" },
                    spokenReady = {},
                )
            assertNull(home.jumpBackIn)
            home.refreshJumpBackIn()
            assertEquals(listOf("b1", "a1"), home.jumpBackIn?.map { it.id }) // blocked, chapter, podcast and unresolvable dropped
            db.close()
        }

    private fun rows(
        store: PlayEventStore,
        taste: TasteEngine = engine(store),
    ) = TurtleHomeRows(
        taste,
        store,
        SongCache(),
        { error("no database needed") },
        blockChanges = emptyFlow<Unit>(),
        isBlocked = { false },
        spokenReady = {},
    )

    private fun shelf(
        key: String,
        vararg ids: String,
    ) = HomeShelf(key = key, title = key, items = ids.map { song(it) }, style = HomeShelfStyle.CAROUSEL)

    @Test
    fun scoringPublishesTheFirstRowsAtColdStart() =
        runBlocking {
            val home = rows(InMemoryPlayEventStore())
            val shelves = listOf(shelf("a", "sa"), shelf("b", "sb"), shelf("c", "sc"), shelf("d", "sd"))
            home.score(shelves, listOf(song("r1"), song("r2")))
            assertEquals(HomeRowsPick(rowKeys = listOf("a", "b", "c"), gridIds = setOf("r1", "r2"), scored = false), home.pick)
        }

    @Test
    fun controlsChangeRescoresTheShownRows() =
        runBlocking {
            // Review Focus 1
            // 60 finished Queen plays: warm, and Queen is loved. All song() items are by Queen.
            val store = InMemoryPlayEventStore()
            store.appendAll(List(60) { finished("h$it", t0 - 2 * DAY_MS + it * 5 * MINUTE_MS) })
            val taste = engine(store)
            val home = rows(store, taste)
            home.score(listOf(shelf("queen", "n1", "n2", "n3", "n4")), emptyList())
            assertEquals(listOf("queen"), home.pick?.rowKeys)
            assertEquals(true, home.pick?.scored)
            taste.lessLikeThis(LessLikeThis.Artist(ArtistKey.of("Queen")))
            home.onControlsChanged()
            assertEquals(HomeRowsPick(emptyList(), emptySet(), scored = true), home.pick)
        }

    private fun HomeLayout.rowIds(key: String) =
        youtubeRows
            .first { it.key == key }
            .items
            .map { it.id }

    @Test
    fun matchedRowOpensWithItsBestSupportedSong() =
        runBlocking {
            // First review, replay case 1: a weak song leads a Matched shelf.
            val history =
                // Warm (coldStartPlays is 50), and Queen is loved.
                List(60) { finished("h$it", t0 - 2 * DAY_MS + it * 5 * MINUTE_MS) } +
                    // lv1: finished six times three days ago.
                    List(6) { finished("lv1", t0 - 3 * DAY_MS + it * 5 * MINUTE_MS) } +
                    // x1: quick-skipped three times yesterday.
                    List(3) { finished("x1", t0 - DAY_MS + it * 5 * MINUTE_MS, end = EndReason.SKIPPED, msPlayed = 5_000) } +
                    // g1: finished half an hour ago; it is the Jump back in tile below.
                    listOf(finished("g1", t0 + 30 * MINUTE_MS))
            // g1 duplicates a grid tile; hid1 was hidden elsewhere.
            val mixed = shelf("section_mixed_4", "g1", "hid1", "x1", "n1", "n2", "lv1")
            val nova = HomeShelf("section_nova_5", "Nova", List(4) { song("nv$it", artist = "Nova") }, HomeShelfStyle.CAROUSEL)
            val shelves = listOf(mixed, nova)
            val ui = HomeUiState()
            val hidden = HiddenSet(songIds = setOf("hid1"))
            val recents = listOf(song("g1"))
            val provider = listOf("x1", "n1", "n2", "lv1")

            // Scored as ShellHome scores: YouTube's rows without what the listener hid.
            suspend fun pick(
                store: PlayEventStore,
                songOrder: SongOrder,
            ): HomeRowsPick {
                val home = rows(store, engine(store, songOrder))
                val candidates = HomeLayout.candidates(HomeLayout.visible(shelves, hidden), ui, HomeYouTubeRows.MATCHED)
                home.score(candidates, HomeLayout.gridCandidates(shelves, ui))
                return requireNotNull(home.pick)
            }

            fun layout(
                pick: HomeRowsPick,
                mode: HomeYouTubeRows,
            ) = HomeLayout.of(shelves, ui, recents, pick, mode, hidden)

            val warm = InMemoryPlayEventStore().apply { appendAll(history) }
            val ordered = pick(warm, SongOrder.ON)
            assertTrue(ordered.scored)
            assertEquals(listOf("section_mixed_4"), ordered.rowKeys)
            val matched = layout(ordered, HomeYouTubeRows.MATCHED)
            assertEquals(listOf("g1"), matched.grid.map { it.id })
            val shown = matched.rowIds("section_mixed_4")
            // The song finished six times leads, the never-heard ones keep YouTube's order, the thrice-skipped one is last.
            assertEquals(listOf("lv1", "n1", "n2", "x1"), shown)
            assertEquals(setOf("section_mixed_4"), matched.orderedRows)

            // YouTube's order: under All (even with the ordered pick), with ordering off, and at cold start.
            val all = layout(ordered, HomeYouTubeRows.ALL)
            assertEquals(provider, all.rowIds("section_mixed_4"))
            assertEquals(emptySet(), all.orderedRows)
            val off = layout(pick(warm, SongOrder.OFF), HomeYouTubeRows.MATCHED)
            assertEquals(provider, off.rowIds("section_mixed_4"))
            assertEquals(emptySet(), off.orderedRows)
            assertEquals(setOf("section_mixed_4"), off.comparisonRows) // could be ordered, so it is the comparison
            val cold = InMemoryPlayEventStore().apply { appendAll(history.drop(20)) } // 40 + 6 + 1 finished
            val coldPick = pick(cold, SongOrder.ON)
            assertEquals(emptyMap(), coldPick.songSupport)
            val coldLayout = layout(coldPick, HomeYouTubeRows.MATCHED)
            assertEquals(provider, coldLayout.rowIds("section_mixed_4"))
            assertEquals(emptySet(), coldLayout.orderedRows)
            assertEquals(emptySet(), coldLayout.comparisonRows)
        }
}
