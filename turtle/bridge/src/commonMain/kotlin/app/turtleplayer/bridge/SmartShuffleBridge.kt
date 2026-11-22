package app.turtleplayer.bridge

import app.turtleplayer.core.TrackMeta
import app.turtleplayer.taste.SmartShuffle
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

/**
 * Smart Shuffle for a playlist or album: YouTube's radio for a few of its songs gives the
 * candidates, the taste engine ranks them, and [SmartShuffle] mixes them into the shuffled list.
 * Recommended songs are tagged on the [PlayerObserver], so their skips and finishes train the engine.
 * Anything slow or failing gives a plain shuffle instead.
 */
internal class SmartShuffleBridge(
    private val taste: TasteEngine,
    private val cache: SongCache,
    private val observer: PlayerObserver,
    private val radio: YouTubeRadioSource = YouTubeRadioSource(cache),
    private val random: Random = Random.Default,
) {
    suspend fun shuffle(songs: List<SongItem>): List<SongItem> = withContext(Dispatchers.Default) {
        val own = songs.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (own.size < 2) return@withContext own
        try {
            withTimeoutOrNull(BUDGET_MS) { mix(own) } ?: plain(own).also {
                TurtleLog.info("smart shuffle: plain shuffle (recommendations exceeded ${BUDGET_MS}ms)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("smart shuffle failed, plain shuffle used", e)
            plain(own)
        }
    }

    private suspend fun mix(own: List<SongItem>): List<SongItem> {
        val metas = own.map { it.toTrackMeta() }
        // Seeds by different artists, so the mix spans the playlist rather than one corner of it.
        val seeds = metas.shuffled(random).distinctBy { it.artistKey }.take(SEEDS)
        val candidates = candidates(seeds, own.mapTo(HashSet()) { it.id })
        if (candidates.isEmpty()) return plain(own)
        val ranked = taste.rankExternal(seeds.first(), candidates)
        val arranged = SmartShuffle(random).arrange(metas, ranked, taste.controls, taste.profile())
        val byId = own.associateBy { it.id }
        val out = ArrayList<SongItem>(arranged.size)
        for (item in arranged) {
            val id = item.track.youtubeId ?: continue
            val song = byId[id] ?: cache.get(id) ?: continue
            item.surfaceDetail?.let { observer.tag(song.id, it) }
            out += song
        }
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
        return SmartShuffle(random).arrange(own.map { it.toTrackMeta() }, emptyList())
            .mapNotNull { it.track.youtubeId?.let(byId::get) }
    }

    companion object {
        private const val BUDGET_MS = 3_000L
        private const val RADIO_MS = 2_200L
        private const val SEEDS = 3
        private const val RADIO_LIMIT = 50
    }
}
