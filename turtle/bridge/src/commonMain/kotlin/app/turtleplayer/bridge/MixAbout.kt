package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.taste.QueueItem
import app.turtleplayer.taste.Reason
import app.turtleplayer.taste.Shelf
import app.turtleplayer.taste.ShelfCadence
import app.turtleplayer.taste.ShelfKind
import app.turtleplayer.taste.SlotKind
import app.turtleplayer.taste.TasteProfile

/** Why a More like mix's seed track was picked (the engine's pick: replayed, else liked, else played lately). */
enum class MixSeedTie {
    /** They replayed it. */
    REPLAYED,

    /** They liked it, without replaying it. */
    LIKED,

    /** They played it lately: found this week, or their most finished recent track. */
    LISTENED,
}

/**
 * Where one of Turtle's mixes comes from, for its details page: the kind of mix (the UI says what
 * it is made of), how often it is rebuilt, the track or artist it grew from, the artists it leans
 * on, and how many of its songs the listener already knows. Typed, with names only: the UI writes
 * the sentences.
 */
data class MixAbout(
    val kind: ShelfKind,
    /** How long the mix stays as built; null when it follows the listening live (On repeat). */
    val cadence: ShelfCadence?,
    /** The track ("Because you replayed X") or artist ("X Mix") it grew from, when it has one. */
    val seed: String?,
    /** Its main artists, most present first (at most [MAX_TOP_ARTISTS]). */
    val topArtists: List<String>,
    /** How many of the mix's [total] songs the listener has heard (played here or saved), counted when it was described. */
    val known: Int = 0,
    /** How many songs the mix holds. */
    val total: Int = 0,
    /** Why the seed track was picked, for a More like mix; null for every other kind. */
    val seedTie: MixSeedTie? = null,
    /**
     * The YouTube ids of the [known] songs, so the page can count the songs it actually lists (Home
     * shows at most 24 of a mix, only songs it can play, and none the listener hid).
     */
    val knownSongs: Set<String> = emptySet(),
) {
    companion object {
        const val MAX_TOP_ARTISTS = 3
    }
}

/** How long a shelf of [kind] stays as built (see [ShelfCadence]); null for live or on-demand shelves. */
internal fun cadenceOf(kind: ShelfKind): ShelfCadence? = when (kind) {
    ShelfKind.DAILY_MIX -> ShelfCadence.DAILY
    ShelfKind.MORE_LIKE -> ShelfCadence.DAILY_MORE_LIKE
    ShelfKind.WEEKLY_DISCOVERY -> ShelfCadence.WEEKLY
    ShelfKind.FRESH_FOR_YOU -> ShelfCadence.DAILY_FRESH
    ShelfKind.REDISCOVER -> ShelfCadence.DAILY_REDISCOVER
    ShelfKind.ON_REPEAT, ShelfKind.UP_NEXT, ShelfKind.RADIO -> null
}

/**
 * Whether the listener knows an item's song: a FAMILIAR slot (the ranker marks a candidate FAMILIAR
 * only when it was played here before), or [profile]'s own check, which also counts saved songs, the
 * same song under another upload, and plays since a kept shelf was built. Never the mix's planned
 * familiar share: a planned familiar slot with no heard track left is filled by a new one.
 */
internal fun knownBy(profile: TasteProfile?): (QueueItem) -> Boolean =
    { item -> item.slot == SlotKind.FAMILIAR || profile?.hasHeard(item.track) == true }

/**
 * Describes [shelf]. Names come from [track] and [artist] (the taste engine's display names, which
 * are suspend); an artist without one falls back to the name written on its tracks. [known] says
 * whether the listener has heard an item (see [knownBy]).
 */
internal suspend fun mixAbout(
    shelf: Shelf,
    track: suspend (TrackKey) -> String?,
    artist: suspend (ArtistKey) -> String?,
    known: (QueueItem) -> Boolean = knownBy(null),
): MixAbout {
    val reason = shelf.reason
    val seed =
        when (reason) {
            is Reason.DailyMix -> reason.seedArtist?.let { artist(it) }
            is Reason.BecauseYouReplayed -> track(reason.track)
            is Reason.BecauseYouLiked -> track(reason.track)
            is Reason.BecauseYouListened -> track(reason.track)
            else -> null
        }
    // The mix's own artists first (a Daily Mix names them), then the items' artists by how often they appear.
    val written = HashMap<ArtistKey, String>()
    shelf.items.forEach { written.getOrPut(it.track.artistKey) { it.track.primaryArtist } }
    val fromReason = (reason as? Reason.DailyMix)?.let { listOfNotNull(it.seedArtist) + it.topArtists }.orEmpty()
    val byCount =
        shelf.items
            .groupingBy { it.track.artistKey }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<ArtistKey, Int>> { it.value }.thenBy { it.key.value })
            .map { it.key }
    val names = LinkedHashSet<String>()
    for (key in (fromReason + byCount).distinct()) {
        if (names.size >= MixAbout.MAX_TOP_ARTISTS) break
        val name = (artist(key) ?: written[key])?.takeIf { it.isNotBlank() } ?: continue
        names += name
    }
    val tie =
        when (reason) {
            is Reason.BecauseYouReplayed -> MixSeedTie.REPLAYED
            is Reason.BecauseYouLiked -> MixSeedTie.LIKED
            is Reason.BecauseYouListened -> MixSeedTie.LISTENED
            else -> null
        }?.takeIf { shelf.kind == ShelfKind.MORE_LIKE }
    return MixAbout(
        kind = shelf.kind,
        cadence = cadenceOf(shelf.kind),
        seed = seed,
        topArtists = names.toList(),
        known = shelf.items.count(known),
        total = shelf.items.size,
        seedTie = tie,
        knownSongs = shelf.items.filter(known).mapNotNullTo(HashSet()) { it.track.youtubeId },
    )
}
