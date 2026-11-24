package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.CandidateSource
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.core.TrackKey
import app.turtleplayer.taste.QueueItem
import app.turtleplayer.taste.Reason
import app.turtleplayer.taste.RecommendationSource
import app.turtleplayer.taste.SavedKind
import app.turtleplayer.taste.SavedTrack
import app.turtleplayer.taste.Shelf
import app.turtleplayer.taste.ShelfCadence
import app.turtleplayer.taste.ShelfKind
import app.turtleplayer.taste.SlotKind
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurtleHomeShelvesTest {
    private val now = 1_800_000_000_000L

    private fun song(
        id: String,
        artist: String,
    ) = SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    private val heard = (0 until 12).map { song("h$it", "Artist ${it / 4}") }
    private val radioSongs = (0 until 40).map { song("r$it", "Radio ${it % 10}") }
    private val history =
        heard.flatMapIndexed { i, s ->
            (1..3).map { k ->
                PlayEvent(
                    track = s.toTrackMeta(),
                    sessionId = "s",
                    position = 0,
                    surface = Surface.AUTOPLAY,
                    startReason = StartReason.AUTOPLAY,
                    endReason = EndReason.COMPLETED,
                    startedAtMs = now - (k * 24 + i) * HOUR_MS,
                    msPlayed = 200_000,
                    durationMs = 200_000,
                )
            }
        }

    /** YouTube radio as the bridge wires it: songs land in the cache, metas go to the engine. */
    private class Radio(
        private val cache: SongCache,
        private val songs: List<SongItem>,
    ) : RecommendationSource {
        var calls = 0

        override suspend fun related(
            seed: TrackMeta,
            limit: Int,
        ): List<TrackMeta> {
            calls++
            cache.putAll(songs)
            return songs.take(limit).map { it.toTrackMeta() }
        }
    }

    private suspend fun engine(upstream: RecommendationSource) =
        TasteEngine(
            events = InMemoryPlayEventStore().also { it.appendAll(history) },
            store = InMemoryEmbeddingStore(),
            catalog = InMemoryTrackCatalog(heard.map { it.toTrackMeta() }),
            upstream = upstream,
            clock = Clock { now },
            random = Random(7),
        )

    @Test
    fun shelfSongsComeBackAfterARestartWithoutTheNetwork() =
        runTest {
            val file =
                File.createTempFile("turtle", ".db").apply {
                    delete()
                    deleteOnExit()
                }

            // First run: shelves built from history and radio, then the process dies before any play.
            val db1 = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val cache1 = SongCache().also { it.replaceLibrary(heard) }
            val engine1 = engine(Radio(cache1, radioSongs))
            val home1 = TurtleHomeShelves(engine1, InMemoryPlayEventStore(), cache1, db1)
            home1.restore()
            val built = engine1.homeShelves()
            home1.persist(built)
            val ids = built.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct()
            assertTrue(ids.any { it.startsWith("r") }, "a kept shelf holds never-heard radio songs: $ids")
            val songs = ids.map { assertNotNull(cache1.get(it)) }
            db1.close()

            // New runtime: library songs only, the network down, and a long session flooding the LRU.
            val db2 = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val cache2 = SongCache(capacity = 1).also { it.replaceLibrary(heard) }
            val offline =
                object : RecommendationSource {
                    var calls = 0

                    override suspend fun related(
                        seed: TrackMeta,
                        limit: Int,
                    ): List<TrackMeta> {
                        calls++
                        error("offline")
                    }
                }
            val engine2 = engine(offline)
            assertTrue(engine2.importState(assertNotNull(db2.pref(PREF_TASTE_STATE))))
            TurtleHomeShelves(engine2, InMemoryPlayEventStore(), cache2, db2).restore()
            cache2.putAll((0 until 50).map { song("f$it", "Filler") })

            val kept = engine2.keptShelves()
            assertEquals(built, kept)
            val restoredIds = kept.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct()
            assertEquals(songs, restoredIds.map { assertNotNull(cache2.get(it), it) })
            assertEquals(0, offline.calls)
            db2.close()
        }

    private val expectedCadence =
        mapOf(
            ShelfKind.DAILY_MIX to ShelfCadence.DAILY,
            ShelfKind.MORE_LIKE to ShelfCadence.DAILY_MORE_LIKE,
            ShelfKind.WEEKLY_DISCOVERY to ShelfCadence.WEEKLY,
            ShelfKind.FRESH_FOR_YOU to ShelfCadence.DAILY_FRESH,
            ShelfKind.REDISCOVER to ShelfCadence.DAILY_REDISCOVER,
        )

    @Test
    fun mixAboutDescribesEachKind() =
        runTest {
            val cache = SongCache().also { it.replaceLibrary(heard) }
            val engine = engine(Radio(cache, radioSongs))
            val shelves = engine.homeShelves()
            assertTrue(shelves.isNotEmpty())
            for (shelf in shelves) {
                val about = mixAbout(shelf, { engine.displayName(it) }, { engine.displayName(it) })
                assertEquals(shelf.kind, about.kind)
                assertEquals(expectedCadence[shelf.kind], about.cadence, "${shelf.kind}")
                assertTrue(about.topArtists.isNotEmpty(), "${shelf.id} names its artists")
                assertTrue(about.topArtists.size <= MixAbout.MAX_TOP_ARTISTS)
                assertEquals(about.topArtists.distinct(), about.topArtists)
                // Daily Mixes lead with their own artists; the rest name the artists on their songs.
                if (shelf.reason !is Reason.DailyMix) {
                    val onShelf = shelf.items.map { it.track.primaryArtist }.toSet()
                    assertTrue(onShelf.containsAll(about.topArtists), "${about.topArtists} come from $onShelf")
                }
                when (val reason = shelf.reason) {
                    is Reason.DailyMix -> {
                        val seed = reason.seedArtist
                        if (seed != null) {
                            assertEquals(engine.displayName(seed), about.seed)
                            assertEquals(about.seed, about.topArtists.first(), "an artist mix leads with its artist")
                        } else {
                            assertNull(about.seed)
                        }
                    }
                    is Reason.BecauseYouReplayed -> assertEquals(engine.displayName(reason.track), about.seed)
                    is Reason.BecauseYouLiked -> assertEquals(engine.displayName(reason.track), about.seed)
                    is Reason.BecauseYouListened -> assertEquals(engine.displayName(reason.track), about.seed)
                    else -> assertNull(about.seed)
                }
            }
        }

    @Test
    fun mixAboutOnRepeatHasNoCadence() =
        runTest {
            val cache = SongCache().also { it.replaceLibrary(heard) }
            val engine = engine(Radio(cache, radioSongs))
            val items = engine.homeShelves().flatMap { it.items }.take(6)
            assertTrue(items.isNotEmpty())
            val about = mixAbout(Shelf("on_repeat", ShelfKind.ON_REPEAT, Reason.OnRepeat, items), { engine.displayName(it) }, { engine.displayName(it) })
            assertEquals(ShelfKind.ON_REPEAT, about.kind)
            assertNull(about.cadence)
            assertNull(about.seed)
        }

    @Test
    fun mixAboutRanksArtistsByPresenceAndFallsBackToTheirWrittenNames() =
        runTest {
            val cache = SongCache().also { it.replaceLibrary(heard) }
            val engine = engine(Radio(cache, radioSongs))
            val items = engine.homeShelves().flatMap { it.items }.distinctBy { it.key }
            // Two of one artist, one of another: the first leads.
            val byArtist = items.groupBy { it.track.artistKey }.values.sortedByDescending { it.size }
            val major = byArtist.first { it.size >= 2 }.take(2)
            val minor = byArtist.first { it.first().track.artistKey != major.first().track.artistKey }.take(1)
            val seed = TrackKey("yt:seed")
            val shelf = Shelf("more_like:x", ShelfKind.MORE_LIKE, Reason.BecauseYouReplayed(seed), minor + major)
            val about = mixAbout(shelf, { if (it == seed) "Seed Song" else null }, { null })
            assertEquals("Seed Song", about.seed)
            assertEquals(ShelfCadence.DAILY_MORE_LIKE, about.cadence)
            assertEquals(listOf(major.first().track.primaryArtist, minor.first().track.primaryArtist), about.topArtists)
        }

    private fun item(
        s: SongItem,
        slot: SlotKind,
        source: CandidateSource = CandidateSource.UPSTREAM_RADIO,
        reason: Reason = Reason.NewForYou,
    ) = QueueItem(s.toTrackMeta(), source, reason, slot, 0.5f)

    /** A brand-new profile: no plays, no saved songs, artists picked in onboarding. */
    private suspend fun coldEngine(picked: List<String>) =
        TasteEngine(
            events = InMemoryPlayEventStore(),
            store = InMemoryEmbeddingStore(),
            catalog = InMemoryTrackCatalog(radioSongs.map { it.toTrackMeta() }),
            clock = Clock { now },
            random = Random(7),
        ).also { it.onboard(picked.map { a -> ArtistKey.of(a) }) }

    @Test
    fun mixAboutOnAColdProfileKnowsNoneOfItsSongs() =
        runTest {
            val engine = coldEngine(listOf("Radio 0", "Radio 1", "Radio 2", "Radio 3"))
            val picked = ArtistKey.of("Radio 0")
            // A mix built from the onboarding picks: every candidate is unheard, so the ranker slots each as DISCOVERY.
            val items = radioSongs.take(12).map { item(it, SlotKind.DISCOVERY, CandidateSource.ONBOARDING, Reason.BecauseYouPicked(picked)) }
            for (reason in listOf(Reason.DailyMix(0, listOf(picked)), Reason.DailyMix(0, listOf(picked), seedArtist = picked))) {
                val shelf = Shelf("daily_mix:0", ShelfKind.DAILY_MIX, reason, items)
                val about = mixAbout(shelf, { null }, { null }, knownBy(engine.profile()))
                assertEquals(0, about.known, "a cold profile has heard none of $reason")
                assertEquals(12, about.total)
                assertNull(about.seedTie)
            }
        }

    @Test
    fun theEngineShelvesOfAColdOnboardedProfileHoldNoKnownSong() =
        runTest {
            // The engine's own shelves, built from the onboarding picks alone: nothing in them is heard.
            val engine = coldEngine(listOf("Radio 0", "Radio 1", "Radio 2", "Radio 3"))
            val profile = engine.profile()
            val shelves = engine.homeShelves()
            assertTrue(shelves.any { it.items.isNotEmpty() }, "a cold onboarded profile gets a mix: $shelves")
            for (shelf in shelves) {
                assertTrue(shelf.items.all { it.slot == SlotKind.DISCOVERY }, "${shelf.id} slots an unheard song as familiar")
                val about = mixAbout(shelf, { null }, { null }, knownBy(profile))
                assertEquals(0, about.known, shelf.id)
                assertEquals(emptySet(), about.knownSongs, shelf.id)
            }
        }

    @Test
    fun mixAboutCountsTheSongsActuallyHeard() =
        runTest {
            val cache = SongCache().also { it.replaceLibrary(heard) }
            val engine = engine(Radio(cache, radioSongs))
            val known = knownBy(engine.profile())
            val reason = Reason.DailyMix(0, emptyList())
            // Mixed: 3 heard songs and 7 new ones, whatever the plan wanted (a planned familiar slot can hold a new song).
            val mixed = heard.take(3).map { item(it, SlotKind.FAMILIAR) } + radioSongs.take(7).map { item(it, SlotKind.DISCOVERY) }
            val m = mixAbout(Shelf("daily_mix:0", ShelfKind.DAILY_MIX, reason, mixed), { null }, { null }, known)
            assertEquals(3 to 10, m.known to m.total)
            // The page counts what it lists by these ids: exactly the known songs.
            assertEquals(heard.take(3).map { it.id }.toSet(), m.knownSongs)
            // All familiar.
            val all = heard.take(8).map { item(it, SlotKind.FAMILIAR) }
            val a = mixAbout(Shelf("daily_mix:1", ShelfKind.DAILY_MIX, reason, all), { null }, { null }, known)
            assertEquals(8 to 8, a.known to a.total)
            // All new.
            val none = radioSongs.take(5).map { item(it, SlotKind.DISCOVERY) }
            val n = mixAbout(Shelf("weekly_discovery:weekly", ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, none), { null }, { null }, known)
            assertEquals(0 to 5, n.known to n.total)
            // The profile knows heard songs even when a kept shelf still carries the DISCOVERY slot it was built with.
            val stale = heard.take(2).map { item(it, SlotKind.DISCOVERY) } + radioSongs.take(2).map { item(it, SlotKind.DISCOVERY) }
            val s = mixAbout(Shelf("weekly_discovery:weekly", ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, stale), { null }, { null }, known)
            assertEquals(2 to 4, s.known to s.total)
            // Without a profile only the slots count.
            assertEquals(3, mixAbout(Shelf("daily_mix:0", ShelfKind.DAILY_MIX, reason, mixed), { null }, { null }).known)
        }

    @Test
    fun mixAboutCountsSavedSongsAsKnown() =
        runTest {
            val engine = coldEngine(listOf("Radio 0"))
            engine.setSavedTracks(listOf(SavedTrack(radioSongs[0].toTrackMeta(), SavedKind.LIBRARY, now - HOUR_MS)))
            val items = radioSongs.take(4).map { item(it, SlotKind.DISCOVERY) }
            val about = mixAbout(Shelf("daily_mix:0", ShelfKind.DAILY_MIX, Reason.DailyMix(0, emptyList()), items), { null }, { null }, knownBy(engine.profile()))
            assertEquals(1 to 4, about.known to about.total)
        }

    @Test
    fun mixAboutOnTheEngineShelvesMatchesWhatTheyAreMadeOf() =
        runTest {
            val cache = SongCache().also { it.replaceLibrary(heard) }
            val engine = engine(Radio(cache, radioSongs))
            val profile = engine.profile()
            val shelves = engine.homeShelves()
            assertTrue(shelves.isNotEmpty())
            for (shelf in shelves) {
                val about = mixAbout(shelf, { engine.displayName(it) }, { engine.displayName(it) }, knownBy(profile))
                assertEquals(shelf.items.size, about.total)
                assertEquals(shelf.items.count { profile.hasHeard(it.track) }, about.known, shelf.id)
                when (shelf.kind) {
                    ShelfKind.WEEKLY_DISCOVERY -> assertEquals(0, about.known, "weekly discovery holds only unheard songs")
                    ShelfKind.ON_REPEAT, ShelfKind.REDISCOVER -> assertEquals(about.total, about.known, "${shelf.kind} holds only heard songs")
                    else -> Unit
                }
            }
        }

    @Test
    fun mixAboutSaysWhyAMoreLikeSeedWasPicked() =
        runTest {
            val seed = TrackKey("yt:seed")
            val items = radioSongs.take(3).map { item(it, SlotKind.DISCOVERY) }
            val ties =
                listOf(Reason.BecauseYouReplayed(seed), Reason.BecauseYouLiked(seed), Reason.BecauseYouListened(seed)).map {
                    mixAbout(Shelf("more_like:x", ShelfKind.MORE_LIKE, it, items), { "Seed" }, { null }).seedTie
                }
            assertEquals(listOf(MixSeedTie.REPLAYED, MixSeedTie.LIKED, MixSeedTie.LISTENED), ties)
            assertNull(mixAbout(Shelf("on_repeat", ShelfKind.ON_REPEAT, Reason.OnRepeat, items), { null }, { null }).seedTie)
        }
}
