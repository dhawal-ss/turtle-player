package app.turtleplayer.events.imports

import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore

/** One file from a user's data export, already unzipped by the platform layer. */
class ExportFile(val name: String, val text: String)

data class ImportReport(
    val source: EventSource,
    val filesRead: Int,
    val filesSkipped: List<String>,
    val plays: Int,
    /** Podcast episodes, audiobooks and rows without track names. */
    val rowsIgnored: Int,
    val malformedRows: Int,
    val firstPlayMs: Long?,
    val lastPlayMs: Long?,
    val distinctTracks: Int,
    val distinctArtists: Int,
)


/**
 * Shared post-processing for every importer: sorts plays, rebuilds listening sessions from time
 * gaps (exports have no session IDs), fills local-time context, and replaces any previous import
 * from the same source so re-importing a newer export never double counts.
 */
internal suspend fun finishImport(
    source: EventSource,
    plays: List<PlayEvent>,
    filesRead: Int,
    filesSkipped: List<String>,
    rowsIgnored: Int,
    malformed: Int,
    store: PlayEventStore,
    time: LocalTimeResolver,
    sessionGapMs: Long = 30 * MINUTE_MS,
): ImportReport {
    val sorted = plays.sortedBy { it.startedAtMs }
    val withSessions = ArrayList<PlayEvent>(sorted.size)
    var sessionStart = Long.MIN_VALUE
    var lastEnd = Long.MIN_VALUE
    var position = 0
    for (e in sorted) {
        if (lastEnd == Long.MIN_VALUE || e.startedAtMs - lastEnd > sessionGapMs) {
            sessionStart = e.startedAtMs
            position = 0
        }
        val (hour, weekday) = time.hourAndWeekday(e.startedAtMs)
        withSessions += e.copy(
            sessionId = "${source.name.lowercase()}-$sessionStart",
            position = position++,
            context = e.context ?: PlayContext(hour, weekday),
        )
        lastEnd = maxOf(lastEnd, e.startedAtMs + e.msPlayed)
    }
    store.deleteSource(source)
    withSessions.chunked(2_000).forEach { store.appendAll(it) }
    return ImportReport(
        source = source,
        filesRead = filesRead,
        filesSkipped = filesSkipped,
        plays = withSessions.size,
        rowsIgnored = rowsIgnored,
        malformedRows = malformed,
        firstPlayMs = withSessions.firstOrNull()?.startedAtMs,
        lastPlayMs = withSessions.lastOrNull()?.startedAtMs,
        distinctTracks = withSessions.mapTo(HashSet()) { it.trackKey }.size,
        distinctArtists = withSessions.mapTo(HashSet()) { it.artistKey }.size,
    )
}
