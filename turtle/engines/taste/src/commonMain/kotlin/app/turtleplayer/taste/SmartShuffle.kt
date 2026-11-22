package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlin.random.Random

data class SmartShuffleConfig(
    /** One recommendation after every this many playlist songs (3: about one song in four). */
    val playlistPerRecommendation: Int = 3,
    /** At most this many recommendations by one artist, so one radio's favourite doesn't take over. */
    val maxPerRecommendedArtist: Int = 2,
) {
    init {
        require(playlistPerRecommendation >= 1) { "playlistPerRecommendation must be at least 1" }
        require(maxPerRecommendedArtist >= 1) { "maxPerRecommendedArtist must be at least 1" }
    }
}

/**
 * One position of a Smart Shuffle queue: a song of the playlist ([recommendation] null) or a
 * recommended song. Store [surfaceDetail] on the recommended song's play event, so its skip or
 * finish trains the engine like any other suggestion.
 */
data class SmartShuffleItem(val track: TrackMeta, val recommendation: QueueItem? = null) {
    val isRecommendation: Boolean get() = recommendation != null
    val surfaceDetail: String? get() = recommendation?.surfaceDetail
}

/**
 * Spotify-style Smart Shuffle: the playlist in a random order with taste-matched songs mixed in.
 *
 * - Every playlist song is kept exactly once (duplicates in the playlist play once).
 * - The order is random but never puts one artist twice in a row when another order could avoid it.
 * - After every [SmartShuffleConfig.playlistPerRecommendation] playlist songs comes the best
 *   remaining recommendation that doesn't repeat a neighbour's artist. A slot stays empty rather
 *   than break that rule.
 * - Recommendations never repeat a playlist song (same key, or same artist and title under another
 *   upload), each other, a muted artist, an artist or track marked "less like this", or a track the
 *   profile shows the listener dislikes.
 *
 * Pure and deterministic for a given [random]; the caller gathers and ranks the recommendations
 * (for example with [TasteEngine.rankExternal]).
 */
class SmartShuffle(
    private val random: Random,
    private val config: SmartShuffleConfig = SmartShuffleConfig(),
) {
    /**
     * @param playlist the playlist's songs, in any order.
     * @param recommendations candidates, best first.
     * @param controls the listener's controls ([TasteEngine.controls]).
     * @param profile when given, tracks with negative affinity are not recommended.
     */
    fun arrange(
        playlist: List<TrackMeta>,
        recommendations: List<QueueItem>,
        controls: UserControls = UserControls(),
        profile: TasteProfile? = null,
    ): List<SmartShuffleItem> {
        val own = shuffleSpaced(playlist.distinctBy { it.key })
        val picks = eligible(own, recommendations, controls, profile)
        return interleave(own, picks)
    }

    private fun eligible(
        own: List<TrackMeta>,
        recommendations: List<QueueItem>,
        controls: UserControls,
        profile: TasteProfile?,
    ): MutableList<QueueItem> {
        val keys = own.mapTo(HashSet()) { it.key }
        val songs = own.mapTo(HashSet()) { songIdentity(it) }
        val perArtist = HashMap<ArtistKey, Int>()
        val out = ArrayList<QueueItem>()
        for (item in recommendations) {
            val t = item.track
            if (t.key in keys || !songs.add(songIdentity(t))) continue
            if (blocked(t.key, t.artistKey, controls, profile)) continue
            val n = perArtist[t.artistKey] ?: 0
            if (n >= config.maxPerRecommendedArtist) continue
            perArtist[t.artistKey] = n + 1
            keys += t.key
            out += item
        }
        return out
    }

    private fun blocked(key: TrackKey, artist: ArtistKey, controls: UserControls, profile: TasteProfile?): Boolean =
        artist in controls.mutedArtists ||
            artist in controls.lessLikeThisArtists ||
            key in controls.lessLikeThisTracks ||
            (profile?.track(key)?.let { it.lessLikeThis || it.affinity < 0 } ?: false)

    private fun interleave(own: List<TrackMeta>, picks: MutableList<QueueItem>): List<SmartShuffleItem> {
        val out = ArrayList<SmartShuffleItem>(own.size + picks.size)
        for ((i, track) in own.withIndex()) {
            out += SmartShuffleItem(track)
            val placed = i + 1
            if (placed % config.playlistPerRecommendation != 0 || picks.isEmpty()) continue
            val before = track.artistKey
            val after = own.getOrNull(i + 1)?.artistKey
            val index = picks.indexOfFirst { !sameArtist(it.track.artistKey, before) && !sameArtist(it.track.artistKey, after) }
            if (index >= 0) picks.removeAt(index).let { out += SmartShuffleItem(it.track, it) }
        }
        return out
    }

    /**
     * A random order with no artist twice in a row where avoidable. Walks a random permutation and
     * takes the first song that keeps the rest arrangeable: with n songs left after the last one
     * placed (artist L), a valid rest exists iff no artist has more than ceil(n/2) songs and L no
     * more than floor(n/2).
     */
    private fun shuffleSpaced(tracks: List<TrackMeta>): List<TrackMeta> {
        val pool = tracks.toMutableList()
        pool.shuffle(random)
        val counts = HashMap<ArtistKey, Int>()
        pool.forEach { if (known(it.artistKey)) counts[it.artistKey] = (counts[it.artistKey] ?: 0) + 1 }
        val out = ArrayList<TrackMeta>(pool.size)
        var last: ArtistKey? = null
        while (pool.isNotEmpty()) {
            val restAfterPick = pool.size - 1
            // The two largest artist counts, so "largest other than the pick" is O(1).
            var top: ArtistKey? = null
            var topCount = 0
            var secondCount = 0
            for ((artist, c) in counts) {
                if (c > topCount) {
                    secondCount = topCount
                    top = artist
                    topCount = c
                } else if (c > secondCount) {
                    secondCount = c
                }
            }
            fun keepsFeasible(a: ArtistKey): Boolean {
                if (!known(a)) return topCount <= (restAfterPick + 1) / 2
                val own = (counts[a] ?: 0) - 1
                val other = if (a == top) secondCount else topCount
                return own <= restAfterPick / 2 && other <= (restAfterPick + 1) / 2
            }
            var index = pool.indexOfFirst { !sameArtist(it.artistKey, last) && keepsFeasible(it.artistKey) }
            // Already infeasible (one artist fills most of the playlist): still avoid a repeat when possible.
            if (index < 0) index = pool.indexOfFirst { !sameArtist(it.artistKey, last) }
            if (index < 0) index = 0
            val pick = pool.removeAt(index)
            if (known(pick.artistKey)) {
                val c = (counts[pick.artistKey] ?: 1) - 1
                if (c == 0) counts.remove(pick.artistKey) else counts[pick.artistKey] = c
            }
            out += pick
            last = pick.artistKey
        }
        return out
    }

    private fun known(a: ArtistKey) = a.value.isNotBlank()

    /** Songs without an artist never count as a repeat. */
    private fun sameArtist(a: ArtistKey, b: ArtistKey?) = b != null && known(a) && a == b

    private fun songIdentity(t: TrackMeta) = t.artistKey.value + "\u0000" + TextNormalizer.normalize(t.title).folded
}
