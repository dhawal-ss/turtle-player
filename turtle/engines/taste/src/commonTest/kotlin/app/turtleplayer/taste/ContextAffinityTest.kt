package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.InMemoryPlayEventStore
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Time-of-day and weekday/weekend context: who the listener plays when, as a ranking signal. */
class ContextAffinityTest {
    private val start = SyntheticWorld.T0
    private val now = start + 30 * DAY_MS

    private val morning = artist("Morning Artist")
    private val evening = artist("Evening Artist")
    private val weekendOnly = artist("Weekend Artist")
    private val weekdayOnly = artist("Weekday Artist")

    /** Six songs per artist: five to play, the sixth never heard, so only artist-level signals tell them apart. */
    private fun artist(name: String) = List(6) { TrackMeta(TrackKey("test:$name-$it"), "$name song $it", listOf(name), durationMs = 200_000) }

    private val List<TrackMeta>.key: ArtistKey get() = first().artistKey
    private val List<TrackMeta>.unheard: TrackMeta get() = last()

    /** [days] finished plays of [artist], one a day at [hour] on [weekday] (ISO, 1 = Monday). */
    private fun TasteProfileBuilder.plays(artist: List<TrackMeta>, days: Int, hour: Int, weekday: (Int) -> Int = { 1 + it % 5 }) {
        repeat(days) { d ->
            val at = start + d * DAY_MS + hour * HOUR_MS
            add(play(artist[d % 5], at).copy(context = PlayContext(hour = hour, weekday = weekday(d))))
        }
    }

    private fun extractor(profile: TasteProfile, context: PlayContext?, controls: UserControls = UserControls()) =
        FeatureExtractor(profile, controls, sessionVector = null, nowMs = now, context = context)

    private fun FeatureExtractor.contextAffinity(t: TrackMeta) = extract(candidate(t), null)[Feature.CONTEXT_AFFINITY]

    private fun candidate(t: TrackMeta) = Candidate(t, CandidateSource.TASTE_CLUSTER, Reason.NewForYou, retrieval = 0.5f)

    /** The two unheard tracks, best first, as the default ranker orders them. */
    private fun rank(e: FeatureExtractor, vararg artists: List<TrackMeta>): List<ArtistKey> {
        val ranker = HandTunedRanker()
        return artists.map { it.unheard }.sortedByDescending { ranker.score(e.extract(candidate(it), null)) }.map { it.artistKey }
    }

    private fun morningAndEvening(days: Int = 20) = TasteProfileBuilder().apply {
        plays(morning, days, hour = 8)
        plays(evening, days, hour = 21)
    }

    private val wednesdayAt8 = PlayContext(hour = 8, weekday = 3)
    private val wednesdayAt22 = PlayContext(hour = 22, weekday = 3)

    @Test
    fun morningArtistRanksFirstInTheMorningAndEveningArtistAtNight() {
        val profile = morningAndEvening().snapshot(now, null)

        assertEquals(listOf(morning.key, evening.key), rank(extractor(profile, wednesdayAt8), morning, evening))
        assertEquals(listOf(evening.key, morning.key), rank(extractor(profile, wednesdayAt22), morning, evening))

        val atEight = extractor(profile, wednesdayAt8)
        assertTrue(atEight.contextAffinity(morning.unheard) > 0.2f)
        assertTrue(atEight.contextAffinity(evening.unheard) < -0.2f)
    }

    @Test
    fun unknownContextHasNoEffect() {
        val profile = morningAndEvening().snapshot(now, null)
        val e = extractor(profile, context = null)
        assertEquals(0f, e.contextAffinity(morning.unheard))
        assertEquals(0f, e.contextAffinity(evening.unheard))
        // Plays recorded without a context leave the artist neutral, whatever the current time.
        val noContext = TasteProfileBuilder().apply { repeat(20) { add(play(morning[it % 5], start + it * DAY_MS + 8 * HOUR_MS)) } }.snapshot(now, null)
        assertEquals(0f, extractor(noContext, wednesdayAt22).contextAffinity(morning.unheard))
    }

    @Test
    fun artistsWithFewPlaysStayNearNeutral() {
        val profile = TasteProfileBuilder().apply {
            plays(morning, 1, hour = 8)
            plays(evening, 20, hour = 21) // the listener's overall habit: evenings
        }.snapshot(now, null)
        for (context in listOf(wednesdayAt8, wednesdayAt22)) {
            val v = extractor(profile, context).contextAffinity(morning.unheard)
            assertTrue(abs(v) < 0.15f, "one play at $context gave $v")
        }
        // The same habit with twenty plays is a clear signal.
        val established = morningAndEvening().snapshot(now, null)
        assertTrue(extractor(established, wednesdayAt22).contextAffinity(morning.unheard) < -0.5f)
    }

    @Test
    fun anArtistPlayedAtTheListenersUsualTimeIsNeutral() {
        // Everything happens in the evening: an evening artist is not special at night, just typical.
        val profile = TasteProfileBuilder().apply {
            plays(evening, 20, hour = 21)
            plays(weekdayOnly, 20, hour = 20)
        }.snapshot(now, null)
        val v = extractor(profile, wednesdayAt22).contextAffinity(evening.unheard)
        assertTrue(abs(v) < 0.05f, "typical artist lifted by $v")
    }

