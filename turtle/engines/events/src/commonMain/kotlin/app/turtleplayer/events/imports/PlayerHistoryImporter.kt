package app.turtleplayer.events.imports

import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface

/**
 * One row of the app's own play history from before Turtle recorded plays: Metrolist's event table,
 * including plays synced from the YouTube Music account. [playedMs] is null when only "played at"
 * is known (synced history).
 */
data class PastPlay(val track: TrackMeta, val atMs: Long, val playedMs: Long?)

/**
 * Imports [PastPlay]s once, so a listener upgrading from Metrolist starts with their history
 * instead of a cold engine. Metrolist keeps recording its own history beside ours, so only plays
 * before [ListeningSessionTracker]'s first recorded play count (pass it as `before`); later ones
 * would be counted twice. Re-running replaces the previous import, all or nothing; a run with
 * nothing to import leaves it as it was.
 *
 * Without a known play time a row counts as an ordinary listen (heard, not finished, not skipped);
 * with one, as finished at 80% of the song, otherwise stopped.
 */
class PlayerHistoryImporter(private val store: PlayEventStore, private val time: LocalTimeResolver) {
    /**
     * Stores [plays] before [before] at once when that is safe (see [ImportPreview.canCommit] and
     * [ImportPreview.shrinksHistory]); otherwise writes nothing and reports `committed = false`.
     */
    suspend fun import(plays: List<PastPlay>, before: Long): ImportReport = store.commitIfSafe(stage(plays, before))

    /** Sorts and counts [plays] without writing anything; plays at or after [before] are excluded. */
    suspend fun stage(plays: List<PastPlay>, before: Long): StagedImport {
        val tally = ImportTally().apply { filesRead = 1 }
        val events = plays.mapNotNull { p ->
            if (p.atMs >= before || p.atMs <= 0) {
                tally.excluded++
                return@mapNotNull null
            }
            val duration = p.track.durationMs?.takeIf { it > 0 }
            val played = p.playedMs?.coerceAtLeast(0)?.let { ms -> duration?.let { ms.coerceAtMost(it) } ?: ms }
            val finished = played != null && duration != null && played >= duration * FINISHED_SHARE
            PlayEvent(
                track = p.track,
                sessionId = "",
                position = 0,
                surface = Surface.UNKNOWN,
                startReason = StartReason.UNKNOWN,
                endReason = if (finished) EndReason.COMPLETED else if (played == null) EndReason.UNKNOWN else EndReason.STOPPED,
                startedAtMs = p.atMs,
                msPlayed = played ?: PlayEvent.LISTEN_MS,
                durationMs = duration,
                source = EventSource.METROLIST_IMPORT,
            )
        }
        return stageImport(EventSource.METROLIST_IMPORT, events, tally, store, time)
    }

    private companion object {
        const val FINISHED_SHARE = 0.8
    }
}
