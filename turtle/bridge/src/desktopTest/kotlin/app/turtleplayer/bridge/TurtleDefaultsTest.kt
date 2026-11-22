package app.turtleplayer.bridge

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TurtleDefaultsTest {
    private val prefs = HashMap<String, String>()

    private suspend fun run(defaults: List<TurtleDefaults.Default>) =
        TurtleDefaults.applyOnce(defaults, { prefs[it] }, { k, v -> prefs[k] = v })

    @Test
    fun eachDefaultIsAppliedOnlyOnceSoTheListenersLaterChoiceStands() = runBlocking {
        var applied = 0
        val keepPlaying = TurtleDefaults.Default("d1") { applied++ }
        run(listOf(keepPlaying))
        run(listOf(keepPlaying))
        assertEquals(1, applied, "a default applied before is not applied again")
    }

    @Test
    fun aFailingDefaultIsSkippedAndRetriedNextTimeWithoutStoppingTheOthers() = runBlocking {
        var fail = true
        var later = 0
        val broken = TurtleDefaults.Default("broken") { if (fail) error("settings not ready") }
        val fine = TurtleDefaults.Default("fine") { later++ }
        run(listOf(broken, fine))
        assertEquals(1, later)
        assertEquals(null, prefs["broken"], "not remembered as applied")
        fail = false
        run(listOf(broken, fine))
        assertEquals("applied", prefs["broken"])
        assertEquals(1, later)
    }
}
