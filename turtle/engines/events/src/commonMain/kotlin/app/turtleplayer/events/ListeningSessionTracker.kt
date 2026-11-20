package app.turtleplayer.events

import app.turtleplayer.core.Clock
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackMeta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Low-level facts reported by the player through the bridge. Timestamps come from [Clock]. */
sealed interface PlaybackSignal {
    data class TrackStarted(
        val track: TrackMeta,
        val reason: StartReason,
        val surface: Surface,
        val surfaceDetail: String? = null,
        val durationMs: Long? = track.durationMs,
        val shuffle: Boolean? = null,
    ) : PlaybackSignal

    data class PlayingChanged(val playing: Boolean) : PlaybackSignal

    data class Seeked(val fromMs: Long, val toMs: Long) : PlaybackSignal

    data class DurationKnown(val durationMs: Long) : PlaybackSignal

    data class Feedback(val kind: FeedbackKind) : PlaybackSignal

    data class TrackEnded(val reason: EndReason) : PlaybackSignal

    data class ContextChanged(val device: DeviceType, val network: NetworkType) : PlaybackSignal
}

enum class FeedbackKind { LIKED, UNLIKED, ADDED_TO_PLAYLIST, LESS_LIKE_THIS }

/** Resolves local hour and ISO weekday for an epoch time; the bridge supplies the device time zone. */
fun interface LocalTimeResolver {
    fun hourAndWeekday(epochMs: Long): Pair<Int, Int>

    companion object {
        /** UTC with a fixed offset; adequate for imports where the zone is unknown. */
        fun fixedOffset(offsetMs: Long = 0): LocalTimeResolver = LocalTimeResolver { epochMs ->
            val local = epochMs + offsetMs
            val day = local.floorDiv(86_400_000L)
            val hour = (local.mod(86_400_000L) / 3_600_000L).toInt()
            // 1970-01-01 was a Thursday (ISO 4).
            val weekday = ((day + 3).mod(7L) + 1).toInt()
            hour to weekday
        }
    }
}

/**
 * Turns the player's signal stream into one [PlayEvent] per play.
 *
 * Plays are measured in wall-clock listening time (pauses excluded), so seeking forward does not
 * inflate `msPlayed`. A new session starts after [sessionGapMs] without playback. When a track
 * starts while another is still open, the previous one is closed with a reason inferred from how
 * the new one started (autoplay = completed, next button = skipped, and so on).
 */
