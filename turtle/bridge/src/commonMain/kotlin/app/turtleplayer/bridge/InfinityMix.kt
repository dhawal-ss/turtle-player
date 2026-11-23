package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.taste.CandidateSource
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.QueueItem
import app.turtleplayer.taste.Served
import app.turtleplayer.taste.SlotKind
import app.turtleplayer.taste.SongKeys
import app.turtleplayer.taste.SurfaceTag
import app.turtleplayer.taste.TasteEngine
import app.turtleplayer.taste.TasteProfile
import app.turtleplayer.taste.UserControls
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** The Infinity Mix's state, readable before our runtime is up (empty until restored). */
internal val infinityStateFlow = MutableStateFlow(InfinityState())

/** What the Infinity Mix reads from YouTube: a genre's top songs and a song's radio. */
internal interface InfinitySource {
    suspend fun search(query: String): List<SongItem>

    suspend fun radio(seedId: String): List<SongItem>
}

/** [InfinitySource] on upstream's repository. */
internal class YouTubeInfinitySource(
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) : InfinitySource {
    override suspend fun search(query: String): List<SongItem> =
        repository().searchFiltered(query, SearchFilter.SONG, allowOfflineFallback = false).items.filterIsInstance<SongItem>()

    override suspend fun radio(seedId: String): List<SongItem> = repository().startRadio(seedId)
}

/** The listener's thumbs on songs, by id. */
internal interface SongVotes {
    val liked: Set<String>

    fun isDisliked(id: String): Boolean
}

/** [SongVotes] from upstream's like and dislike state. */
internal object UpstreamSongVotes : SongVotes {
    override val liked: Set<String> get() = SongLikeState.likedSongIds

    override fun isDisliked(id: String): Boolean = SongLikeState.isDisliked(id)
}

/** Share of Infinity songs that are favourites at dial [position] (0 familiar .. 1 all new). */
fun infinityFamiliarShare(position: Double): Double = 0.85 - 0.75 * position.coerceIn(0.0, 1.0)

/** A genre or mood the listener can put into the Infinity Mix. The UI owns the display names. */
class InfinityGenre(val id: String, internal val query: String, val mood: Boolean = false)

/** Everything the Infinity Mix can be built from. Ids are stable: they are saved. */
object InfinityGenres {
    val all: List<InfinityGenre> = listOf(
        InfinityGenre("pop", "pop hits"),
        InfinityGenre("hiphop", "hip hop hits"),
        InfinityGenre("rnb", "r&b hits"),
        InfinityGenre("rock", "rock anthems"),
        InfinityGenre("indie", "indie hits"),
        InfinityGenre("alternative", "alternative rock hits"),
        InfinityGenre("electronic", "electronic music hits"),
        InfinityGenre("house", "house music"),
        InfinityGenre("techno", "techno"),
        InfinityGenre("dance", "dance pop hits"),
        InfinityGenre("lofi", "lofi hip hop beats"),
        InfinityGenre("jazz", "jazz classics"),
        InfinityGenre("soul", "soul classics"),
        InfinityGenre("funk", "funk classics"),
        InfinityGenre("classical", "classical music essentials"),
        InfinityGenre("country", "country hits"),
        InfinityGenre("latin", "latin hits"),
        InfinityGenre("reggaeton", "reggaeton hits"),
        InfinityGenre("afrobeats", "afrobeats hits"),
        InfinityGenre("kpop", "k-pop hits"),
        InfinityGenre("metal", "metal anthems"),
        InfinityGenre("punk", "punk rock"),
        InfinityGenre("folk", "indie folk"),
        InfinityGenre("blues", "blues classics"),
        InfinityGenre("reggae", "reggae classics"),
        InfinityGenre("ambient", "ambient music"),
        InfinityGenre("bollywood", "bollywood hits"),
        InfinityGenre("punjabi", "punjabi hits"),
        InfinityGenre("chill", "chill vibes songs", mood = true),
        InfinityGenre("focus", "focus instrumental music", mood = true),
        InfinityGenre("workout", "workout songs", mood = true),
        InfinityGenre("party", "party anthems", mood = true),
        InfinityGenre("sleep", "sleep calm music", mood = true),
        InfinityGenre("romance", "love songs", mood = true),
        InfinityGenre("sad", "sad songs", mood = true),
        InfinityGenre("feelgood", "feel good songs", mood = true),
        InfinityGenre("throwback", "2000s hits", mood = true),
        InfinityGenre("roadtrip", "road trip songs", mood = true),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): InfinityGenre? = byId[id]
}

