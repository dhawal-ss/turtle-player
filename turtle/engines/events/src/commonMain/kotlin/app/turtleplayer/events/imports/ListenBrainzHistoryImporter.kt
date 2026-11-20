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
 * so every row is a completed play; no skips are known.
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

    suspend fun import(files: List<ExportFile>): ImportReport {
        val plays = ArrayList<PlayEvent>()
        var ignored = 0
        var malformed = 0
        for (file in files) {
            val listens = parse(file.text)
            if (listens == null) {
                malformed++
                continue
            }
            for (l in listens) {
                val e = l.toEvent()
                if (e == null) ignored++ else plays += e
            }
        }
        return finishImport(source, plays, files.size, emptyList(), ignored, malformed, store, time)
    }

    private fun parse(text: String): List<Listen>? {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("[")) {
            runCatching { json.decodeFromString<List<Listen>>(trimmed) }.getOrNull()
        } else {
            trimmed.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { json.decodeFromString<Listen>(line) }.getOrNull()
            }.toList()
        }
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
