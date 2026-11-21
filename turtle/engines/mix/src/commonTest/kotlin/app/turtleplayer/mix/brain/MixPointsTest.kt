package app.turtleplayer.mix.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixPointsTest {

    @Test
    fun fullSongsMixOutBeforeTheFadeOut() {
        // 3:30 radio edit: keep the last 8 % (16.8 s) out, but always 30 s before the end.
        val radio = track(genre = "pop", durationMs = 210_000)
        assertEquals(180_000L, MixPoints.mixOutAtMs(radio, 100f, 0f, 4.0))
        // 6:00 radio track: the tail is capped at 25 s, so the 30 s rule decides.
        val long = track(genre = "pop", durationMs = 360_000)
        assertEquals(330_000L, MixPoints.mixOutAtMs(long, 100f, 0f, 4.0))
    }

    @Test
    fun danceTracksMixOutOfTheOutro() {
        // 4:00 at 125 BPM: 32 beats + 8 pre beats is under 30 s, so the 30 s rule decides.
        val short = track(durationMs = 240_000)
        assertEquals(210_000L, MixPoints.mixOutAtMs(short, 125f, 0f, 8.0))
        // 7:30: last 64 beats (30.72 s).
        val long = track(durationMs = 450_000)
        assertEquals(450_000L - 30_720 - 3_840, MixPoints.mixOutAtMs(long, 125f, 0f, 8.0))
        // 5:30 at 100 BPM: 48 beats (28.8 s) out, minus 8 pre beats (4.8 s).
        val mid = track(durationMs = 330_000)
        assertEquals(330_000L - 28_800 - 4_800, MixPoints.mixOutAtMs(mid, 100f, 0f, 8.0))
    }

    @Test
    fun quickMixesLeaveAfterAboutAMinute() {
        for (dur in listOf(180_000L, 240_000L, 420_000L)) {
            val at = MixPoints.mixOutAtMs(track(durationMs = dur), 124f, 1f, 4.0)
            assertTrue(at in 60_000L..75_000L, "$dur -> $at")
        }
        // A 90 s track: never before 40 % (36 s), always 30 s before the end.
        val short = MixPoints.mixOutAtMs(track(genre = "pop", durationMs = 90_000), 100f, 1f, 4.0)
        assertTrue(short in 36_000L..60_000L, "$short")
    }

    @Test
    fun paceInterpolatesMonotonicallyAndKeeps30sBeforeTheEnd() {
        for (dur in listOf(75_000L, 150_000L, 210_000L, 300_000L, 600_000L)) for (dance in listOf(true, false)) {
            val t = track(durationMs = dur, dance = dance)
            var last = Long.MAX_VALUE - 1
            for (i in 0..10) {
                val at = MixPoints.mixOutAtMs(t, 128f, i / 10f, 4.0)
                assertTrue(at <= dur - 30_000, "$dur pace ${i / 10f}: $at")
                assertTrue(at >= 0)
                assertTrue(at <= last + 1, "later pace mixes earlier: $dur")
                last = at
            }
        }
    }
}