/** Why the Infinity Mix picked a song: shown under the title, so the listener sees the dial work. */
sealed interface InfinityWhy {
    /** One of your favourites (liked, replayed or finished often). */
    data object Familiar : InfinityWhy

    /** New to you, from a genre or mood you picked. */
    data class Genre(val id: String) : InfinityWhy

    /** New to you, because you liked [title] while the mix played. */
    data class MoreLike(val title: String) : InfinityWhy

    /** New to you, from your listening taste (no genre picked). */
    data object ForYou : InfinityWhy
}

/** What the Infinity sheet shows: the picks, how each has been tuned, and how much feedback we have. */
data class InfinityState(
    val genres: List<String> = emptyList(),
    val weights: Map<String, Double> = emptyMap(),
    val likes: Int = 0,
    val passes: Int = 0,
    val why: Map<String, InfinityWhy> = emptyMap(),
    /** True once the listener has saved picks (even none), so the sheet stops opening on Play. */
    val configured: Boolean = false,
)

/**
 * The Infinity Mix: an endless queue that blends the listener's favourites with new music from the
 * genres and moods they picked. The discovery dial sets the blend (Familiar is mostly favourites,
 * New is mostly new), likes pull more of a genre and songs like the liked one, and passes ("less
 * like this", quick skips) push a genre down. Picks and tuning are saved, so the mix keeps learning.
 */
