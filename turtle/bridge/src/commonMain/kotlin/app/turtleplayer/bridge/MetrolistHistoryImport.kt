package app.turtleplayer.bridge

import app.turtleplayer.events.EventSource
import app.turtleplayer.events.imports.PastPlay
import app.turtleplayer.events.imports.PlayerHistoryImporter
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Brings the listening history from upstream's own database (plays from before Turtle, and plays
 * synced from the YouTube Music account) into our event log, once. Upstream keeps recording its
 * history beside ours, so only plays before our first recorded play are imported.
 */
class MetrolistHistoryImport(
    private val database: () -> MusicDatabase,
    private val store: SqlitePlayEventStore,
    private val importer: PlayerHistoryImporter,
) {
    /** True when history was imported now; false when done before or nothing to import. */
    @OptIn(ExperimentalTime::class)
    suspend fun runOnce(): Boolean {
        if (store.pref(PREF_DONE) != null) return false
        val before = store.firstStartedAt(EventSource.LOCAL) ?: Clock.System.now().toEpochMilliseconds()
        val zone = TimeZone.currentSystemDefault()
        val rows = database().dao().events().first()
        val plays = rows.mapNotNull { row ->
            val song = row.song.toSongItem().takeIf { it.podcastId == null } ?: return@mapNotNull null
            PastPlay(
                track = song.toTrackMeta(),
                atMs = row.event.timestamp.toInstant(zone).toEpochMilliseconds(),
                playedMs = row.event.playTime.takeIf { row.event.playTimeKnown && it > 0 },
            )
        }
        val report = importer.import(plays, before)
        store.setPref(PREF_DONE, before.toString())
        TurtleLog.info("history: imported ${report.plays} earlier plays (${report.distinctTracks} songs)")
        return report.plays > 0
    }

    private companion object {
        const val PREF_DONE = "metrolist_history_imported_before"
    }
}
