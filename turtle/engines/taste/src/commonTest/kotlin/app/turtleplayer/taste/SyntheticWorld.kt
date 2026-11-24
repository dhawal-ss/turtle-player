package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import kotlin.random.Random

/**
 * Deterministic synthetic music world: [genres] genre clusters, [artistsPerGenre] artists each,
 * [tracksPerArtist] tracks per artist (two albums), with co-listening vectors that cluster by genre
 * and artist. Shipped through a real pack round-trip so quantization is part of every test.
 */
class SyntheticWorld(
    seed: Int = 42,
    val genres: Int = 6,
    val artistsPerGenre: Int = 8,
    val tracksPerArtist: Int = 6,
) {
    val dim = EMBEDDING_DIM
    private val random = Random(seed)

    val genreCentroids: List<FloatArray> = List(genres) { gaussian(1f).normalizeInPlace() }
    val artists: List<ArtistKey> = List(genres * artistsPerGenre) { ArtistKey.of("g${it / artistsPerGenre}a${it % artistsPerGenre}") }
    val artistGenre: Map<ArtistKey, Int> = artists.withIndex().associate { (i, a) -> a to i / artistsPerGenre }
    val artistVectors: Map<ArtistKey, FloatArray> = artists.associateWith { a ->
        genreCentroids[artistGenre.getValue(a)].copyOf().addScaled(gaussian(0.05f), 1f).normalizeInPlace()
    }

    val tracks: List<TrackMeta> = artists.flatMap { a ->
        List(tracksPerArtist) { t ->
            TrackMeta(
                key = TrackKey("mbid:${a.value}t$t"),
                title = "${a.value} song $t",
                artists = listOf(a.value),
                album = "${a.value} album ${t / 3}",
                durationMs = 200_000,
                releasedAtMs = T0 - (30 + random.nextInt(1500)) * DAY_MS,
                // Popularity is independent of genre: personal taste must beat it.
                popularity = random.nextFloat().let { it * it },
            )
        }
    }
    val trackGenre: Map<TrackKey, Int> = tracks.associate { it.key to artistGenre.getValue(it.artistKey) }
    val trackVectors: Map<TrackKey, FloatArray> = tracks.associate { t ->
        t.key to artistVectors.getValue(t.artistKey).copyOf().addScaled(gaussian(0.06f), 1f).normalizeInPlace()
    }

    val store: InMemoryEmbeddingStore = InMemoryEmbeddingStore(dim).also { s ->
        val artistPack = EmbeddingPackWriter.write(EmbeddingKind.ARTIST, dim, artistVectors.map { (k, v) -> k.value to v }, buildId = 20261101)
        val trackPack = EmbeddingPackWriter.write(EmbeddingKind.TRACK, dim, trackVectors.map { (k, v) -> k.value to v }, buildId = 20261101)
        s.load(EmbeddingPackReader.read(artistPack, dim, EmbeddingKind.ARTIST))
        s.load(EmbeddingPackReader.read(trackPack, dim, EmbeddingKind.TRACK))
    }
    val catalog = InMemoryTrackCatalog(tracks)
    val byKey: Map<TrackKey, TrackMeta> = tracks.associateBy { it.key }

    fun tracksOf(artist: ArtistKey): List<TrackMeta> = tracks.filter { it.artistKey == artist }

    fun tracksOfGenre(g: Int): List<TrackMeta> = tracks.filter { trackGenre[it.key] == g }

    private fun gaussian(sigma: Float) = FloatArray(dim) { (Distributions.normal(random) * sigma).toFloat() }

    /** A listener who loves two genres (some artists in them more than others) and skips the rest. */
    class User(
        val id: String,
        val likedGenres: Set<Int>,
        val favoriteArtists: Set<ArtistKey>,
        val heavyRotation: List<TrackMeta>,
        /** In-taste tracks the listener only finds in the last 30 days: the targets for discovery. */
        val discoverLate: Set<TrackKey>,
    )

    fun user(index: Int): User {
        val r = Random(1000 + index)
        val g1 = r.nextInt(genres)
        var g2 = r.nextInt(genres - 1)
        if (g2 >= g1) g2++
        val liked = setOf(g1, g2)
        val favorites = liked.flatMap { g -> artists.filter { artistGenre[it] == g }.shuffled(r).take(5) }.toSet()
        val late = tracks.filter { trackGenre[it.key] in liked }.shuffled(r).take(30).map { it.key }.toSet()
        val rotation = tracks.filter { it.artistKey in favorites && it.key !in late }.shuffled(r).take(12)
        return User("u$index", liked, favorites, rotation, late)
    }

    /**
     * [days] of history, [playsPerDay] plays a day, ending at [endMs]. 80% of plays are in-taste
     * (mostly finished), 20% are popular out-of-taste tracks (mostly quick-skipped).
     */
    fun history(user: User, days: Int = 120, playsPerDay: Int = 6, endMs: Long = T0): List<PlayEvent> {
        val r = Random(user.id.hashCode())
        val favoriteTracks = tracks.filter { it.artistKey in user.favoriteArtists && it.key !in user.discoverLate }
        val late = tracks.filter { it.key in user.discoverLate }
        val lateStart = endMs - 30 * DAY_MS
        val likedOther = tracks.filter { trackGenre[it.key] in user.likedGenres && it.artistKey !in user.favoriteArtists && it.key !in user.discoverLate }
        val popularOut = tracks.filter { trackGenre[it.key] !in user.likedGenres }.sortedByDescending { it.popularity }.take(60)
        val start = endMs - days * DAY_MS
        val out = ArrayList<PlayEvent>()
        var position = 0
        for (d in 0 until days) {
            val sessionStart = start + d * DAY_MS + (8 + r.nextInt(12)) * HOUR_MS
            for (i in 0 until playsPerDay) {
                val roll = r.nextDouble()
                val discovering = start + d * DAY_MS >= lateStart && r.nextDouble() < 0.25
                val (track, inTaste) = when {
                    discovering && roll < 0.8 -> late.random(r) to true
                    roll < 0.45 -> user.heavyRotation.random(r) to true
                    roll < 0.72 -> favoriteTracks.random(r) to true
                    roll < 0.80 -> likedOther.random(r) to true
                    else -> popularOut.random(r) to false
                }
                val outcome = r.nextDouble()
                val (end, ms) = if (inTaste) {
                    when {
                        outcome < 0.85 -> EndReason.COMPLETED to 200_000L
                        outcome < 0.95 -> EndReason.SKIPPED to 90_000L
                        else -> EndReason.SKIPPED to 10_000L
                    }
                } else {
                    if (outcome < 0.85) EndReason.SKIPPED to 8_000L else EndReason.COMPLETED to 200_000L
                }
                val startedAt = sessionStart + i * 4 * MINUTE_MS
                out += PlayEvent(
                    track = track, sessionId = "${user.id}-$d", position = position++, surface = Surface.LIBRARY,
                    startReason = StartReason.AUTOPLAY, endReason = end, startedAtMs = startedAt, msPlayed = ms, durationMs = 200_000,
                    liked = inTaste && end == EndReason.COMPLETED && r.nextDouble() < 0.05,
                    replayed = inTaste && end == EndReason.COMPLETED && r.nextDouble() < 0.03,
                    context = PlayContext(hour = ((startedAt / HOUR_MS) % 24).toInt(), weekday = 1 + (d % 7)),
                )
            }
        }
        return out
    }

    fun engine(
        events: PlayEventStore,
        clock: Clock,
        random: Random = Random(7),
        controls: UserControls = UserControls(),
        fresh: FreshReleaseSource? = null,
        upstream: RecommendationSource? = null,
        config: TasteConfig = TasteConfig(),
        blocks: ExternalBlocks? = null,
    ) = TasteEngine(
        events = events, store = store, catalog = catalog, freshReleases = fresh, upstream = upstream,
        config = config, clock = clock, random = random, controls = controls, blocks = blocks,
    )

    companion object {
        /** 2026-11-02T00:00Z, a Monday. */
        const val T0 = 1_793_577_600_000L
    }
}

/** Builds a single play event with sensible defaults for tests. */
fun play(
    track: TrackMeta,
    atMs: Long,
    end: EndReason = EndReason.COMPLETED,
    msPlayed: Long = if (end == EndReason.COMPLETED) 200_000 else 10_000,
    liked: Boolean = false,
    replayed: Boolean = false,
    lessLikeThis: Boolean = false,
    surfaceDetail: String? = null,
    startReason: StartReason = StartReason.AUTOPLAY,
    sessionId: String = "s",
    source: EventSource = EventSource.LOCAL,
) = PlayEvent(
    track = track, sessionId = sessionId, position = 0, surface = Surface.AUTOPLAY, startReason = startReason,
    endReason = end, startedAtMs = atMs, msPlayed = msPlayed, durationMs = 200_000, liked = liked, replayed = replayed,
    lessLikeThis = lessLikeThis, surfaceDetail = surfaceDetail, source = source,
)

class MutableClock(var now: Long) : Clock {
    override fun nowMs(): Long = now
}