class InfinityMix internal constructor(
    private val store: SqlitePlayEventStore,
    private val taste: TasteEngine,
    private val cache: SongCache,
    private val observer: PlayerObserver,
    private val source: InfinitySource = YouTubeInfinitySource(),
    private val random: Random = Random.Default,
    private val votes: SongVotes = UpstreamSongVotes,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val _state = infinityStateFlow
    val state: StateFlow<InfinityState> = _state.asStateFlow()

    /** New candidates per genre id, best first. */
    private val pools = HashMap<String, ArrayDeque<SongItem>>()
    /** Songs liked while the mix played: each seeds a radio of songs like it. */
    private val likedSeeds = ArrayDeque<SongItem>()
    private val moreLike = ArrayDeque<Pair<SongItem, String>>()

    /** "For you" picks from the taste engine, each with the queue item that says which generator proposed it. */
    private var forYou = ArrayDeque<Pair<SongItem, QueueItem>>()

    // Queue items of the "for you" songs picked in the current batch, for their play attribution.
    private val forYouPicked = HashMap<String, QueueItem>()

    /** How each pooled genre and "more like" song was ordered, by [servedKey]; only songs still in a pool. */
    private val servedBy = HashMap<String, Served>()

    // How the genre and "more like" songs picked in the current batch were ordered, for their play attribution.
    private val discoveryPicked = HashMap<String, Served>()

    /** Everything served this session, so nothing repeats, an upload twin included. */
    private val served = Taken()
    private val passedArtists = HashSet<ArtistKey>()

    // One song per recording across uploads ("X - Topic" twins); version variants (live, remix) stay apart.
    private val songKeys = SongKeys()
    private var restored = false
    // Genre stock-ups allowed in the current batch, so the first songs are not held up by every genre at once.
    private var fillBudget = 0

    suspend fun restore() = mutex.withLock { restoreLocked() }

    private suspend fun restoreLocked() {
        if (restored) return
        restored = true
        val savedGenres = runCatching { store.pref(PREF_GENRES) }.getOrNull()
        val genres = savedGenres
            ?.split(',')?.map { it.trim() }?.filter { InfinityGenres[it] != null }.orEmpty()
        val weights = runCatching { store.pref(PREF_WEIGHTS) }.getOrNull()
            ?.split(';')?.mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { k to it } } }
            ?.toMap().orEmpty()
        val likes = runCatching { store.pref(PREF_LIKES)?.toIntOrNull() }.getOrNull() ?: 0
        val passes = runCatching { store.pref(PREF_PASSES)?.toIntOrNull() }.getOrNull() ?: 0
        _state.value = _state.value.copy(genres = genres, weights = weights, likes = likes, passes = passes, configured = savedGenres != null && savedGenres != UNSET)
    }

    /** Saves the listener's picks. Pools for genres they dropped are let go. */
    suspend fun setGenres(ids: List<String>) = mutex.withLock {
        restoreLocked()
        val clean = ids.distinct().filter { InfinityGenres[it] != null }
        pools.keys.retainAll(clean.toSet())
        _state.value = _state.value.copy(genres = clean, configured = true)
        runCatching { store.setPref(PREF_GENRES, clean.joinToString(",")) }.onFailure { TurtleLog.error("infinity: could not save genres", it) }
    }

    /** Opens a fresh mix: forgets what this session served, then builds the first songs. */
    suspend fun start(): List<SongItem> = mutex.withLock {
        restoreLocked()
        served.clear()
        passedArtists.clear()
        forYou.clear()
        batchLocked(FIRST_BATCH, emptySet())
    }

    /** The next [count] songs, skipping anything already [queued]. */
    suspend fun next(count: Int, queued: Set<String>): List<SongItem> = mutex.withLock {
        restoreLocked()
        batchLocked(count, queued)
    }

    /** A like (true) or a pass (false) on a song. Tunes its genre and seeds "more like this". */
    suspend fun feedback(song: SongItem, liked: Boolean) = mutex.withLock {
        restoreLocked()
        val why = _state.value.why[song.id]
        val s = _state.value
        val weights = s.weights.toMutableMap()
        if (why is InfinityWhy.Genre) {
            val w = weights[why.id] ?: 1.0
            weights[why.id] = if (liked) (w + LIKE_STEP).coerceAtMost(MAX_WEIGHT) else (w * PASS_FACTOR).coerceAtLeast(MIN_WEIGHT)
        }
        if (liked) {
            likedSeeds.addLast(song)
            while (likedSeeds.size > 6) likedSeeds.removeFirst()
        } else {
            // A passed song builds nothing more, and that artist's other queued candidates go for this session.
            likedSeeds.removeAll { it.id == song.id }
            artistOf(song)?.let { artist ->
                passedArtists += artist
                pools.values.forEach { pool -> pool.removeAll { artistOf(it) == artist } }
                moreLike.removeAll { artistOf(it.first) == artist }
            }
        }
        _state.value = s.copy(weights = weights, likes = s.likes + if (liked) 1 else 0, passes = s.passes + if (liked) 0 else 1)
        saveTuningLocked()
    }

    /** Finished and quick-skipped plays of mix songs gently tune their genre, without any tap. */
    suspend fun onPlayEvent(event: PlayEvent) {
        val id = event.track.youtubeId ?: return
        mutex.withLock {
            val why = _state.value.why[id] ?: return
            // A new song kept for a minute is a find: songs like it come next, without a tap.
            val kept = why != InfinityWhy.Familiar && !event.isQuickSkip && event.msPlayed >= KEEP_MS
            if (kept) cache.get(id)?.let { song ->
                if (likedSeeds.none { it.id == id }) likedSeeds.addLast(song)
                while (likedSeeds.size > 6) likedSeeds.removeFirst()
            }
            if (why !is InfinityWhy.Genre) return
            val w = _state.value.weights[why.id] ?: 1.0
            val next = when {
                event.isQuickSkip -> (w * SKIP_FACTOR).coerceAtLeast(MIN_WEIGHT)
                event.isFinished -> (w + FINISH_STEP).coerceAtMost(MAX_WEIGHT)
                kept -> (w + KEEP_STEP).coerceAtMost(MAX_WEIGHT)
                else -> return
            }
            _state.value = _state.value.copy(weights = _state.value.weights + (why.id to next))
            saveTuningLocked()
        }
    }

    /**
     * Start fresh: forgets the mix's tuning (genre weights, likes, passes, songs to build on) and
     * the picks, so the listener can rebuild it from scratch. Listening history is kept.
     */
    suspend fun startFresh() = mutex.withLock {
        pools.clear()
        likedSeeds.clear()
        moreLike.clear()
        forYou.clear()
        servedBy.clear()
        served.clear()
        passedArtists.clear()
        _state.value = InfinityState()
        runCatching {
            store.setPref(PREF_GENRES, UNSET)
            for (k in listOf(PREF_WEIGHTS, PREF_LIKES, PREF_PASSES)) store.setPref(k, "")
        }.onFailure { TurtleLog.error("infinity: could not reset", it) }
        restored = true
        TurtleLog.info("infinity: started fresh")
    }

    private suspend fun saveTuningLocked() {
        val s = _state.value
        runCatching {
            store.setPref(PREF_WEIGHTS, s.weights.entries.joinToString(";") { "${it.key}=${(it.value * 1000).toLong() / 1000.0}" })
            store.setPref(PREF_LIKES, s.likes.toString())
            store.setPref(PREF_PASSES, s.passes.toString())
        }.onFailure { TurtleLog.error("infinity: could not save tuning", it) }
    }

    // ---- Building batches ----------------------------------------------------------------------

    private suspend fun batchLocked(count: Int, queued: Set<String>): List<SongItem> {
        val picks = ArrayList<SongItem>(count)
        val why = LinkedHashMap<String, InfinityWhy>()
        val taken =
            Taken().apply {
                for (id in queued) add(id, cache.get(id))
                addAll(served)
            }
        val dial = discoveryDialState.value.coerceIn(0.0, 1.0)
        // Familiar 85% of songs at "Familiar", about half at the centre, 10% at "All new".
        val familiarShare = infinityFamiliarShare(dial)
        // The one "not interested" list (Turtle's own and upstream's block list), read once per batch.
        val profile = runCatching { taste.profile() }.getOrNull()
        val ruled = taste.controls
        val batch = Batch(taken, Exclusions(ruled, ruledSongs(ruled, profile)), profile)
        val familiar = familiarCandidates(batch).toCollection(ArrayDeque())
        fillBudget = FILLS_PER_BATCH
        forYouPicked.clear()
        discoveryPicked.clear()
        var acc = if (familiarShare >= 0.5) 0.5 else 0.0
        var attempts = 0
        withTimeoutOrNull(BATCH_TIMEOUT_MS) {
            while (picks.size < count && attempts++ < count * 4) {
                acc += familiarShare
                val wantFamiliar = acc >= 1.0 - 1e-9
                if (wantFamiliar) acc -= 1.0
                val pick = if (wantFamiliar && familiar.isNotEmpty()) {
                    takeSpaced(familiar, batch)?.let { it to InfinityWhy.Familiar }
                } else {
                    discoveryPick(batch)
                        ?: takeSpaced(familiar, batch)?.let { it to InfinityWhy.Familiar }
                } ?: continue
                val (song, reason) = pick
                picks += song
                why[song.id] = reason
                taken.add(song)
                artistOf(song)?.let { batch.recentArtists.addLast(it) }
                if (batch.recentArtists.size > ARTIST_SPACING) batch.recentArtists.removeFirst()
            }
        }
        for (song in picks) served.add(song)
        pruneServedBy()
        for ((id, reason) in why) observer.tag(id, SurfaceTag.format(originOf(reason, forYouPicked[id], discoveryPicked[id])))
        val merged = LinkedHashMap(_state.value.why).apply { putAll(why) }
        while (merged.size > MAX_WHY) merged.remove(merged.keys.first())
        _state.value = _state.value.copy(why = merged)
        TurtleLog.info("infinity: ${picks.size} songs (${why.values.count { it == InfinityWhy.Familiar }} familiar, dial ${(dial * 100).toInt()}%)")
        return picks
    }

    /**
     * Where an Infinity pick came from; a "for you" pick carries the generator and slot of its [item],
     * a genre or "more like" pick how its pool was ordered ([how]).
     */
    private fun originOf(
        why: InfinityWhy,
        item: QueueItem?,
        how: Served?,
    ): PlayOrigin {
        val radio = PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, served = how)
        return when (why) {
            InfinityWhy.Familiar -> PlayOrigin(PlayPlace.INFINITY, CandidateSource.LIBRARY, SlotKind.FAMILIAR, "familiar")
            InfinityWhy.ForYou -> PlayOrigin(PlayPlace.INFINITY, item?.source, item?.slot, "foryou")
            is InfinityWhy.Genre -> radio.copy(row = "genre:${why.id}")
            is InfinityWhy.MoreLike -> radio.copy(row = "morelike")
        }
    }

    /** [servedBy]'s key: a song can sit in two pools (two genres, or a genre and "more like") ordered differently. */
    private fun servedKey(
        pool: String,
        id: String,
    ) = "$pool/$id"

    /**
     * Notes how [song] entered [pool]. While the pool still holds an [earlier] copy, that copy is taken
     * first, so its entry stays; otherwise any entry is a leftover from a copy purged since and is replaced.
     */
    private fun noteServed(
        pool: String,
        song: SongItem,
        how: Served,
        earlier: Boolean,
    ) {
        val key = servedKey(pool, song.id)
        if (earlier) servedBy.getOrPut(key) { how } else servedBy[key] = how
    }

    /** Moves a picked song's entry from [servedBy] to [discoveryPicked]. */
    private fun pickedFrom(
        pool: String,
        song: SongItem,
    ) {
        servedBy.remove(servedKey(pool, song.id))?.let { discoveryPicked[song.id] = it }
    }

    /** Drops [servedBy] entries of songs no longer in any pool (passed, ruled out, dropped genres), so it stays pool-sized. */
    private fun pruneServedBy() {
        val pooled = HashSet<String>()
        for ((g, pool) in pools) pool.forEach { pooled += servedKey(g, it.id) }
        moreLike.forEach { pooled += servedKey(MORE_LIKE_POOL, it.first.id) }
        servedBy.keys.retainAll(pooled)
    }

    /** Songs used up: by id and by recording, so an upload twin of a used song counts as used. */
    private inner class Taken {
        private val ids = HashSet<String>()
        private val songs = HashSet<TrackKey>()

        fun add(song: SongItem) = add(song.id, song)

        /** [song] is [id]'s song when known; an id alone still stops that upload. */
        fun add(
            id: String,
            song: SongItem?,
        ) {
            ids += id
            if (song != null) songs += songOf(song)
        }

        fun addAll(other: Taken) {
            ids += other.ids
            songs += other.songs
        }

        fun clear() {
            ids.clear()
            songs.clear()
        }

        operator fun contains(song: SongItem): Boolean = song.id in ids || songOf(song) in songs

        operator fun contains(id: String): Boolean = id in ids
    }

    /**
     * What the listener ruled out, read once per batch: dislikes, "not interested" songs and every
     * upload of them the profile or song cache knows or a response brings ([learn]), muted or blocked
     * artists ([ruled]), and artists passed in this session.
     */
    private inner class Exclusions(
        private val ruled: UserControls,
        private val ruledSongs: MutableSet<TrackKey>,
    ) {
        operator fun contains(song: SongItem): Boolean {
            val meta = song.toTrackMeta()
            if (ruledByVote(song) || songKeys.of(meta) in ruledSongs) return true
            val artist = meta.artistKey.takeIf { meta.primaryArtist.isNotBlank() } ?: return false
            return artist in ruled.mutedArtists || artist in passedArtists
        }

        /** Notes the recordings of [songs] ruled out by id, so another upload in the same response is caught too. */
        fun learn(songs: List<SongItem>) {
            for (song in songs) if (ruledByVote(song)) ruledSongs += songOf(song)
        }

        private fun ruledByVote(song: SongItem) = votes.isDisliked(song.id) || TrackKey.youtube(song.id) in ruled.lessLikeThisTracks
    }

    /** One batch's view: what is used up, what is ruled out, the profile, and the artists just picked. */
    private inner class Batch(
        val taken: Taken,
        val excluded: Exclusions,
        val profile: TasteProfile?,
    ) {
        val recentArtists = ArrayDeque<ArtistKey>()

        /** Can be picked: neither used up nor ruled out. */
        fun usable(song: SongItem): Boolean = !song.isLive && song !in taken && song !in excluded

        /**
         * New music for the discovery slots: not something the listener already plays a lot, nor another
         * upload of a song they have heard (its plays are recorded under the upload they used).
         */
        fun fresh(song: SongItem): Boolean {
            val stats = profile?.track(TrackKey.youtube(song.id))
            return if (stats != null) stats.plays < 3 else profile?.hasHeard(song.toTrackMeta()) != true
        }

        /** Its artist did not just play. */
        fun spaced(song: SongItem): Boolean = artistOf(song).let { it == null || it !in recentArtists }
    }

    /** The recordings of [ruled]'s "not interested" songs, as far as the profile or the song cache knows them. */
    private suspend fun ruledSongs(
        ruled: UserControls,
        profile: TasteProfile?,
    ): MutableSet<TrackKey> =
        ruled.lessLikeThisTracks.mapNotNullTo(HashSet()) { key ->
            val meta = profile?.track(key)?.meta ?: if (key.scheme == TrackKey.YOUTUBE) cache.get(key.id)?.toTrackMeta() else null
            meta?.let(songKeys::of)
        }

    private fun songOf(song: SongItem): TrackKey = songKeys.of(song.toTrackMeta())

    /** The song's artist as the taste engine keys it; null for a song with no artist, which spaces and passes as no one. */
    private fun artistOf(song: SongItem): ArtistKey? = song.toTrackMeta().takeIf { it.primaryArtist.isNotBlank() }?.artistKey

    /** Takes the first usable candidate whose artist did not just play; null when none fits. Unusable ones are dropped. */
    private fun takeSpaced(
        pool: ArrayDeque<SongItem>,
        batch: Batch,
    ): SongItem? = takeSpaced(pool, batch) { it }

    private fun <T> takeSpaced(
        pool: ArrayDeque<T>,
        batch: Batch,
        songOf: (T) -> SongItem,
    ): T? {
        pool.removeAll { !batch.usable(songOf(it)) }
        val i = pool.indexOfFirst { batch.spaced(songOf(it)) }
        return when {
            i >= 0 -> pool.removeAt(i)
            else -> null
        }
    }

    /** The listener's favourites, best first with some shuffle so no two mixes open the same way. */
    private suspend fun familiarCandidates(batch: Batch): List<SongItem> {
        val profile = batch.profile
        val now = clock.nowMs()
        val scored = HashMap<String, Double>()
        profile?.tracks?.values?.forEach { t ->
            val id = t.meta.youtubeId ?: return@forEach
            if (t.lessLikeThis || t.affinity <= 0) return@forEach
            // Not something from the last few hours: favourites should feel like a welcome return.
            if (now - t.lastPlayedMs < RECENT_GAP_MS) return@forEach
            scored[id] = t.affinity * (if (t.isLoved) 1.5 else 1.0)
        }
        for (id in votes.liked) scored[id] = (scored[id] ?: 0.0) + LIKED_BONUS
        return scored.entries
            .filter { it.key !in batch.taken && !votes.isDisliked(it.key) }
            // Draw each song's shuffle once: a key that changes between comparisons breaks the sort.
            .map { it.key to it.value * (0.6 + random.nextDouble() * 0.8) }
            .sortedByDescending { it.second }
            .take(FAMILIAR_POOL)
            .mapNotNull { cache.get(it.first) }
            .filter(batch::usable)
    }

    /** One new song: from a liked song's radio, a picked genre (by tuned weight), or your taste. */
    private suspend fun discoveryPick(batch: Batch): Pair<SongItem, InfinityWhy>? {
        val genres = _state.value.genres
        val useMoreLike = likedSeeds.isNotEmpty() && random.nextDouble() < MORE_LIKE_CHANCE
        if (useMoreLike) {
            moreLike.removeAll { !batch.usable(it.first) }
            fillMoreLike(batch)
            moreLike.removeAll { !batch.usable(it.first) }
            val i = moreLike.indexOfFirst { batch.spaced(it.first) }
            if (i >= 0) {
                moreLike.removeAt(i).let { (song, seed) ->
                    pickedFrom(MORE_LIKE_POOL, song)
                    return song to InfinityWhy.MoreLike(seed)
                }
            }
        }
        if (genres.isNotEmpty()) {
            val order = weightedOrder(genres)
            for (g in order) {
                // Count only usable songs: a pool of ruled-out ones must not stop a refill.
                pools[g]?.removeAll { !batch.usable(it) }
                val stocked = pools[g]?.size ?: 0
                val pool = if (stocked >= POOL_LOW || fillBudget-- > 0) fillGenre(g, batch) else pools[g] ?: continue
                takeSpaced(pool, batch)?.let {
                    pickedFrom(g, it)
                    return it to InfinityWhy.Genre(g)
                }
            }
        }
        forYou.removeAll { !batch.usable(it.first) }
        if (forYou.isEmpty()) {
            // Read-only picks: refilling never resets the session or Up Next of the queue that is playing.
            val picks = runCatching { taste.forYou(FOR_YOU_BATCH) }.getOrDefault(emptyList())
            forYou =
                picks
                    .mapNotNull { item -> item.track.youtubeId?.let { id -> cache.get(id) }?.let { it to item } }
                    .filter { batch.usable(it.first) }
                    .toCollection(ArrayDeque())
        }
        return takeSpaced(forYou, batch) { it.first }?.let { (song, item) ->
            forYouPicked[song.id] = item
            song to InfinityWhy.ForYou
        }
    }

    /** Genres in a random order biased by their tuned weight (a like makes a genre come up more). */
    private fun weightedOrder(genres: List<String>): List<String> {
        val weights = _state.value.weights
        return genres.map { g -> g to -kotlin.math.ln(random.nextDouble().coerceAtLeast(1e-9)) / (weights[g] ?: 1.0) }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * Keeps a genre's pool stocked: YouTube Music's top songs for the genre seed radios, which are
     * then ordered by the taste engine. After a like in that genre, the liked song seeds the next refill.
     */
    private suspend fun fillGenre(
        id: String,
        batch: Batch,
    ): ArrayDeque<SongItem> {
        val pool = pools.getOrPut(id) { ArrayDeque() }
        if (pool.size >= POOL_LOW) return pool
        val genre = InfinityGenres[id] ?: return pool
        try {
            val likedHere = likedSeeds.lastOrNull { (_state.value.why[it.id] as? InfinityWhy.Genre)?.id == id && it !in batch.excluded }
            val seeds = buildList {
                if (likedHere != null) add(likedHere)
                val hits = source.search(genre.query)
                cache.putAll(hits)
                batch.excluded.learn(hits)
                // A ruled-out hit neither seeds a radio nor goes in; one already played may still seed.
                val top = hits.filter { !it.isLive && it !in batch.excluded }
                // Search hits are the genre's best-known songs: a couple go straight in, two seed radios.
                addAll(top.shuffled(random).take(SEEDS_PER_FILL - size))
                val pooled = pool.mapTo(HashSet(), ::songOf)
                top
                    .filter { batch.usable(it) && batch.fresh(it) && songOf(it) !in pooled }
                    .distinctBy(::songOf)
                    .shuffled(random)
                    .take(2)
                    .forEach { song ->
                        val earlier = pool.any { it.id == song.id }
                        pool.addLast(song)
                        noteServed(id, song, Served.DIRECT, earlier)
                    }
            }
            val radios =
                seeds
                    .flatMap { seed -> runCatching { source.radio(seed.id) }.getOrDefault(emptyList()) }
                    .filter { !it.isLive }
            cache.putAll(radios)
            batch.excluded.learn(radios)
            // One upload per recording, never the seeds again, nothing already pooled, used or ruled out.
            val skip = seeds.mapTo(HashSet(), ::songOf).apply { pool.mapTo(this, ::songOf) }
            val fresh =
                radios
                    .distinctBy(::songOf)
                    .filter { songOf(it) !in skip && batch.usable(it) && batch.fresh(it) }
            val (ranked, how) = rankByTaste(seeds.firstOrNull(), fresh)
            ranked.forEach { song ->
                val earlier = pool.any { it.id == song.id }
                pool.addLast(song)
                noteServed(id, song, how, earlier)
            }
            TurtleLog.info("infinity: genre $id stocked with ${ranked.size} songs")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("infinity: could not stock genre $id", e)
        }
        return pool
    }

    private suspend fun fillMoreLike(batch: Batch) {
        if (moreLike.size >= POOL_LOW) return
        // A liked song the listener has since ruled out seeds nothing.
        likedSeeds.removeAll { it in batch.excluded }
        val seed = likedSeeds.removeLastOrNull() ?: return
        val radio = runCatching { source.radio(seed.id) }.getOrDefault(emptyList())
        cache.putAll(radio)
        batch.excluded.learn(radio)
        val skip = moreLike.mapTo(hashSetOf(songOf(seed))) { songOf(it.first) }
        val songs = radio.distinctBy(::songOf).filter { songOf(it) !in skip && batch.usable(it) }
        val (ranked, how) = rankByTaste(seed, songs)
        ranked.take(MORE_LIKE_TAKE).forEach { song ->
            val earlier = moreLike.any { it.first.id == song.id }
            moreLike.addLast(song to seed.title)
            noteServed(MORE_LIKE_POOL, song, how, earlier)
        }
        likedSeeds.addFirst(seed)
    }

    /**
     * Taste order for new candidates, falling back to YouTube's order if ranking takes too long or
     * fails; also says which of those happened, for the songs' play attribution.
     */
    private suspend fun rankByTaste(
        seed: SongItem?,
        songs: List<SongItem>,
    ): Pair<List<SongItem>, Served> {
        if (seed == null || songs.size < 2) return songs to Served.PROVIDER
        val byId = songs.associateBy { it.id }
        var failed = false
        val ranked = withTimeoutOrNull(RANK_TIMEOUT_MS) {
            // The timeout cancels a suspended ranking with a CancellationException: that is a timeout, not a failure.
            runCatching { taste.rankExternal(seed.toTrackMeta(), songs.map { it.toTrackMeta() }) }
                .onFailure { failed = it !is CancellationException }
                .getOrNull()
        } ?: return songs to if (failed) Served.FAILED else Served.TIMEOUT
        return ranked.mapNotNull { it.track.youtubeId?.let(byId::get) } to Served.RANKED
    }

    private companion object {
        const val PREF_GENRES = "infinity_genres"
        const val PREF_WEIGHTS = "infinity_weights"
        const val PREF_LIKES = "infinity_likes"
        const val PREF_PASSES = "infinity_passes"
        const val FIRST_BATCH = 12
        const val FAMILIAR_POOL = 80
        const val LIKED_BONUS = 1.0
        const val RECENT_GAP_MS = 3 * 60 * 60 * 1000L
        const val ARTIST_SPACING = 3
        const val POOL_LOW = 4
        const val SEEDS_PER_FILL = 2
        const val FILLS_PER_BATCH = 2
        const val MORE_LIKE_CHANCE = 0.35
        const val MORE_LIKE_TAKE = 12
        const val LIKE_STEP = 0.2
        const val FINISH_STEP = 0.03
        const val KEEP_STEP = 0.06
        const val KEEP_MS = 60_000L
        /** Saved genres after Start fresh: not configured, so Play opens the picker again. */
        const val UNSET = "~"
        const val PASS_FACTOR = 0.75
        const val SKIP_FACTOR = 0.92
        const val MIN_WEIGHT = 0.15
        const val MAX_WEIGHT = 3.0
        const val MAX_WHY = 400
        const val BATCH_TIMEOUT_MS = 20_000L
        const val RANK_TIMEOUT_MS = 1_500L
        const val FOR_YOU_BATCH = 20

        /** [servedKey]'s pool name for "more like" songs; genre ids never take it. */
        const val MORE_LIKE_POOL = "morelike"
    }
}
