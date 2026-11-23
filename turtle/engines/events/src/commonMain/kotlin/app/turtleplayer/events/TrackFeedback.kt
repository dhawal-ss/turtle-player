package app.turtleplayer.events

import app.turtleplayer.core.TrackMeta

/** Something the listener did to a track outside a play. Stored by name: never rename. */
enum class TrackFeedbackKind { ADDED_TO_PLAYLIST, NOT_INTERESTED, UNDO_NOT_INTERESTED }

/** One non-play feedback event, kept beside the play log. [detail]: the playlist id for ADDED_TO_PLAYLIST. */
data class TrackFeedback(
    val id: Long = 0,
    val track: TrackMeta,
    val kind: TrackFeedbackKind,
    val atMs: Long,
    val detail: String? = null,
    val source: EventSource = EventSource.LOCAL,
)
