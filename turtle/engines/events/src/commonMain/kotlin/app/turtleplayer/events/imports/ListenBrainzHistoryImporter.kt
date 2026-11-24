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

/**
 * Imports a ListenBrainz listens export (JSON array or JSON Lines of listens, as produced by the
 * "Export listens" page or the `user/{name}/listens` API). Last.fm history reaches us the same way
 * once the bridge pages it from the Last.fm API into this listen shape.
 *
 * Scrobbles only record that a track was listened to (normally past half its length or 4 minutes),
 * so every row is a completed play; no skips are known. Importing is [stage], a preview, then an
 * explicit [commit]: a wrong, empty, broken or canceled import leaves stored history as it was.
 */
class ListenBrainzHistoryImporter(
    private val store: PlayEventStore,
    private val time: LocalTimeResolver = LocalTimeResolver.fixedOffset(),
    private val source: EventSource = EventSource.LISTENBRAINZ_IMPORT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    data class Listen(
        @SerialName("listened_at") val listenedAt: Long? = null,
        @SerialName("track_metadata") val metadata: Metadata? = null,
    )

    @Serializable
    data class Metadata(
        @SerialName("artist_name") val artist: String? = null,
        @SerialName("track_name") val track: String? = null,
        @SerialName("release_name") val release: String? = null,
        @SerialName("additional_info") val info: AdditionalInfo? = null,
        @SerialName("mbid_mapping") val mapping: MbidMapping? = null,
    )

    @Serializable
    data class AdditionalInfo(
        val isrc: String? = null,
        @SerialName("recording_mbid") val recordingMbid: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val duration: Long? = null,
    )

    @Serializable
    data class MbidMapping(@SerialName("recording_mbid") val recordingMbid: String? = null)

    /**
     * Reads, dedupes and counts [files] without writing anything; [commit] the result to store it.
     * Only `.json` and `.jsonl` files are read. A JSON array that does not decode, or JSON Lines text
     * without a single decodable line, fails the import, so a wrong file never replaces history.
     */
    suspend fun stage(files: List<ExportFile>, limits: ImportLimits = ImportLimits()): StagedImport {
        val tally = ImportTally()
        val plays = ArrayList<PlayEvent>()
        val seen = HashSet<Pair<Long, TrackKey>>()
        for (file in files) {
            val base = file.baseName
            if (!base.endsWith(".json") && !base.endsWith(".jsonl")) {
                tally.filesSkipped += base
                continue
            }
            val text = tally.read(file, limits) ?: continue
            val parsed = parse(text)
            if (parsed == null) {
                tally.fail(base, FileProblem.UNREADABLE)
                continue
            }
            if (!parsed.isArray && parsed.listens.isEmpty()) {
                // Lines that are not JSON: a wrong file, which must not pass for an empty one. Blank
                // files and JSON without a listen (an export's user.json or feedback) are not history.
                if (parsed.rejectedLines > 0) {
                    tally.rejected += parsed.rejectedLines
                    tally.fail(base, FileProblem.UNREADABLE)
                } else {
                    tally.filesSkipped += base
                }
                continue
            }
            tally.rejected += parsed.rejectedLines + parsed.otherLines
            if (!tally.admitRows(base, parsed.listens.size, limits)) continue
            for (l in parsed.listens) {
                val at = l.listenedAt
                if (at == null) {
                    tally.rejected++
                    continue
                }
                val e = l.toEvent()
                when {
                    e == null -> tally.unresolved++
                    !seen.add(at to e.trackKey) -> tally.duplicates++
                    else -> plays += e
                }
            }
        }
        return stageImport(source, plays, tally, store, time)
    }

    /**
     * Stages [files] and stores them at once only when that is safe: every file was read and the
     * import does not start later than the stored one. Otherwise nothing is written and the report
     * says `committed = false`; use [stage], show the preview, then [commit].
     */
    suspend fun import(files: List<ExportFile>, limits: ImportLimits = ImportLimits()): ImportReport =
        store.commitIfSafe(stage(files, limits))

    /**
     * [rejectedLines] are not JSON; [otherLines] are JSON objects without a listen's time or track
     * (feedback, user data).
     */
    private class Parsed(val listens: List<Listen>, val rejectedLines: Int, val otherLines: Int, val isArray: Boolean)

    /** The listens in [text], or null when it is a JSON array that does not decode. */
    private fun parse(text: String): Parsed? {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            return runCatching { json.decodeFromString<List<Listen>>(trimmed) }.getOrNull()?.let { Parsed(it, 0, 0, isArray = true) }
        }
        // One JSON object over several lines (a pretty-printed user.json) is one record, not broken lines.
        val lines =
            if (trimmed.startsWith("{") && runCatching { json.parseToJsonElement(trimmed) }.isSuccess) {
                sequenceOf(trimmed)
            } else {
                trimmed.lineSequence().filter { it.isNotBlank() }
            }
        var rejected = 0
        var other = 0
        val listens = ArrayList<Listen>()
        for (line in lines) {
            val listen = runCatching { json.decodeFromString<Listen>(line) }.getOrNull()
            when {
                listen == null -> rejected++
                listen.listenedAt == null && listen.metadata == null -> other++
                else -> listens += listen
            }
        }
        return Parsed(listens, rejected, other, isArray = false)
    }

    fun Listen.toEvent(): PlayEvent? {
        val at = listenedAt ?: return null
        val m = metadata ?: return null
        val artist = m.artist?.takeIf { it.isNotBlank() } ?: return null
        val title = m.track?.takeIf { it.isNotBlank() } ?: return null
        val durationMs = m.info?.durationMs ?: m.info?.duration?.let { it * 1000 }
        val mbid = m.mapping?.recordingMbid ?: m.info?.recordingMbid
        return PlayEvent(
            track = TrackMeta(
                key = TrackKey.best(isrc = m.info?.isrc, mbid = mbid, artist = artist, title = title),
                title = title,
                artists = listOf(artist),
                album = m.release,
                durationMs = durationMs,
                isrc = m.info?.isrc,
            ),
            sessionId = "",
            position = 0,
            surface = Surface.EXTERNAL,
            startReason = StartReason.UNKNOWN,
            endReason = EndReason.COMPLETED,
            startedAtMs = at * 1000,
            msPlayed = durationMs ?: PlayEvent.LISTEN_MS,
            durationMs = durationMs,
            source = source,
        )
    }
}
