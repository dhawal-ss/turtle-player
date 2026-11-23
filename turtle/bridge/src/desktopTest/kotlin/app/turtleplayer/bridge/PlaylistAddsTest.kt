package app.turtleplayer.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistAddsTest {
    private fun m(
        p: String,
        s: String,
    ) = Membership(p, s)

    private val settle = PlaylistAdds.SETTLE_MS

    @Test
    fun firstSnapshotIsOnlyTheBaseline() {
        val adds = PlaylistAdds()
        adds.observe(setOf(m("PL1", "a"), m("PL1", "b")), 0)
        assertEquals(emptyList(), adds.due(10 * settle))
        adds.observe(setOf(m("PL1", "a"), m("PL1", "b"), m("PL1", "c")), 1_000)
        assertEquals(emptyList(), adds.due(1_000 + settle - 1))
        assertEquals(listOf(m("PL1", "c")), adds.due(1_000 + settle))
        assertEquals(emptyList(), adds.due(1_000 + 2 * settle)) // returned once
    }

    @Test
    fun reordersRemovalsAndReaddsAreNotAdds() {
        val adds = PlaylistAdds().apply { observe(setOf(m("PL1", "a"), m("PL1", "b")), 0) }
        adds.observe(setOf(m("PL1", "b"), m("PL1", "a")), 1_000)
        adds.observe(setOf(m("PL1", "a")), 2_000)
        adds.observe(setOf(m("PL1", "a"), m("PL1", "b")), 3_000) // added back, or a resync: seen before
        assertEquals(emptyList(), adds.due(10 * settle))
    }

    @Test
    fun importsAndSyncsAreNotAdds() {
        val adds = PlaylistAdds().apply { observe(emptySet(), 0) }
        // A local import of 260 songs: 250 in one transaction, then 10.
        val local = (1..260).map { m("PLlocal", "l$it") }
        adds.observe(local.take(250).toSet(), 1_000)
        adds.observe(local.toSet(), 3_000)
        // A remote import: one song per transaction, a few seconds apart.
        var snapshot = local.toSet()
        for (i in 1..60) {
            snapshot = snapshot + m("PLremote", "r$i")
            adds.observe(snapshot, 3_000 + i * 2_000L)
        }
        assertEquals(emptyList(), adds.due(130_000 + settle))
        // The listener adds an album of 12 to their playlist: 12 adds.
        adds.observe(snapshot + (1..12).map { m("PL1", "t$it") }, 400_000)
        assertEquals(12, adds.due(400_000 + settle).size)
    }

    @Test
    fun systemPlaylistsNeverCount() {
        val adds = PlaylistAdds().apply { observe(emptySet(), 0) }
        adds.observe(setOf(m("LP_WEEKLY_MOST", "a"), m("LP_MONTHLY_MOST", "b"), m("LP_LIKED", "c")), 1_000)
        assertEquals(emptyList(), adds.due(10 * settle))
        assertTrue(PlaylistAdds.counts("PLabc123"))
        assertFalse(PlaylistAdds.counts("LP_DOWNLOADED"))
    }
}
