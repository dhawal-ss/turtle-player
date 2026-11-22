package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

data class QueueRules(
    /** The same artist never appears within this many tracks before it (so at most once in any 4 in a row). */
    val artistSpacing: Int = 3,
    /** At most this many tracks from one album in any hour, counting recent plays. */
    val maxPerAlbumPerHour: Int = 2,
    /** How many recommended tracks we remember to avoid repeating them. */
    val recentMemory: Int = 500,
    /** Upcoming items that stay fixed when the queue is re-ranked, so it never feels jumpy. */
    val frozenCount: Int = 2,
    /** A track played this recently is not queued again. */
    val minRepeatGapMs: Long = 3 * HOUR_MS,
    /** Assumed length of tracks with unknown duration, for projecting album-cap windows. */
    val defaultDurationMs: Long = 210_000,
    /** Cap per artist in one assembled list (used for shelves; unlimited for autoplay). */
    val maxPerArtist: Int = Int.MAX_VALUE,
)

/** One recommended track with its explanation. Store [surfaceDetail] on the resulting PlayEvent. */
data class QueueItem(
    val track: TrackMeta,
    val source: CandidateSource,
    val reason: Reason,
    val slot: SlotKind,
    val score: Float,
) {
    val key: TrackKey get() = track.key
    val surfaceDetail: String get() = SurfaceTag.format(source, slot)

    companion object {
        fun of(c: ScoredCandidate, slot: SlotKind = c.slot) = QueueItem(c.track, c.candidate.source, c.candidate.reason, slot, c.score)
    }
}

/** Bounded memory of recommended tracks (oldest evicted first). Persist with [toList]/[restore]. */
class RecentlyRecommended(val capacity: Int = 500) {
    private val order = ArrayDeque<TrackKey>()
    private val set = HashSet<TrackKey>()

    val size: Int get() = order.size

    operator fun contains(key: TrackKey): Boolean = key in set

    fun add(key: TrackKey) {
        if (!set.add(key)) {
            order.remove(key) // refresh recency
        }
        order.addLast(key)
        while (order.size > capacity) set.remove(order.removeFirst())
    }

    fun addAll(keys: Iterable<TrackKey>) = keys.forEach(::add)

    fun toList(): List<TrackKey> = order.toList()

    fun restore(keys: List<TrackKey>) {
        order.clear()
        set.clear()
        addAll(keys)
    }
}

/**
 * Builds a queue from ranked candidates and a familiar/discovery slot plan, enforcing artist
 * spacing, album caps and no-repeat rules. Rules are never relaxed: when nothing fits, the queue
 * is shorter rather than repetitive.
 */
class QueueAssembler(val rules: QueueRules = QueueRules(), private val songs: SongKeys = SongKeys()) {
    /**
     * @param frozen items kept as-is at the head (already validated when they were chosen).
     * @param ranked candidates, best first.
     * @param slots slot plan for the positions after [frozen]; its size is how many items to add.
     * @param history recent plays (oldest first) for spacing, album caps and repeat gaps.
     * @param nowPlaying the track playing now, which counts for spacing and album caps.
     * @param allowAgain tracks exempt from the recent-memory rule (the tail being re-ranked).
     */
    fun assemble(
        frozen: List<QueueItem>,
        ranked: List<ScoredCandidate>,
        slots: List<SlotKind>,
        history: List<RecentPlay>,
        nowPlaying: TrackMeta?,
        recent: RecentlyRecommended,
        nowMs: Long,
        allowAgain: Set<TrackKey> = emptySet(),
    ): List<QueueItem> {
        val queue = ArrayList(frozen)
        val used = HashSet<TrackKey>()
        frozen.forEach { used += it.key }
        nowPlaying?.let { used += it.key }
        // The same song under another upload id counts as the same song for every rule below.
        val usedSongs = HashSet<TrackKey>()
        frozen.forEach { usedSongs += songs.of(it.track) }
        nowPlaying?.let { usedSongs += songs.of(it) }
        val recentPlays = history.filter { nowMs - it.startedAtMs < rules.minRepeatGapMs }
        val playedRecently = recentPlays.map { it.track }.toSet()
        val playedRecentlySongs = recentPlays.mapNotNull { it.song }.toSet()

        // Artists of the last plays (within an hour, so yesterday's last track does not count), then now playing.
        val leadArtists = ArrayList<ArtistKey>()
        history.filter { nowMs - it.startedAtMs <= HOUR_MS }.takeLast(rules.artistSpacing).forEach { leadArtists += it.artist }
        nowPlaying?.let { leadArtists += it.artistKey }

        // Album timeline: (projected start, album) for recent plays, now playing and queued items.
        val albumTimes = ArrayList<Pair<Long, String>>()
        history.forEach { p -> p.album?.let { albumTimes += p.startedAtMs to it } }
        var cursor = nowMs
        nowPlaying?.let { np ->
            np.albumKey?.let { albumTimes += nowMs to it }
            cursor += np.durationMs ?: rules.defaultDurationMs
        }
        for (item in frozen) {
            item.track.albumKey?.let { albumTimes += cursor to it }
            cursor += item.track.durationMs ?: rules.defaultDurationMs
        }

        val byKind = ranked.groupBy { it.slot }
        val taken = HashSet<TrackKey>()

        fun fits(c: ScoredCandidate, start: Long): Boolean {
            val key = c.candidate.key
            if (key in used || key in taken || key in playedRecently) return false
            val song = songs.of(c.track)
            if (song in usedSongs || song in playedRecentlySongs) return false
            if (key in recent && key !in allowAgain) return false
            val artist = c.track.artistKey
            val tail = (leadArtists + queue.map { it.track.artistKey }).takeLast(rules.artistSpacing)
            if (artist in tail) return false
            if (rules.maxPerArtist != Int.MAX_VALUE && queue.count { it.track.artistKey == artist } >= rules.maxPerArtist) return false
            val album = c.track.albumKey
            if (album != null) {
                val inWindow = albumTimes.count { (t, a) -> a == album && t > start - HOUR_MS && t <= start }
                if (inWindow >= rules.maxPerAlbumPerHour) return false
            }
            return true
        }

        for (slot in slots) {
            val other = if (slot == SlotKind.FAMILIAR) SlotKind.DISCOVERY else SlotKind.FAMILIAR
            val pick = byKind[slot].orEmpty().firstOrNull { fits(it, cursor) }
                ?: byKind[other].orEmpty().firstOrNull { fits(it, cursor) }
                ?: break
            val item = QueueItem.of(pick)
            queue += item
            taken += item.key
            usedSongs += songs.of(item.track)
            item.track.albumKey?.let { albumTimes += cursor to it }
            cursor += item.track.durationMs ?: rules.defaultDurationMs
        }
        return queue
    }
}