    @Test
    fun weekendAndWeekdayHabitsSplit() {
        // Same hour, so only the day type differs.
        val profile = TasteProfileBuilder().apply {
            plays(weekendOnly, 20, hour = 14) { 6 + it % 2 }
            plays(weekdayOnly, 20, hour = 14)
        }.snapshot(now, null)
        val saturday = PlayContext(hour = 14, weekday = 6)
        val wednesday = PlayContext(hour = 14, weekday = 3)
        assertEquals(listOf(weekendOnly.key, weekdayOnly.key), rank(extractor(profile, saturday), weekendOnly, weekdayOnly))
        assertEquals(listOf(weekdayOnly.key, weekendOnly.key), rank(extractor(profile, wednesday), weekendOnly, weekdayOnly))
        val sunday = extractor(profile, PlayContext(hour = 14, weekday = 7))
        assertTrue(sunday.contextAffinity(weekendOnly.unheard) > 0.2f)
        assertTrue(sunday.contextAffinity(weekdayOnly.unheard) < -0.2f)
    }

    @Test
    fun weekendDaysAreConfigurable() {
        // A Friday-Saturday weekend: Friday plays are weekend plays, Sunday is a weekday.
        val weights = AffinityWeights(weekendDays = setOf(5, 6))
        val profile = TasteProfileBuilder(weights).apply {
            plays(weekendOnly, 20, hour = 14) { 5 + it % 2 }
            plays(weekdayOnly, 20, hour = 14) { listOf(7, 1, 2, 3, 4)[it % 5] }
        }.snapshot(now, null)
        val mix = profile.artist(weekendOnly.key)!!.context
        assertTrue(mix.total > 0)
        assertEquals(mix.total, mix.weekend, 1e-9)
        val friday = PlayContext(hour = 14, weekday = 5)
        val sunday = PlayContext(hour = 14, weekday = 7)
        assertEquals(listOf(weekendOnly.key, weekdayOnly.key), rank(extractor(profile, friday), weekendOnly, weekdayOnly))
        assertEquals(listOf(weekdayOnly.key, weekendOnly.key), rank(extractor(profile, sunday), weekendOnly, weekdayOnly))
    }

    @Test
    fun skipsDoNotCountAsContextHabit() {
        val profile = TasteProfileBuilder().apply {
            plays(evening, 20, hour = 21)
            repeat(10) { d -> add(play(morning[d % 5], start + d * DAY_MS + 8 * HOUR_MS, EndReason.SKIPPED).copy(context = wednesdayAt8)) }
        }.snapshot(now, null)
        assertEquals(0.0, profile.artist(morning.key)!!.context.total)
        assertEquals(0f, extractor(profile, wednesdayAt8).contextAffinity(morning.unheard))
    }

    @Test
    fun contextNeverOutweighsALessLikeThisMark() {
        val profile = morningAndEvening().snapshot(now, null)
        val controls = UserControls(lessLikeThisArtists = mapOf(morning.key to 1))
        assertEquals(listOf(evening.key, morning.key), rank(extractor(profile, wednesdayAt8, controls), morning, evening))
    }

    @Test
    fun habitsDecayLikeAffinity() {
        val t = morning[0]
        val builder = TasteProfileBuilder().apply {
            add(play(t, start).copy(context = wednesdayAt8))
            add(play(t, start - 90 * DAY_MS).copy(context = wednesdayAt22)) // an import arriving late
        }
        val mix = builder.snapshot(start, null).artist(morning.key)!!.context
        assertEquals(1.0, mix.hourBucket(wednesdayAt8.hourBucket), 1e-6)
        assertEquals(0.5, mix.hourBucket(wednesdayAt22.hourBucket), 1e-6)
        assertEquals(1.5, mix.weekday, 1e-6)
        assertEquals(0.0, mix.weekend)
        assertEquals(0.75, builder.snapshot(start + 90 * DAY_MS, null).artist(morning.key)!!.context.total, 1e-6)
    }

    @Test
    fun theEngineRanksForTheTimeNowNotTheLastPlay() = runTest {
        // Evening plays last: the last play's context says 21:00, but the app is opened at 08:00.
        val events = InMemoryPlayEventStore()
        repeat(20) { d ->
            events.append(play(morning[d % 5], start + d * DAY_MS + 8 * HOUR_MS).copy(context = PlayContext(8, 1 + d % 5)))
            events.append(play(evening[d % 5], start + d * DAY_MS + 21 * HOUR_MS).copy(context = PlayContext(21, 1 + d % 5)))
        }
        val at8 = start + 21 * DAY_MS + 8 * HOUR_MS
        val clock = MutableClock(at8)
        val hourNow = { ms: Long -> PlayContext(hour = (ms.mod(DAY_MS) / HOUR_MS).toInt(), weekday = 3) }
        val seed = artist("Seed").first()
        fun engine(contextAt: ((Long) -> PlayContext)?) =
            TasteEngine(events, InMemoryEmbeddingStore(), InMemoryTrackCatalog(morning + evening), clock = clock, contextAt = contextAt)

        val aware = engine(hourNow)
        aware.profile()
        assertEquals(morning.key, aware.rankExternal(seed, listOf(evening.unheard, morning.unheard)).first().track.artistKey)
        clock.now = at8 + 14 * HOUR_MS // 22:00
        assertEquals(evening.key, aware.rankExternal(seed, listOf(morning.unheard, evening.unheard)).first().track.artistKey)
    }
}
