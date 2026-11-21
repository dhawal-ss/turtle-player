package com.metrolist.shared.turtle

import kotlin.concurrent.Volatile

/**
 * Turtle Player's live audio seams (turtle/HOOKS.md). This file is ours: a new file in the upstream
 * tree. Everything here is null or upstream's default outside a Turtle DJ set, so upstream's audio
 * path is untouched then.
 *
 * - [processor]: called first in `ParametricEqualizer.processPcm16`, once per player pipeline (two
 *   at once during a crossfade), in place on 16-bit interleaved PCM. Turtle DJ's per-deck effects
 *   rack (echo, loop rolls, tape stops, bass swaps, samples, beat tracking) lives behind it.
 * - [crossfadeGains]: while it returns non-null, crossfades use its (outgoing, incoming) gains
 *   instead of upstream's curve, so the DJ's rack can do the volume work itself.
 * - [maxCrossfadeMs]: upstream caps crossfades at 15 s; DJ blends may run up to 30 s.
 *
 * Read on audio threads, hence volatile.
 */
object TurtleAudioFx {
    @Volatile
    var processor: PcmProcessor? = null

    @Volatile
    var crossfadeGains: ((Float) -> Pair<Float, Float>?)? = null

    @Volatile
    var maxCrossfadeMs: Long = 15_000L

    fun interface PcmProcessor {
        /** [pipeline] identifies the player pipeline (stable per player). Must not block or allocate. */
        fun process(pipeline: Any, samples: ShortArray, count: Int, sampleRate: Int, channels: Int)
    }
}
