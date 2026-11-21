package com.metrolist.shared.turtle

import com.metrolist.shared.equalizer.EqualizerConfiguration
import kotlin.concurrent.Volatile

/**
 * Turtle Player's live audio effects (turtle/HOOKS.md). This file is ours: a new file in the upstream
 * tree. While [equalizerOverride] is set, `EqualizerProfiles.configuration` returns it instead of the
 * saved equalizer, so Turtle DJ's filter sweeps, bass cuts and volume rides are heard on the next
 * audio buffer (Android's EqualizerAudioProcessor and desktop's AudioRenderer read it per buffer)
 * without writing the user's equalizer settings. Read on audio threads, hence volatile.
 */
object TurtleAudioFx {
    @Volatile
    var equalizerOverride: EqualizerConfiguration? = null
}
