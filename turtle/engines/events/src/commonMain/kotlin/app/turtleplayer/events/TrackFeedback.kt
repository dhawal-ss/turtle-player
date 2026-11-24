package app.turtleplayer.events

import app.turtleplayer.core.TrackMeta

/**
 * Something the listener did to a track outside a play. Stored by name: never rename, only append.
 * [SAVED] is a like or a library add made in the app, recorded with its time for measurement only;
 * taste reads saves from the library itself, so the taste engine ignores it.
 */
enum class TrackFeedbackKind { ADDED_TO_PLAYLIST, NOT_INTERESTED, UNDO_NOT_INTERESTED, SAVED }

/**
 * One non-play feedback event, kept beside the play log. [detail]: the playlist id for
 * ADDED_TO_PLAYLIST; [SAVED_LIKED] or [SAVED_LIBRARY] for SAVED.
 */
data class TrackFeedback(
    val id: Long = 0,
    val track: TrackMeta,
    val kind: TrackFeedbackKind,
    val atMs: Long,
    val detail: String? = null,
    val source: EventSource = EventSource.LOCAL,
) {
    companion object {
        /** [TrackFeedbackKind.SAVED] detail: the listener liked the track. */
        const val SAVED_LIKED = "liked"

        /** [TrackFeedbackKind.SAVED] detail: the listener added the track to their library. */
        const val SAVED_LIBRARY = "library"
    }
}
