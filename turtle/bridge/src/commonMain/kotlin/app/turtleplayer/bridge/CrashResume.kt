package app.turtleplayer.bridge

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Picks the music back up when the phone, not the listener, ended the app mid-song: a crash, or the
 * system killing it (OnePlus kills apps swiped from recents, and frees memory in the background).
 * The listener's own stop, an update, or opening the app again later never starts playback.
 */
internal object CrashResume {
    /** How the platform says the previous process ended. */
    enum class Cause { CRASH, SYSTEM_KILL, USER, UPDATE, OTHER }

    data class Exit(val cause: Cause, val atMs: Long)

    /** Resume only when Android restarted us right after it ended a process that was playing. */
    fun shouldResume(exit: Exit?, playingMarkMs: Long?, nowMs: Long): Boolean {
        if (exit == null || exit.cause != Cause.CRASH && exit.cause != Cause.SYSTEM_KILL) return false
        if (nowMs - exit.atMs !in 0..RESTART_WINDOW_MS) return false
        // The mark is refreshed every MARK_EVERY_MS while playing and cleared on pause.
        val mark = playingMarkMs ?: return false
        return mark > 0 && exit.atMs - mark in -MARK_SLACK_MS..(MARK_EVERY_MS + MARK_SLACK_MS)
    }

    /** Restarts after a kill come within seconds; a later launch is the listener opening the app. */
    const val RESTART_WINDOW_MS = 30_000L
    const val MARK_EVERY_MS = 5_000L
    private const val MARK_SLACK_MS = 2_000L
    const val MARK_KEY = "playing_mark"
    private const val SONG_RESTORE_WAIT_MS = 15_000L
    private const val SETTLE_MS = 1_000L

    /**
     * Run once per process after the player is attached: decides from the previous exit and the
     * mark it left, resumes if so, then keeps the mark for the next time. Never throws.
     */
    suspend fun run(store: SqlitePlayEventStore, currentSong: StateFlow<SongItem?>, isPlaying: StateFlow<Boolean>) {
        try {
            val exit = previousExit()
            val mark = store.pref(MARK_KEY)?.toLongOrNull()
            val now = Clock.System.now().toEpochMilliseconds()
            if (shouldResume(exit, mark, now)) {
                TurtleLog.info("auto-resume: the app was ended by ${exit?.cause} while playing; resuming")
                // Upstream restores the saved queue first; play only once a song is back.
                withTimeoutOrNull(SONG_RESTORE_WAIT_MS) { currentSong.first { it != null } }
                delay(SETTLE_MS)
                if (!isPlaying.value) {
                    if (!resumeOwnPlayback()) TurtleLog.info("auto-resume: could not reach the player")
                }
            } else if (exit != null && mark != null && mark > 0) {
                TurtleLog.info("auto-resume: not resuming (${exit.cause}, ${(now - exit.atMs) / 1000}s ago)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("auto-resume failed", e)
        }
        keepMark(store, isPlaying)
    }

    private suspend fun keepMark(store: SqlitePlayEventStore, isPlaying: StateFlow<Boolean>) {
        isPlaying.collectLatest { playing ->
            try {
                if (!playing) {
                    store.setPref(MARK_KEY, "0")
                    return@collectLatest
                }
                while (true) {
                    store.setPref(MARK_KEY, Clock.System.now().toEpochMilliseconds().toString())
                    delay(MARK_EVERY_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TurtleLog.error("auto-resume: could not save the playing mark", e)
            }
        }
    }
}

/** How the previous process of this app ended; null where the platform cannot tell. */
internal expect fun previousExit(): CrashResume.Exit?

/** Presses play on our own media session, as a car or headset would. False when unreachable. */
internal expect suspend fun resumeOwnPlayback(): Boolean
