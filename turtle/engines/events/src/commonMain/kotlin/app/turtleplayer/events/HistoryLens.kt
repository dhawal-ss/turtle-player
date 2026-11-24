package app.turtleplayer.events

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey

/**
 * How taste reads stored history, without ever changing or deleting it. Turning an option off restores
 * the previous reading exactly.
 *
 * - [repairLegacySkips]: a LOCAL play labelled before label versioning ([PlayEvent.labelVersion] null)
 *   that ended SKIPPED, and whose next play started for a reason the player could not classify (or is
 *   missing), is read as UNKNOWN. Older builds labelled a replaced queue or an unclassified change as a
 *   skip of the track before it; today's tracker records such a change as UNKNOWN.
 * - [ignoreThroughId]: plays with a row id up to and including this one are left out (a taste reset).
 *   Plays added later count, including imports made after the reset.
 */
data class HistoryLens(
    val repairLegacySkips: Boolean = false,
    val ignoreThroughId: Long? = null,
) {
    val isDefault: Boolean get() = this == HistoryLens()

    /**
     * The first [count] of [events] (oldest first, as [PlayEventStore.since] returns them) as taste
     * should read them. Rows after [count] are only looked at, as successors of the rows before.
     */
    fun read(events: List<PlayEvent>, count: Int = events.size): List<PlayEvent> {
        val n = count.coerceIn(0, events.size)
        if (isDefault) return if (n == events.size) events else events.subList(0, n).toList()
        val suspect = if (repairLegacySkips) suspectLegacySkips(events) else emptySet()
        val out = ArrayList<PlayEvent>(n)
        for (i in 0 until n) {
            val e = events[i]
            if (ignoreThroughId != null && e.id <= ignoreThroughId) continue
            out += if (i in suspect) e.copy(endReason = EndReason.UNKNOWN) else e
        }
        return out
    }

    /** How many of [events] [read] reads as neutral instead of a skip; 0 with [repairLegacySkips] off. */
    fun repairedCount(events: List<PlayEvent>): Int =
        if (!repairLegacySkips) {
            0
        } else {
            suspectLegacySkips(events).count { ignoreThroughId == null || events[it].id > ignoreThroughId }
        }

    /** A compact stored form; [decode] reads it back. */
    fun encode(): String = "repair=${if (repairLegacySkips) 1 else 0};ignoreThrough=${ignoreThroughId ?: ""}"

    companion object {
        /** The lens stored as [encode]; the default lens for null or unreadable text. */
        fun decode(text: String?): HistoryLens {
            if (text.isNullOrBlank()) return HistoryLens()
            val fields = text.split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            return HistoryLens(
                repairLegacySkips = fields["repair"] == "1",
                ignoreThroughId = fields["ignoreThrough"]?.toLongOrNull(),
            )
        }

        /** How many of [events] a lens with [repairLegacySkips] on would read as neutral. */
        fun legacySkipCount(events: List<PlayEvent>): Int = suspectLegacySkips(events).size

        /** Indices into [events] of legacy SKIPPED plays that are likely mislabelled (see [HistoryLens]). */
        private fun suspectLegacySkips(events: List<PlayEvent>): Set<Int> {
            val local = events.indices.filter { events[it].source == EventSource.LOCAL }
            if (local.isEmpty()) return emptySet()
            val bySlot = HashMap<Pair<String, Int>, Int>(local.size * 2)
            for (i in local) bySlot.putIfAbsent(events[i].sessionId to events[i].position, i)
            // LOCAL plays in (startedAtMs, id) order, for successors that opened a new session.
            val ordered = local.sortedWith(compareBy({ events[it].startedAtMs }, { events[it].id }))
            val rank = HashMap<Int, Int>(ordered.size * 2).also { m -> ordered.forEachIndexed { r, i -> m[i] = r } }
            val out = HashSet<Int>()
            for (i in local) {
                val e = events[i]
                if (e.labelVersion != null || e.endReason != EndReason.SKIPPED) continue
                val next = bySlot[e.sessionId to e.position + 1]
                    ?: ordered.getOrNull(rank.getValue(i) + 1)?.takeIf { events[it].position == 0 }
                if (next == null || events[next].startReason == StartReason.UNKNOWN) out += i
            }
            return out
        }
    }
}

/**
 * [PlayEventStore] that reads history through a [HistoryLens] in [since], the read every taste, search
 * and Home signal uses. Writes, [newEvents] (always labelled now) and feedback pass straight through;
 * [forTrack] and [forArtist] read the stored rows as they are.
 */
class LensedPlayEventStore(
    private val inner: PlayEventStore,
    private val lens: suspend () -> HistoryLens,
) : PlayEventStore by inner {
    override suspend fun since(sinceMs: Long, limit: Int): List<PlayEvent> {
        val l = lens()
        if (l.isDefault) return inner.since(sinceMs, limit)
        if (limit == Int.MAX_VALUE) return l.read(inner.since(sinceMs))
        // A reset leaves rows out by id, not time, so the first [limit] stored rows may all be left out
        // while later ones count: read the whole range, then take the first [limit] that count.
        if (l.ignoreThroughId != null) return l.read(inner.since(sinceMs)).take(limit)
        // One more row, so the last play's successor is visible to the repair.
        val rows = inner.since(sinceMs, limit + 1)
        return l.read(rows, count = limit)
    }

    override suspend fun forTrack(key: TrackKey, limit: Int): List<PlayEvent> = inner.forTrack(key, limit)

    override suspend fun forArtist(artist: ArtistKey, limit: Int): List<PlayEvent> = inner.forArtist(artist, limit)
}
