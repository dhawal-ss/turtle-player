package app.turtleplayer.events.imports

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.LocalTimeResolver
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Imports Spotify's official data exports, which the user requests and downloads themselves:
 *
 * - **Extended Streaming History** (`Streaming_History_Audio_*.json`, older `endsong_*.json`):
 *   every play with ms played, start and end reasons, shuffle and skip flags. Arrives 5–30 days
 *   after the request. This is the cold-start fuel.
 * - **Account data** (`StreamingHistory_music_*.json`, older `StreamingHistory*.json`): last year
 *   only, with just end time, names and ms played. Accepted so users get something while waiting.
 *
 * We never scrape Spotify; only files the user hands us are read.
 */
class SpotifyHistoryImporter(
    private val store: PlayEventStore,
    private val time: LocalTimeResolver = LocalTimeResolver.fixedOffset(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    internal data class ExtendedRow(
        val ts: String? = null,
        @SerialName("ms_played") val msPlayed: Long = 0,
        @SerialName("master_metadata_track_name") val track: String? = null,
        @SerialName("master_metadata_album_artist_name") val artist: String? = null,
        @SerialName("master_metadata_album_album_name") val album: String? = null,
        @SerialName("spotify_track_uri") val uri: String? = null,
        @SerialName("episode_name") val episode: String? = null,
        @SerialName("reason_start") val reasonStart: String? = null,
        @SerialName("reason_end") val reasonEnd: String? = null,
        val shuffle: Boolean? = null,
        val skipped: Boolean? = null,
        val platform: String? = null,
    )

    @Serializable
    internal data class AccountRow(
        val endTime: String? = null,
        val artistName: String? = null,
        val trackName: String? = null,
        val msPlayed: Long = 0,
    )

    suspend fun import(files: List<ExportFile>): ImportReport {
        val plays = ArrayList<PlayEvent>()
        val skipped = mutableListOf<String>()
        var ignored = 0
        var malformed = 0
        var read = 0
        for (file in files) {
            val base = file.name.substringAfterLast('/').substringAfterLast('\\')
            val kind = when {
                base.startsWith("Streaming_History_Audio_") || base.startsWith("endsong") -> Kind.EXTENDED
                base.startsWith("StreamingHistory") && !base.contains("podcast", ignoreCase = true) -> Kind.ACCOUNT
                else -> null
            }
            if (kind == null || !base.endsWith(".json")) {
                skipped += base
                continue
            }
            read++
            when (kind) {
                Kind.EXTENDED -> {
                    val rows = runCatching { json.decodeFromString<List<ExtendedRow>>(file.text) }.getOrElse {
                        malformed++
                        continue
                    }
                    for (row in rows) {
                        when (val e = row.toEvent()) {
                            null -> if (row.ts == null) malformed++ else ignored++
                            else -> plays += e
                        }
                    }
                }
                Kind.ACCOUNT -> {
                    val rows = runCatching { json.decodeFromString<List<AccountRow>>(file.text) }.getOrElse {
                        malformed++
                        continue
                    }
                    for (row in rows) {
                        when (val e = row.toEvent()) {
                            null -> if (row.endTime == null) malformed++ else ignored++
                            else -> plays += e
                        }
                    }
                }
            }
        }
        return finishImport(EventSource.SPOTIFY_IMPORT, plays, read, skipped, ignored, malformed, store, time)
    }

    private enum class Kind { EXTENDED, ACCOUNT }

    @OptIn(ExperimentalTime::class)
    private fun ExtendedRow.toEvent(): PlayEvent? {
        val endMs = ts?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
        val title = track?.takeIf { it.isNotBlank() } ?: return null
        val artistName = artist?.takeIf { it.isNotBlank() } ?: return null
        if (episode != null) return null
        val end = mapEnd(reasonEnd, skipped)
        return PlayEvent(
            track = TrackMeta(
                // Spotify exports carry no ISRC; the name key merges with YouTube plays of the same song.
                key = TrackKey.fromMeta(artistName, title),
                title = title,
                artists = listOf(artistName),
                album = album,
            ),
            sessionId = "",
            position = 0,
            surface = Surface.EXTERNAL,
            startReason = mapStart(reasonStart),
            endReason = end,
            startedAtMs = endMs - msPlayed,
            msPlayed = msPlayed,
            durationMs = null,
            shuffle = shuffle,
            source = EventSource.SPOTIFY_IMPORT,
            surfaceDetail = uri,
        )
    }

    private fun AccountRow.toEvent(): PlayEvent? {
        val endMs = endTime?.let(::parseAccountTime) ?: return null
        val title = trackName?.takeIf { it.isNotBlank() && it != "Unknown Track" } ?: return null
        val artistName = artistName?.takeIf { it.isNotBlank() && it != "Unknown Artist" } ?: return null
        return PlayEvent(
            track = TrackMeta(TrackKey.fromMeta(artistName, title), title, listOf(artistName)),
            sessionId = "",
            position = 0,
            surface = Surface.EXTERNAL,
            startReason = StartReason.UNKNOWN,
            endReason = EndReason.UNKNOWN,
            startedAtMs = endMs - msPlayed,
            msPlayed = msPlayed,
            durationMs = null,
            source = EventSource.SPOTIFY_IMPORT,
        )
    }

    internal companion object {
        fun mapStart(reason: String?): StartReason = when (reason) {
            "trackdone" -> StartReason.AUTOPLAY
            "clickrow", "clickside", "playbtn" -> StartReason.TAPPED
            "fwdbtn" -> StartReason.USER_NEXT
            "backbtn" -> StartReason.USER_PREVIOUS
            "appload" -> StartReason.RESUMED
            "remote" -> StartReason.REMOTE
            else -> StartReason.UNKNOWN
        }

        fun mapEnd(reason: String?, skipped: Boolean?): EndReason = when (reason) {
            "trackdone" -> EndReason.COMPLETED
            "fwdbtn" -> EndReason.SKIPPED
            "backbtn" -> EndReason.SKIPPED_BACK
            "endplay", "logout", "remote" -> if (skipped == true) EndReason.SKIPPED else EndReason.STOPPED
            "trackerror", "unexpected-exit", "unexpected-exit-while-paused" -> EndReason.ERROR
            else -> if (skipped == true) EndReason.SKIPPED else EndReason.UNKNOWN
        }

        /** Account-data format: "2024-03-01 21:14" in UTC. */
        @OptIn(ExperimentalTime::class)
        fun parseAccountTime(text: String): Long? {
            val iso = text.trim().replace(' ', 'T').let { if (it.length == 16) "$it:00Z" else if (it.endsWith("Z")) it else "${it}Z" }
            return runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
        }
    }
}
