package app.turtleplayer.bridge

import app.turtleplayer.core.TrackMeta
import app.turtleplayer.search.OnlineAlbum
import app.turtleplayer.search.OnlineArtist
import app.turtleplayer.search.OnlineResults
import app.turtleplayer.search.OnlineSearchProvider
import app.turtleplayer.search.OnlineTrack
import app.turtleplayer.search.ParsedQuery
import app.turtleplayer.taste.RecommendationSource
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository

/** Audio uploads from an artist's "Topic" channel; YouTube Music's official audio. */
private const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"

internal val SongItem.isOfficialUpload: Boolean
    get() = musicVideoType == MUSIC_VIDEO_TYPE_ATV || musicVideoType == MUSIC_VIDEO_TYPE_OMV

/** YouTube Music search through upstream's repository: the engine's online stage. */
class YouTubeOnlineSearch(
    private val cache: SongCache,
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) : OnlineSearchProvider {
    override suspend fun search(query: ParsedQuery, limit: Int): OnlineResults {
        val page = repository().getSearchSummary(query.raw)
        val items = page.summaries.flatMap { it.items }
        val songs = items.filterIsInstance<SongItem>().distinctBy { it.id }
        cache.putAll(songs)
        return OnlineResults(
            tracks = songs.take(limit).map { OnlineTrack(it.toTrackMeta(), official = it.isOfficialUpload) },
            artists = items.filterIsInstance<ArtistItem>().distinctBy { it.id }.map { OnlineArtist(it.title, browseId = it.id) },
            albums = items.filterIsInstance<AlbumItem>().distinctBy { it.id }.map {
                OnlineAlbum(it.title, it.artists.firstOrNull()?.name.orEmpty(), browseId = it.id, year = it.year?.toIntOrNull())
            },
        )
    }

    override suspend fun suggestions(prefix: String): List<String> =
        repository().getSearchSuggestions(prefix)?.queries.orEmpty()
}

/** YouTube's radio for a track: one candidate source for the taste engine, never the ranker. */
class YouTubeRadioSource(
    private val cache: SongCache,
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) : RecommendationSource {
    override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> {
        val videoId = seed.youtubeId ?: return emptyList()
        val songs = repository().startRadio(videoId).filter { it.id != videoId }
        cache.putAll(songs)
        return songs.take(limit).map { it.toTrackMeta() }
    }
}
