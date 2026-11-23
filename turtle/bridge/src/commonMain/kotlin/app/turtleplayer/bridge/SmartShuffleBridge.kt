package app.turtleplayer.bridge

import app.turtleplayer.core.TrackMeta
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.SmartShuffle
import app.turtleplayer.taste.SurfaceTag
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Smart Shuffle for a playlist or album: YouTube's radio for a few of its songs gives the
 * candidates, the taste engine ranks them, and [SmartShuffle] mixes them into the shuffled list.
 * The shuffled queue is attributed on the [PlayerObserver], recommendations with their generator, so
 * their skips and finishes train the engine.
 * Anything slow or failing gives a plain shuffle instead.
 */
internal class SmartShuffleBridge(
    private val taste: TasteEngine,
    private val cache: SongCache,
    private val observer: PlayerObserver,
    private val radio: YouTubeRadioSource = YouTubeRadioSource(cache),
    private val random: Random = Random.Default,
) {
    /**
     * [rest]: the whole playlist when the page loaded only part of it (null when it can't be had). It
     * loads alongside the radio lookups, within the same budget; past it, the loaded songs are used.
     */
    suspend fun shuffle(songs: List<SongItem>, rest: (suspend () -> List<SongItem>?)? = null): List<SongItem> = withContext(Dispatchers.Default) {
        val loaded = songs.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (loaded.size < 2 && rest == null) return@withContext loaded
        try {
            withTimeoutOrNull(BUDGET_MS) { mix(loaded, rest) } ?: plain(loaded).also {
                TurtleLog.info("smart shuffle: plain shuffle (recommendations exceeded ${BUDGET_MS}ms)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("smart shuffle failed, plain shuffle used", e)
            plain(loaded)
        }
    }

    private suspend fun mix(loaded: List<SongItem>, rest: (suspend () -> List<SongItem>?)?): List<SongItem> = coroutineScope {
        val started = TimeSource.Monotonic.markNow()
        val whole = rest?.let { async { it() } }
        // Seeds by different artists, so the mix spans the playlist rather than one corner of it.
        val seeds = loaded.map { it.toTrackMeta() }.shuffled(random).distinctBy { it.artistKey }.take(SEEDS)
        val candidates = candidates(seeds, loaded.mapTo(HashSet()) { it.id })
        val waitMs = (REST_MS - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
        val fetched = whole?.let { w -> withTimeoutOrNull(waitMs) { w.await() }.also { w.cancel() } }
        val own = fetched?.filter { it.id.isNotBlank() }?.distinctBy { it.id }?.takeIf { it.size > loaded.size } ?: loaded
        if (whole != null && own === loaded) TurtleLog.info("smart shuffle: the rest of the playlist did not load; shuffling the ${loaded.size} loaded songs")
        val ownIds = own.mapTo(HashSet()) { it.id }
        mixInto(own, seeds, candidates.filter { it.youtubeId !in ownIds })
    }

    private suspend fun mixInto(own: List<SongItem>, seeds: List<TrackMeta>, candidates: List<TrackMeta>): List<SongItem> {
        val metas = own.map { it.toTrackMeta() }
        if (candidates.isEmpty() || seeds.isEmpty()) return plain(own)
        val ranked = taste.rankExternal(seeds.first(), candidates)
        val arranged = SmartShuffle(random).arrange(metas, ranked, taste.controls, taste.profile())
        val byId = own.associateBy { it.id }
        val out = ArrayList<SongItem>(arranged.size)
        val tags = LinkedHashMap<String, String>()
        for (item in arranged) {
            val id = item.track.youtubeId ?: continue
            val song = byId[id] ?: cache.get(id) ?: continue
            val rec = item.recommendation
            tags[song.id] = SurfaceTag.format(PlayOrigin(PlayPlace.SMART_SHUFFLE, rec?.source, rec?.slot))
            out += song
        }
        observer.attribute(tags)
        TurtleLog.info("smart shuffle: ${own.size} songs, ${out.size - own.size} recommendations from ${candidates.size} candidates")
        return out
    }

    /** Each seed's radio, merged round-robin so every seed is represented near the top. */
    private suspend fun candidates(seeds: List<TrackMeta>, exclude: Set<String>): List<TrackMeta> = coroutineScope {
        val radios = seeds.map { seed ->
            async {
                withTimeoutOrNull(RADIO_MS) {
                    try {
                        radio.related(seed, RADIO_LIMIT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TurtleLog.error("smart shuffle: radio for ${seed.youtubeId} failed", e)
                        null
                    }
                }.orEmpty()
            }
        }.awaitAll()
        val seen = HashSet(exclude)
        val merged = ArrayList<TrackMeta>()
        for (i in 0 until (radios.maxOfOrNull { it.size } ?: 0)) {
            for (r in radios) {
                val t = r.getOrNull(i) ?: continue
                val id = t.youtubeId ?: continue
                if (seen.add(id)) merged += t
            }
        }
        merged
    }

    /** Fallback: random order, no artist twice in a row where avoidable. */
    private fun plain(own: List<SongItem>): List<SongItem> {
        val byId = own.associateBy { it.id }
        val out =
            SmartShuffle(random)
                .arrange(own.map { it.toTrackMeta() }, emptyList())
                .mapNotNull { it.track.youtubeId?.let(byId::get) }
        val tag = SurfaceTag.format(PlayOrigin(PlayPlace.SMART_SHUFFLE))
        observer.attribute(out.associate { it.id to tag })
        return out
    }

    companion object {
        private const val BUDGET_MS = 3_000L
        // How long the rest of a paged playlist may take, leaving time to rank and arrange.
        private const val REST_MS = 2_000L
        private const val RADIO_MS = 2_200L
        private const val SEEDS = 3
        private const val RADIO_LIMIT = 50
    }
}
