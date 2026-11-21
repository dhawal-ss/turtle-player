package app.turtleplayer.bridge

import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * The shape of a Turtle DJ set: target energy (0 calm .. 1 peak) over the set. Each arc also sets how
 * long the blends are ([fadeSeconds], within the players' 15 s crossfade limit).
 */
enum class DjArc(val fadeSeconds: Float, private val keys: List<Pair<Double, Double>>) {
    Lounge(12f, listOf(0.0 to 0.22, 0.3 to 0.34, 0.6 to 0.42, 0.85 to 0.38, 1.0 to 0.26)),
    HouseParty(9f, listOf(0.0 to 0.42, 0.25 to 0.56, 0.55 to 0.74, 0.75 to 0.82, 0.9 to 0.66, 1.0 to 0.5)),
    ClubPeak(7f, listOf(0.0 to 0.55, 0.15 to 0.7, 0.35 to 0.86, 0.5 to 0.78, 0.65 to 0.92, 0.85 to 0.95, 1.0 to 0.74)),
    Sunrise(11f, listOf(0.0 to 0.72, 0.2 to 0.64, 0.5 to 0.48, 0.75 to 0.36, 1.0 to 0.22)),
    ;

    /** Target energy at [t] (0 start .. 1 end of the set), eased between the arc's key points. */
    fun energyAt(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        val i = keys.indexOfLast { it.first <= x }.coerceIn(0, keys.lastIndex - 1)
        val (x0, y0) = keys[i]
        val (x1, y1) = keys[i + 1]
        val u = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        val s = u * u * (3 - 2 * u)
        return y0 + (y1 - y0) * s
    }

    /** Genres a set opens with when the listener picks none. */
    val defaultGenres: List<String>
        get() = when (this) {
            Lounge -> listOf("chill", "soul", "rnb", "lofi")
            HouseParty -> listOf("house", "dance", "pop", "hiphop")
            ClubPeak -> listOf("techno", "house", "edm")
            Sunrise -> listOf("house", "chill", "ambient")
        }
}

/** A track as the DJ sees it: estimated tempo and energy (no BPM metadata exists) and its mix points. */
data class DjTrack(
    val song: SongItem,
    val genre: String,
    /** Tempo prior for the genre, nudged by title hints ("sped up"); an estimate, not analysis. */
    val bpm: Int,
    /** 0 calm .. 1 peak, from the genre prior, the search it came from and title hints. */
    val energy: Double,
    /** How far in to start on a cut (long DJ intros); 0 for radio edits. */
    val introSkipMs: Long,
    /** How long before the end to start mixing out, so the blend skips most of the outro. */
    val outroTrimMs: Long,
)

/**
 * Turtle DJ's crate digger: stocks per-genre pools from YouTube Music search (three energy tiers)
 * plus a radio from one hit, orders them with the taste engine, and picks the next track for a target
 * energy while keeping artists and genres spaced. No upstream changes: it only uses the repository.
 */
object DjEngine {
    private val mutex = Mutex()
    private val random = Random.Default

    private class Candidate(val song: SongItem, val genre: String, val energy: Double, val rank: Int)

    private val pools = HashMap<String, MutableList<Candidate>>()
    private val stocked = HashSet<String>()

    /** DJ-only genres on top of the Infinity Mix's (display names live in the UI). */
    private val extras: Map<String, String> = mapOf(
        "dnb" to "drum and bass",
        "edm" to "edm festival hits",
        "disco" to "nu disco",
        "trance" to "trance anthems",
        "amapiano" to "amapiano",
        "dancehall" to "dancehall hits",
    )

    /** The genres and moods offered in the DJ setup, club-first. */
    val genreIds: List<String> = listOf(
        "house", "techno", "dance", "edm", "dnb", "trance", "disco", "electronic", "afrobeats", "amapiano",
        "reggaeton", "dancehall", "latin", "hiphop", "rnb", "pop", "funk", "soul", "kpop", "bollywood",
        "punjabi", "throwback", "party", "workout", "chill", "lofi", "ambient",
    )

    private val bpmPrior = mapOf(
        "house" to 124, "techno" to 130, "dance" to 122, "edm" to 128, "dnb" to 174, "trance" to 136,
        "disco" to 118, "electronic" to 124, "afrobeats" to 108, "amapiano" to 113, "reggaeton" to 95,
        "dancehall" to 98, "latin" to 100, "hiphop" to 92, "rnb" to 96, "pop" to 118, "funk" to 108,
        "soul" to 100, "kpop" to 120, "bollywood" to 110, "punjabi" to 100, "throwback" to 118,
        "party" to 124, "workout" to 128, "chill" to 90, "lofi" to 80, "ambient" to 70, "rock" to 122,
        "indie" to 118, "alternative" to 120, "jazz" to 110, "classical" to 90, "country" to 112,
        "metal" to 140, "punk" to 160, "folk" to 105, "blues" to 95, "reggae" to 80, "focus" to 80,
        "sleep" to 60, "romance" to 85, "sad" to 80, "feelgood" to 116, "roadtrip" to 115,
    )

    private val energyPrior = mapOf(
        "house" to 0.68, "techno" to 0.78, "dance" to 0.7, "edm" to 0.82, "dnb" to 0.85, "trance" to 0.8,
        "disco" to 0.62, "electronic" to 0.66, "afrobeats" to 0.62, "amapiano" to 0.58, "reggaeton" to 0.7,
        "dancehall" to 0.66, "latin" to 0.64, "hiphop" to 0.62, "rnb" to 0.45, "pop" to 0.6, "funk" to 0.6,
        "soul" to 0.42, "kpop" to 0.66, "bollywood" to 0.6, "punjabi" to 0.68, "throwback" to 0.6,
        "party" to 0.8, "workout" to 0.82, "chill" to 0.3, "lofi" to 0.2, "ambient" to 0.12, "rock" to 0.66,
        "indie" to 0.5, "alternative" to 0.58, "jazz" to 0.35, "classical" to 0.2, "country" to 0.5,
        "metal" to 0.85, "punk" to 0.8, "folk" to 0.32, "blues" to 0.4, "reggae" to 0.45, "focus" to 0.18,
        "sleep" to 0.05, "romance" to 0.32, "sad" to 0.25, "feelgood" to 0.62, "roadtrip" to 0.6,
    )

    private val danceGenres = setOf("house", "techno", "dance", "edm", "dnb", "trance", "disco", "electronic", "amapiano", "party", "workout")

    private val slowHints = Regex("""slowed|reverb|acoustic|piano version|unplugged|lo-?fi|ballad|sleep|calm|stripped""")
    private val fastHints = Regex("""sped up|speed up|nightcore|hardstyle""")
    private val remixHints = Regex("""remix|\bedit\b|club mix|extended|\bvip\b|bootleg|rework""")
    private val extendedHints = Regex("""extended|original mix|club mix|dub mix""")
    private val liveHints = Regex("""\blive\b|live at|live from|concert""")

    fun bpmOf(genre: String): Int = bpmPrior[genre] ?: 118
    fun energyOf(genre: String): Double = energyPrior[genre] ?: 0.55
    fun isDance(genre: String): Boolean = genre in danceGenres

    private fun queryOf(genre: String): String? = InfinityGenres[genre]?.query ?: extras[genre]

    /** Forgets stocked pools (a new set starts with fresh crates). */
    suspend fun reset() = mutex.withLock {
        pools.clear()
        stocked.clear()
    }

    /** The DJ's view of any song (requests, or songs not from our pools): [genre] sets its priors. */
    fun describe(song: SongItem, genre: String): DjTrack = describe(song, genre, energyOf(genre))

    private fun describe(song: SongItem, genre: String, energy: Double): DjTrack {
        val title = song.title.lowercase()
        val bpm = (bpmOf(genre) * if (fastHints.containsMatchIn(title)) 1.2 else if (slowHints.containsMatchIn(title)) 0.85 else 1.0).toInt()
        val beatMs = 60_000L / bpm.coerceAtLeast(40)
        val durationMs = (song.duration ?: 0L) * 1000L
        val extended = extendedHints.containsMatchIn(title)
        val dance = isDance(genre)
        val intro = when {
            durationMs <= 0L -> 0L
            extended -> (128 * beatMs).coerceAtMost(75_000L)
            dance && durationMs > 330_000L -> (64 * beatMs).coerceAtMost(40_000L)
            else -> 0L
        }
        val outro = when {
            durationMs < 150_000L -> 0L
            extended -> (128 * beatMs).coerceAtMost(75_000L)
            dance -> (64 * beatMs).coerceAtMost(40_000L)
            else -> (durationMs * 0.06).toLong().coerceIn(6_000L, 18_000L)
        }
        return DjTrack(song, genre, bpm, energy, intro, outro)
    }

    private fun estimateEnergy(song: SongItem, genre: String, tier: Int): Double {
        val title = song.title.lowercase()
        var e = energyOf(genre) + (tier - 1) * 0.15
        if (slowHints.containsMatchIn(title)) e -= 0.25
        if (fastHints.containsMatchIn(title)) e += 0.15
        if (remixHints.containsMatchIn(title)) e += 0.08
        if (liveHints.containsMatchIn(title)) e -= 0.08
        val seconds = song.duration ?: 0L
        if (seconds > 390 && !isDance(genre)) e -= 0.05
        return e.coerceIn(0.02, 1.0)
    }

    private fun isLiveCut(song: SongItem) = song.isLive || liveHints.containsMatchIn(song.title.lowercase())

    /**
     * The next track for [target] energy from [genres], skipping [exclude] (ids) and the
     * [recentArtists]. Stocks pools near the target first (network, bounded). Null when nothing fits.
     */
    suspend fun pick(
        genres: List<String>,
        target: Double,
        exclude: Set<String>,
        recentArtists: List<String>,
        recentGenres: List<String>,
    ): DjTrack? = mutex.withLock {
        val chosen = genres.filter { queryOf(it) != null }.ifEmpty { listOf("party") }
        val tier = when {
            target < 0.4 -> 0
            target < 0.7 -> 1
            else -> 2
        }
        withTimeoutOrNull(PICK_TIMEOUT_MS) {
            var fills = 0
            while (fills < MAX_FILLS_PER_PICK) {
                val near = chosen.sumOf { g -> pools[g].orEmpty().count { it.song.id !in exclude && abs(it.energy - target) < 0.2 } }
                if (near >= 3) break
                // Stock the least-stocked genre at the tier nearest the target, then its neighbours.
                val order = listOf(tier, (tier + 1).coerceAtMost(2), (tier - 1).coerceAtLeast(0)).distinct()
                val next = order.firstNotNullOfOrNull { t ->
                    chosen.sortedBy { pools[it]?.size ?: 0 }.firstOrNull { "$it:$t" !in stocked }?.let { it to t }
                } ?: break
                stock(next.first, next.second, exclude)
                fills++
            }
        }
        val peak = target >= 0.7
        val recentLower = recentArtists.map { it.lowercase() }.toSet()
        val all = chosen.flatMap { pools[it].orEmpty() }
            .filter { it.song.id !in exclude && !SongLikeState.isDisliked(it.song.id) }
            .filter { (it.song.duration ?: 0L) in 60L..(if (isDance(it.genre)) 720L else 480L) || it.song.duration == null }
        val spaced = all.filter { c -> c.song.artists.firstOrNull()?.name?.lowercase() !in recentLower }
        val clean = spaced.filter { !isLiveCut(it.song) }
            .filter { !peak || (!slowHints.containsMatchIn(it.song.title.lowercase()) && ((it.song.duration ?: 0L) <= 330L || isDance(it.genre))) }
        val pool = clean.ifEmpty { spaced.filter { !isLiveCut(it.song) } }.ifEmpty { spaced }.ifEmpty { all }
        val best = pool.maxByOrNull { c ->
            val variety = if (chosen.size > 1 && recentGenres.takeLast(2).count { it == c.genre } >= 2) 0.3 else 0.0
            -abs(c.energy - target) * 4.0 - c.rank * 0.01 - variety + random.nextDouble() * 0.3
        } ?: return@withLock null
        pools[best.genre]?.remove(best)
        runCatching { koinGet<PlayerObserver>()?.tag(best.song.id, "dj/${best.genre}") }
        describe(best.song, best.genre, best.energy)
    }

    /** Songs for a listener's request (search), described with [genre]'s priors. */
    suspend fun search(query: String, genre: String): List<DjTrack> {
        if (query.isBlank()) return emptyList()
        return try {
            val items = repo().searchFiltered(query.trim(), SearchFilter.SONG, allowOfflineFallback = false).items
                .filterIsInstance<SongItem>().filter { !it.isLive }.take(20)
            koinGet<SongCache>()?.putAll(items)
            items.map { describe(it, genre) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("dj: search failed", e)
            emptyList()
        }
    }

    private suspend fun stock(genre: String, tier: Int, exclude: Set<String>) {
        stocked += "$genre:$tier"
        val base = queryOf(genre) ?: return
        val query = when (tier) {
            0 -> "chill $base"
            2 -> "$base party"
            else -> base
        }
        try {
            val repo = repo()
            val top = repo.searchFiltered(query, SearchFilter.SONG, allowOfflineFallback = false).items
                .filterIsInstance<SongItem>().filter { !it.isLive }
            val seed = top.take(6).randomOrNull(random)
            val radio = seed?.let { s -> runCatching { repo.startRadio(s.id) }.getOrDefault(emptyList()) }.orEmpty().filter { !it.isLive }
            val songs = (top + radio).distinctBy { it.id }.filter { it.id !in exclude }
            koinGet<SongCache>()?.putAll(songs)
            val ordered = rank(seed, songs)
            val pool = pools.getOrPut(genre) { ArrayList() }
            val known = pool.mapTo(HashSet()) { it.song.id }
            val fromSearch = top.mapTo(HashSet()) { it.id }
            ordered.forEachIndexed { i, song ->
                if (song.id in known) return@forEachIndexed
                // Radio drifts from the seed's tier, so its energy leans back toward the genre's.
                val e = estimateEnergy(song, genre, tier).let { if (song.id in fromSearch) it else (it + energyOf(genre)) / 2 }
                pool += Candidate(song, genre, e, i)
            }
            TurtleLog.info("dj: stocked $genre tier $tier with ${ordered.size} songs")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("dj: could not stock $genre", e)
        }
    }

    /** Taste order, falling back to YouTube's within a short budget. */
    private suspend fun rank(seed: SongItem?, songs: List<SongItem>): List<SongItem> {
        val taste = koinGet<TasteEngine>()
        if (seed == null || taste == null || songs.size < 2) return songs
        val byId = songs.associateBy { it.id }
        val ranked = withTimeoutOrNull(RANK_TIMEOUT_MS) {
            runCatching { taste.rankExternal(seed.toTrackMeta(), songs.map { it.toTrackMeta() }) }.getOrNull()
        } ?: return songs
        val ordered = ranked.mapNotNull { it.track.youtubeId?.let(byId::get) }
        return ordered + songs.filter { s -> ordered.none { it.id == s.id } }
    }

    private fun repo(): YouTubeRepository = GlobalYouTubeRepository.instance

    private inline fun <reified T : Any> koinGet(): T? = runCatching { GlobalContext.getOrNull()?.getOrNull<T>() }.getOrNull()

    /** A gentle tempo nudge (0.96..1.04) that brings [trackBpm] toward the set's running [masterBpm]. */
    fun tempoNudge(trackBpm: Int, masterBpm: Double): Float {
        if (trackBpm <= 0) return 1f
        // Half-time and double-time count as matched (a 87 BPM hip-hop track sits fine at 174).
        val candidates = listOf(masterBpm, masterBpm / 2, masterBpm * 2)
        val ratio = candidates.map { it / trackBpm }.minBy { abs(it - 1.0) }
        return ratio.coerceIn(1.0 - MAX_NUDGE, 1.0 + MAX_NUDGE).toFloat()
    }

    private const val PICK_TIMEOUT_MS = 15_000L
    private const val RANK_TIMEOUT_MS = 1_500L
    private const val MAX_FILLS_PER_PICK = 2
    private const val MAX_NUDGE = 0.04
}