class ListeningSessionTracker(
    private val store: PlayEventStore,
    private val clock: Clock = Clock.System,
    private val time: LocalTimeResolver = LocalTimeResolver.fixedOffset(),
    private val sessionGapMs: Long = 30 * MINUTE_MS,
) {
    private class Open(
        val start: PlaybackSignal.TrackStarted,
        val startedAtMs: Long,
        val position: Int,
        val sessionId: String,
        var durationMs: Long?,
        var playingSinceMs: Long? = null,
        var accumulatedMs: Long = 0,
        var seeks: Int = 0,
        var replayed: Boolean = false,
        var liked: Boolean = false,
        var addedToPlaylist: Boolean = false,
        var lessLikeThis: Boolean = false,
    ) {
        fun playedMs(now: Long): Long = accumulatedMs + (playingSinceMs?.let { now - it } ?: 0)
    }

    private val mutex = Mutex()
    private var open: Open? = null
    private var sessionId: String? = null
    private var sessionPosition = 0
    private var lastActivityMs = Long.MIN_VALUE
    private var lastTrackKey: String? = null
    private var device = DeviceType.UNKNOWN
    private var network = NetworkType.UNKNOWN

    suspend fun onSignal(signal: PlaybackSignal) {
        val finished: PlayEvent? = mutex.withLock { handle(signal, clock.nowMs()) }
        finished?.let { store.append(it) }
    }

    /** Closes the open play, e.g. when the app is shutting down. */
    suspend fun flush(reason: EndReason = EndReason.STOPPED) = onSignal(PlaybackSignal.TrackEnded(reason))

    private fun handle(signal: PlaybackSignal, now: Long): PlayEvent? {
        val current = open
        return when (signal) {
            is PlaybackSignal.TrackStarted -> {
                val closed = current?.let { close(it, inferEnd(it, signal.reason, now), now) }
                if (sessionId == null || now - lastActivityMs > sessionGapMs) {
                    sessionId = "s$now"
                    sessionPosition = 0
                    lastTrackKey = null
                }
                val o = Open(signal, now, sessionPosition++, sessionId!!, signal.durationMs, playingSinceMs = now)
                o.replayed = lastTrackKey == signal.track.key.value
                open = o
                lastActivityMs = now
                closed
            }
            is PlaybackSignal.PlayingChanged -> {
                if (current != null) {
                    if (signal.playing && current.playingSinceMs == null) {
                        current.playingSinceMs = now
                    } else if (!signal.playing && current.playingSinceMs != null) {
                        current.accumulatedMs += now - current.playingSinceMs!!
                        current.playingSinceMs = null
                    }
                }
                lastActivityMs = now
                null
            }
            is PlaybackSignal.Seeked -> {
                current?.let {
                    it.seeks++
                    // Jumping back to the start of a track that was mostly heard is a replay.
                    val dur = it.durationMs
                    if (signal.toMs < 5_000 && dur != null && signal.fromMs > dur / 2) it.replayed = true
                }
                null
            }
            is PlaybackSignal.DurationKnown -> {
                current?.durationMs = signal.durationMs
                null
            }
            is PlaybackSignal.Feedback -> {
                current?.let {
                    when (signal.kind) {
                        FeedbackKind.LIKED -> it.liked = true
                        FeedbackKind.UNLIKED -> it.liked = false
                        FeedbackKind.ADDED_TO_PLAYLIST -> it.addedToPlaylist = true
                        FeedbackKind.LESS_LIKE_THIS -> it.lessLikeThis = true
                    }
                }
                null
            }
            is PlaybackSignal.TrackEnded -> {
                lastActivityMs = now
                current?.let { close(it, signal.reason, now) }
            }
            is PlaybackSignal.ContextChanged -> {
                device = signal.device
                network = signal.network
                null
            }
        }
    }

    private fun inferEnd(o: Open, nextReason: StartReason, now: Long): EndReason = when (nextReason) {
        StartReason.AUTOPLAY -> EndReason.COMPLETED
        StartReason.USER_NEXT -> EndReason.SKIPPED
        StartReason.USER_PREVIOUS -> EndReason.SKIPPED_BACK
        else -> {
            val dur = o.durationMs
            val played = o.playedMs(now)
            if (dur != null && played >= dur * 0.9) EndReason.COMPLETED else EndReason.SKIPPED
        }
    }

    private fun close(o: Open, reason: EndReason, now: Long): PlayEvent {
        open = null
        lastTrackKey = o.start.track.key.value
        val (hour, weekday) = time.hourAndWeekday(o.startedAtMs)
        val played = o.playedMs(now).coerceAtLeast(0)
        return PlayEvent(
            track = o.start.track,
            sessionId = o.sessionId,
            position = o.position,
            surface = o.start.surface,
            startReason = o.start.reason,
            endReason = reason,
            startedAtMs = o.startedAtMs,
            msPlayed = o.durationMs?.let { played.coerceAtMost(it) } ?: played,
            durationMs = o.durationMs,
            seeks = o.seeks,
            replayed = o.replayed,
            shuffle = o.start.shuffle,
            liked = o.liked,
            addedToPlaylist = o.addedToPlaylist,
            lessLikeThis = o.lessLikeThis,
            context = PlayContext(hour, weekday, device, network),
            surfaceDetail = o.start.surfaceDetail,
        )
    }
}
