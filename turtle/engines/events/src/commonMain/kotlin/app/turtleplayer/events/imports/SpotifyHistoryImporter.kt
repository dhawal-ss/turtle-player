package app.turtleplayer.events.imports

import app.turtleplayer.core.MINUTE_MS
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
 * We never scrape Spotify; only files the user hands us are read. Importing is [stage], a preview,
 * then an explicit [commit]: a wrong, empty, broken or canceled import leaves stored history exactly
 * as it was. Plays from private sessions are left out unless the listener asks for them
 * ([SpotifyImportOptions]); identifiers taste does not use (URIs, platform, country, IP address)
 * are never parsed.
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
        @SerialName("episode_name") val episode: String? = null,
        @SerialName("spotify_episode_uri") val episodeUri: String? = null,
        @SerialName("audiobook_title") val audiobook: String? = null,
        @SerialName("audiobook_uri") val audiobookUri: String? = null,
        @SerialName("reason_start") val reasonStart: String? = null,
        @SerialName("reason_end") val reasonEnd: String? = null,
        val shuffle: Boolean? = null,
        val skipped: Boolean? = null,
        /** Absent in some older exports: read as not private. */
        @SerialName("incognito_mode") val incognito: Boolean? = null,
        // Deliberately not read: track URIs, platform, country, IP address, offline flags and any
        // other identifier. Taste needs none of them, so they are never parsed or stored.
    )

    @Serializable
    internal data class AccountRow(
        val endTime: String? = null,
        val artistName: String? = null,
        val trackName: String? = null,
        val msPlayed: Long = 0,
    )

    /** Reads, dedupes and counts [files] without writing anything; [commit] the result to store it. */
    suspend fun stage(
        files: List<ExportFile>,
        options: SpotifyImportOptions = SpotifyImportOptions(),
        limits: ImportLimits = ImportLimits(),
    ): StagedImport {
        val tally = ImportTally()
        val plays = ArrayList<PlayEvent>()
        val accountRows = ArrayList<AccountRow>()
        val extendedSeen = HashSet<RowKey>()
        var extendedFirstEnd = Long.MAX_VALUE
        var extendedLastEnd = Long.MIN_VALUE
        for (file in files) {
            val base = file.baseName
            val kind = when {
                !base.endsWith(".json") -> null
                base.startsWith("Streaming_History_Audio_") || base.startsWith("endsong") -> Kind.EXTENDED
                base.startsWith("StreamingHistory") && !base.contains("podcast", ignoreCase = true) -> Kind.ACCOUNT
                else -> null
            }
            if (kind == null) {
                tally.filesSkipped += base
                continue
            }
            val text = tally.read(file, limits) ?: continue
            when (kind) {
                Kind.EXTENDED -> {
                    val rows = runCatching { json.decodeFromString<List<ExtendedRow>>(text) }.getOrNull()
                    if (rows == null) {
                        tally.fail(base, FileProblem.UNREADABLE)
                        continue
                    }
                    if (!tally.admitRows(base, rows.size, limits)) continue
                    for (row in rows) {
                        val endMs = row.ts?.let(::parseInstant)
                        if (endMs == null) {
                            tally.rejected++
                            continue
                        }
                        extendedFirstEnd = minOf(extendedFirstEnd, endMs)
                        extendedLastEnd = maxOf(extendedLastEnd, endMs)
                        // The same export twice, or overlapping exports: each play counts once.
                        val name = row.track ?: row.episode ?: row.audiobook
                        if (!extendedSeen.add(RowKey(name, row.artist, endMs, row.msPlayed))) {
                            tally.duplicates++
                            continue
                        }
                        val nonMusic = row.episode != null || row.episodeUri != null || row.audiobook != null || row.audiobookUri != null
                        when {
                            nonMusic -> tally.nonMusic++
                            row.incognito == true && !options.includePrivateSessions -> tally.privateSession++
                            else -> {
                                val event = row.toEvent(endMs)
                                if (event == null) tally.unresolved++ else plays += event
                            }
                        }
                    }
                }
                Kind.ACCOUNT -> {
                    val rows = runCatching { json.decodeFromString<List<AccountRow>>(text) }.getOrNull()
                    if (rows == null) {
                        tally.fail(base, FileProblem.UNREADABLE)
                        continue
                    }
                    if (!tally.admitRows(base, rows.size, limits)) continue
                    accountRows += rows
                }
            }
        }
        // After every Extended file: Account rows the Extended history already covers are duplicates,
        // so its richer rows (reasons, shuffle, skips) win. Account times have minute precision.
        val accountSeen = HashSet<RowKey>()
        for (row in accountRows) {
            val endMs = row.endTime?.let(::parseAccountTime)
            when {
                endMs == null -> tally.rejected++
                endMs >= extendedFirstEnd - MINUTE_MS && endMs <= extendedLastEnd + MINUTE_MS -> tally.duplicates++
                !accountSeen.add(RowKey(row.trackName, row.artistName, endMs / MINUTE_MS, row.msPlayed)) -> tally.duplicates++
                else -> {
                    val event = row.toEvent(endMs)
                    if (event == null) tally.unresolved++ else plays += event
                }
            }
        }
        return stageImport(EventSource.SPOTIFY_IMPORT, plays, tally, store, time)
    }

    /**
     * Stages [files] and stores them at once only when that is safe: every history file was read and
     * the import does not start later than the stored one. Otherwise nothing is written and the
     * report says `committed = false`; use [stage], show the preview, then [commit].
     */
    suspend fun import(
        files: List<ExportFile>,
        options: SpotifyImportOptions = SpotifyImportOptions(),
        limits: ImportLimits = ImportLimits(),
    ): ImportReport = store.commitIfSafe(stage(files, options, limits))

    private enum class Kind { EXTENDED, ACCOUNT }

    private data class RowKey(val name: String?, val artist: String?, val at: Long, val msPlayed: Long)

    private fun ExtendedRow.toEvent(endMs: Long): PlayEvent? {
        val title = track?.takeIf { it.isNotBlank() } ?: return null
        val artistName = artist?.takeIf { it.isNotBlank() } ?: return null
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
            endReason = mapEnd(reasonEnd, skipped),
            startedAtMs = endMs - msPlayed,
            msPlayed = msPlayed,
            durationMs = null,
            shuffle = shuffle,
            source = EventSource.SPOTIFY_IMPORT,
        )
    }

    private fun AccountRow.toEvent(endMs: Long): PlayEvent? {
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

        /** Extended format: an ISO instant ("2024-03-01T20:03:30Z"), the end of the play. */
        @OptIn(ExperimentalTime::class)
        fun parseInstant(text: String): Long? = runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()

        /** Account-data format: "2024-03-01 21:14" in UTC. */
        @OptIn(ExperimentalTime::class)
        fun parseAccountTime(text: String): Long? {
            val iso = text.trim().replace(' ', 'T').let { if (it.length == 16) "$it:00Z" else if (it.endsWith("Z")) it else "${it}Z" }
            return runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
        }
    }
}

/** Choices the listener makes before a Spotify import. */
data class SpotifyImportOptions(
    /** Include plays Spotify recorded during a private session (left out by default). */
    val includePrivateSessions: Boolean = false,
)
