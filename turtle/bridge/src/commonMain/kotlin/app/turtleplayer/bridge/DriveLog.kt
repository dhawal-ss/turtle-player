package app.turtleplayer.bridge

import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/**
 * A playback flight recorder kept on the device: upstream's playback events, our own log lines and
 * the platform's signals (screen, charger, Bluetooth and audio devices, network, why the app last
 * exited). Problems that happen away from a computer, like music stopping in the car, can then be
 * read back afterwards. Nothing leaves the device; the file is capped and rotated.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object DriveLog {
    private val lines = Channel<String>(capacity = BUFFERED_LINES, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicBoolean(false)

    /** Records one line; never throws and never blocks. Lines before [start] are kept until it runs. */
    fun line(source: String, message: String) {
        lines.trySend("${Clock.System.now()} $source $message")
    }

    /** Starts writing (idempotent): upstream's playback events, platform signals, and every line. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        val sink = driveLogSink() ?: return
        scope.launch {
            val batch = ArrayList<String>()
            while (true) {
                batch += lines.receive()
                while (true) batch += lines.tryReceive().getOrNull() ?: break
                guarded { sink.append(batch) }
                batch.clear()
            }
        }
        scope.launch {
            // From the start of the buffer: the events of the service starting up matter too.
            var lastId = 0L
            var positionSamples = 0
            PlaybackEventLog.events.updates.collect { events ->
                for (event in events) {
                    if (event.id <= lastId) continue
                    lastId = event.id
                    // Upstream samples the audio position every 5 s; one in six is enough to see it move.
                    if (event.message.startsWith(POSITION_SAMPLE) && positionSamples++ % POSITION_SAMPLE_EVERY != 0) continue
                    lines.trySend(event.toLogLine().take(MAX_EVENT_CHARS))
                }
            }
        }
        guarded { startPlatformSignals(scope) }
        line("turtle", "drive log started")
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[Turtle] ERROR drive log: ${e.message}")
        }
    }

    private const val BUFFERED_LINES = 2_000
    private const val POSITION_SAMPLE = "Android audio position sample"
    private const val POSITION_SAMPLE_EVERY = 6
    // Stream resolutions carry kilobytes of details; the head of each event is what explains a stop.
    private const val MAX_EVENT_CHARS = 1_500
}

/** Where [DriveLog] writes; null where there is nothing worth keeping (desktop has its own logs). */
internal interface DriveLogSink {
    fun append(lines: List<String>)
}

internal expect fun driveLogSink(): DriveLogSink?

/** Registers for the platform's screen, power, audio device and network signals. */
internal expect fun startPlatformSignals(scope: CoroutineScope)
