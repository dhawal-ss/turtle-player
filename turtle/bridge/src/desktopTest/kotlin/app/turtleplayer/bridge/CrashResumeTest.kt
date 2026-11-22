package app.turtleplayer.bridge

import app.turtleplayer.bridge.CrashResume.Cause
import app.turtleplayer.bridge.CrashResume.Exit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashResumeTest {
    private val killedAt = 1_000_000L
    private val playingJustBefore = killedAt - 3_000L

    private fun resume(cause: Cause, mark: Long? = playingJustBefore, restartAfterMs: Long = 2_000L) =
        CrashResume.shouldResume(Exit(cause, killedAt), mark, killedAt + restartAfterMs)

    @Test
    fun resumesWhenThePhoneEndedTheAppMidSongAndRestartedItRightAway() {
        assertTrue(resume(Cause.SYSTEM_KILL), "OnePlus o-kill or low memory")
        assertTrue(resume(Cause.CRASH), "a crash")
    }

    @Test
    fun neverResumesAfterThePauseButton() {
        assertFalse(resume(Cause.SYSTEM_KILL, mark = 0L), "paused: the mark is cleared")
        assertFalse(resume(Cause.SYSTEM_KILL, mark = null), "never played")
        assertFalse(resume(Cause.SYSTEM_KILL, mark = killedAt - 60_000L), "last playing a minute before the kill")
    }

    @Test
    fun neverResumesWhenTheListenerClosedOrReopenedLater() {
        assertFalse(resume(Cause.USER), "force stop")
        assertFalse(resume(Cause.UPDATE), "app update")
        assertFalse(resume(Cause.OTHER), "the app exiting itself")
        assertFalse(resume(Cause.SYSTEM_KILL, restartAfterMs = 5 * 60_000L), "opened again minutes later")
        assertFalse(CrashResume.shouldResume(null, playingJustBefore, killedAt), "no exit record")
    }
}
