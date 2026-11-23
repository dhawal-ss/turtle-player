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
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.LessLikeThis
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfStyle
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurtleHomeRowsTest {
    private val t0 = 1_793_577_600_000L

    private fun song(
        id: String,
        podcastId: String? = null,
    ) = SongItem(
        id = id,
        title = "t$id",
        thumbnail = null,
        artists = listOf(ArtistTiny(null, "Queen")),
        album = null,
        duration = 200,
        podcastId = podcastId,
    )

    private fun finished(
        id: String,
        atMs: Long,
    ) = PlayEvent(
        track = TrackMeta(TrackKey.youtube(id), "t$id", listOf("Queen"), durationMs = 200_000, youtubeId = id),
        sessionId = "s",
        position = 0,
        surface = Surface.AUTOPLAY,
        startReason = StartReason.AUTOPLAY,
        endReason = EndReason.COMPLETED,
        startedAtMs = atMs,
        msPlayed = 200_000,
        durationMs = 200_000,
    )

    private fun engine(store: PlayEventStore) =
        TasteEngine(store, InMemoryEmbeddingStore(), InMemoryTrackCatalog(emptyList()), clock = Clock { t0 + HOUR_MS })

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
            assertEquals(HomeRowsPick(listOf("queen"), emptySet(), scored = true), home.pick)
            taste.lessLikeThis(LessLikeThis.Artist(ArtistKey.of("Queen")))
            home.onControlsChanged()
            assertEquals(HomeRowsPick(emptyList(), emptySet(), scored = true), home.pick)
        }
}
