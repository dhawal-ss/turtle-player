package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import kotlin.math.tanh

/** One item of a Home row, as much as the row's source tells about it. */
sealed interface RowItem {
    data class Track(
        val track: TrackMeta,
    ) : RowItem

    data class Artist(
        val artist: ArtistKey,
    ) : RowItem

    /** An album or playlist with the artists its source names; empty for playlists, which name none. */
    data class Collection(
        val artists: List<ArtistKey>,
    ) : RowItem
}

/** A candidate Home row: its key, its items, and whether its source marks it as personal ("Your mixes"). */
data class RowCandidate(
    val id: String,
    val items: List<RowItem>,
    val personal: Boolean = false,
)

data class RowScore(
    /** Mean of tanh(affinity / affinityScale) over items whose track or artist the profile knows; 0 when none. */
    val meanAffinity: Double,
    /** Items by an artist the listener has heard or saved, over [scoredItems]. */
    val knownArtistShare: Double,
    /** Items by a muted, "less like this" or net-disliked artist (or a "less like this" track), over [scoredItems]. */
    val dislikedShare: Double,
    /** Items that name at least one artist. */
    val scoredItems: Int,
) {
    val value: Double get() = knownArtistShare * meanAffinity
}

data class HomeRowConfig(
    val minScore: Double = 0.15,
    val maxDislikedShare: Double = 0.2,
    val maxRows: Int = 3,
    val maxPersonalPlaylistRows: Int = 1,
    val minScoredItems: Int = 3,
    val affinityScale: Double = 3.0,
    /**
     * "Less like this" marks after which a whole artist counts as disliked: "less like this" on the
     * artist adds two, "not interested" on one of their songs adds one.
     */
    val dislikedArtistMarks: Int = 2,
)

/** The rows to show, in order; [coldStart] rows were taken unscored. [scores] holds every candidate's score. */
data class RowSelection(
    val ids: List<String>,
    val coldStart: Boolean,
    val scores: Map<String, RowScore>,
)

/** Scores Home rows and items against one profile and set of controls. */
internal class RowScorer(
    private val profile: TasteProfile,
    private val controls: UserControls,
    private val config: HomeRowConfig,
) {
    fun score(items: List<RowItem>): RowScore {
        var scored = 0
        var known = 0
        var disliked = 0
        var affinitySum = 0.0
        var affinityCount = 0
        for (item in items) {
            val artists = artistsOf(item)
            if (artists.isEmpty()) continue
            scored++
            if (isKnown(item, artists)) known++
            if (isDisliked(item, artists)) disliked++
            affinityOf(item, artists)?.let {
                affinitySum += normalized(it)
                affinityCount++
            }
        }
        if (scored == 0) return RowScore(0.0, 0.0, 0.0, 0)
        val mean = if (affinityCount == 0) 0.0 else affinitySum / affinityCount
        return RowScore(mean, known.toDouble() / scored, disliked.toDouble() / scored, scored)
    }

    fun matches(item: RowItem): Boolean {
        val artists = artistsOf(item)
        if (artists.isEmpty() || isDisliked(item, artists)) return false
        val affinity = affinityOf(item, artists) ?: return false
        return normalized(affinity) > 0.0
    }

    fun select(
        candidates: List<RowCandidate>,
        sticky: List<String>,
        coldStart: Boolean,
    ): RowSelection {
        val scores = candidates.associate { it.id to score(it.items) }
        if (coldStart) return RowSelection(candidates.take(config.maxRows).map { it.id }, coldStart = true, scores = scores)
        val passing =
            candidates.filter { c ->
                val s = scores.getValue(c.id)
                s.scoredItems >= config.minScoredItems && s.value >= config.minScore && s.dislikedShare <= config.maxDislikedShare
            }
        val personal =
            candidates
                .filter { it.personal && scores.getValue(it.id).scoredItems == 0 }
                .take(config.maxPersonalPlaylistRows)
                .map { it.id }
        val keptIds = passing.mapTo(HashSet()) { it.id } + personal
        val result = sticky.filter { it in keptIds }.distinct().toMutableList()
        val scoredSlots = config.maxRows - personal.size
        for (c in passing.sortedByDescending { scores.getValue(it.id).value }) {
            if (result.count { it !in personal } >= scoredSlots) break
            if (c.id !in result) result += c.id
        }
        for (id in personal) if (id !in result) result += id
        return RowSelection(result.take(config.maxRows), coldStart = false, scores = scores)
    }

    private fun artistsOf(item: RowItem): List<ArtistKey> =
        when (item) {
            is RowItem.Track -> item.track.artists.map(ArtistKey::of)
            is RowItem.Artist -> listOf(item.artist)
            is RowItem.Collection -> item.artists
        }

    private fun isKnown(
        item: RowItem,
        artists: List<ArtistKey>,
    ): Boolean = (item is RowItem.Track && profile.hasHeard(item.track)) || artists.any(profile::hasHeardArtist)

    private fun isDisliked(
        item: RowItem,
        artists: List<ArtistKey>,
    ): Boolean =
        (item is RowItem.Track && item.track.key in controls.lessLikeThisTracks) ||
            artists.any { a ->
                a in controls.mutedArtists ||
                    (controls.lessLikeThisArtists[a] ?: 0) >= config.dislikedArtistMarks ||
                    (profile.artist(a)?.affinity ?: 0.0) < 0.0
            }

    /** The track's own affinity when known, else the best of its artists'; null when the profile knows neither. */
    private fun affinityOf(
        item: RowItem,
        artists: List<ArtistKey>,
    ): Double? {
        if (item is RowItem.Track) profile.track(item.track.key)?.let { return it.affinity }
        return artists.mapNotNull { profile.artist(it)?.affinity }.maxOrNull()
    }

    private fun normalized(affinity: Double) = tanh(affinity / config.affinityScale)
}

/** Where Jump back in's plays come from: recorded on this device, or imported from Metrolist's own on-device history. */
private val JUMP_BACK_IN_SOURCES = setOf(EventSource.LOCAL, EventSource.METROLIST_IMPORT)

/**
 * The most recent distinct finished songs in [events] (oldest first), newest first: one per song
 * identity, its newest upload winning; ruled-out tracks and muted artists left out.
 */
internal fun jumpBackInOf(
    events: List<PlayEvent>,
    controls: UserControls,
    limit: Int,
    songs: SongKeys,
): List<TrackMeta> {
    val seen = HashSet<Any>()
    val result = ArrayList<TrackMeta>()
    for (e in events.asReversed()) {
        if (result.size >= limit) break
        if (!e.isFinished || e.source !in JUMP_BACK_IN_SOURCES) continue
        if (e.trackKey in controls.lessLikeThisTracks || e.artistKey in controls.mutedArtists) continue
        if (seen.add(songs.of(e.track))) result += e.track
    }
    return result
}
