package app.turtleplayer.bridge

import app.turtleplayer.mix.rack.MixRack
import com.metrolist.shared.turtle.TurtleAudioFx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.nextDown
import kotlin.time.TimeSource

/**
 * Turtle DJ's effects rack, plugged into the app's audio path through [TurtleAudioFx] (hooks #56 to
 * #58) only while a set runs. Outside a set every seam is back to upstream's behaviour.
 */
object DjRack {
    private val origin = TimeSource.Monotonic.markNow()
    val rack = MixRack { origin.elapsedNow().inWholeNanoseconds }

    /** Set after a transition's script has finished while the outgoing player still runs to its end. */
    @Volatile
    var afterMix: Boolean = false

    @Volatile
    var installed: Boolean = false
        private set

    fun install() {
        if (installed) return
        rack.reset()
        rack.enabled = true
        afterMix = false
        TurtleAudioFx.maxCrossfadeMs = MAX_WINDOW_MS
        TurtleAudioFx.processor = TurtleAudioFx.PcmProcessor { pipeline, samples, count, rate, channels ->
            rack.process(pipeline, samples, count, rate, channels)
        }
        // Both players at full while the rack's script mixes; after it, only the incoming one.
        TurtleAudioFx.crossfadeGains = { _ ->
            when {
                rack.transition.running -> 1f to FULL
                afterMix -> 0f to FULL
                else -> null
            }
        }
        installed = true
        CoroutineScope(Dispatchers.Default).launch { runCatching { rack.warmUp() } }
    }

    fun uninstall() {
        TurtleAudioFx.processor = null
        TurtleAudioFx.crossfadeGains = null
        TurtleAudioFx.maxCrossfadeMs = DEFAULT_WINDOW_MS
        afterMix = false
        rack.reset()
        rack.enabled = false
        installed = false
    }

    const val MAX_WINDOW_MS = 30_000L
    private const val DEFAULT_WINDOW_MS = 15_000L

    // Upstream keeps the incoming gain just under 1 until the crossfade completes.
    private val FULL = 1f.nextDown()
}

/**
 * What Turtle DJ has measured about songs while they played (tempo, energy, loudness), kept on device
 * so the next set knows the real BPM instead of a genre guess. Capped to the most recent [CAPACITY].
 */
object DjMemory {
    data class Facts(val bpm: Float, val energy: Float, val loudnessDb: Float)

    private val mutex = Mutex()
    private val facts = LinkedHashMap<String, Facts>()
    private var loaded = false
    private var saveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        loaded = true
        val raw = runCatching { TurtleBridge.uiPref(PREF) }.getOrNull().orEmpty()
        raw.split(';').forEach { entry ->
            val p = entry.split(':')
            if (p.size == 4) {
                val bpm = p[1].toFloatOrNull() ?: return@forEach
                val energy = p[2].toFloatOrNull() ?: return@forEach
                val loud = p[3].toFloatOrNull() ?: return@forEach
                facts[p[0]] = Facts(bpm, energy, loud)
            }
        }
        snapshot = HashMap(facts)
    }

    /** Remembered facts for [id], or null. Non-suspending: reads whatever has loaded. */
    fun get(id: String): Facts? = snapshot[id]

    @Volatile
    private var snapshot: Map<String, Facts> = emptyMap()

    fun remember(id: String, value: Facts) {
        if (id.isBlank() || ':' in id || ';' in id) return
        scope.launch {
            mutex.withLock {
                facts.remove(id)
                facts[id] = value
                while (facts.size > CAPACITY) facts.remove(facts.keys.first())
                snapshot = HashMap(facts)
            }
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            val encoded = mutex.withLock {
                facts.entries.joinToString(";") { (id, f) ->
                    "$id:${round1(f.bpm)}:${round2(f.energy)}:${round1(f.loudnessDb)}"
                }
            }
            runCatching { TurtleBridge.setUiPref(PREF, encoded) }
        }
    }

    private fun round1(v: Float) = (kotlin.math.round(v * 10f) / 10f).toString()
    private fun round2(v: Float) = (kotlin.math.round(v * 100f) / 100f).toString()

    private const val PREF = "dj_memory"
    private const val CAPACITY = 1_500
    private const val SAVE_DELAY_MS = 4_000L
}
