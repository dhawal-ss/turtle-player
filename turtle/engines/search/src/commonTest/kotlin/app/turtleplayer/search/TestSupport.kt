package app.turtleplayer.search

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.TitleCanonicalizer

/** Epoch ms of 1 July of [year], close enough for year filters. */
fun yearMs(year: Int): Long = ((year - 1970) * 365.2425 * 86_400_000L).toLong() + 181L * 86_400_000L

fun track(
    id: String,
    artist: String,
    title: String,
    album: String? = null,
    year: Int? = null,
    popularity: Float? = null,
    vararg moreArtists: String,
): TrackMeta = TrackMeta(
    key = TrackKey.youtube(id),
    title = title,
    artists = listOf(artist) + moreArtists,
    album = album,
    releasedAtMs = year?.let(::yearMs),
    youtubeId = id,
    popularity = popularity,
)

/**
 * Online provider backed by its own lexical index, standing in for YouTube Music: returns tracks
 * ranked by match with popularity as a tie-breaker, plus artists and albums.
 */
class FakeOnlineProvider(
    tracks: List<OnlineTrack>,
    private val delayMs: Long = 0,
    private val completions: Map<String, List<String>> = emptyMap(),
) : OnlineSearchProvider {
    private val byKey = tracks.associateBy { it.meta.key }
    private val index = LexicalIndex().apply { tracks.forEach { upsert(it.meta, setOf(LocalSource.CATALOG_CACHE)) } }
    var calls = 0
        private set

    override suspend fun search(query: ParsedQuery, limit: Int): OnlineResults {
        calls++
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        val hits = index.search(LexicalQuery.of(query.normalizedTokens.joinToString(" "), asYouType = query.asYouType), limit = limit * 2)
        fun pop(h: LexicalHit) = ((h.item as? LocalItem.Track)?.let { byKey[it.track.meta.key]?.popularity } ?: 0f)
        val sorted = hits.sortedByDescending { it.score + 0.1f * pop(it) }
        return OnlineResults(
            tracks = sorted.mapNotNull { (it.item as? LocalItem.Track)?.let { t -> byKey[t.track.meta.key] } }.take(limit),
            artists = sorted.mapNotNull { (it.item as? LocalItem.Artist)?.artist?.let { a -> OnlineArtist(a.name, browseId = "UC_${a.key.value}") } }.take(5),
            albums = sorted.mapNotNull { (it.item as? LocalItem.Album)?.album?.let { a -> OnlineAlbum(a.title, a.artist, browseId = "MPRE_${a.key}") } }.take(5),
        )
    }

    override suspend fun suggestions(prefix: String): List<String> {
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        return completions[prefix.trim().lowercase()].orEmpty()
    }
}

fun official(meta: TrackMeta, popularity: Float? = null) =
    OnlineTrack(meta, official = TitleCanonicalizer.canonicalize(meta.title).official || meta.artists.first().endsWith("Topic"), popularity = popularity)

/** Portable fixed-point formatting for test output (String.format is JVM-only). */
fun Number.fmt(decimals: Int = 3): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return (kotlin.math.round(toDouble() * factor) / factor).toString()
}
