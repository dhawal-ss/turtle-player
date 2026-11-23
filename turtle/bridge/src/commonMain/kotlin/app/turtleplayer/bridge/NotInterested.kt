package app.turtleplayer.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.turtleplayer.core.ArtistKey
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.displayTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile

/** What "Not interested" was chosen on. */
sealed interface NotInterested {
    val title: String

    data class Song(
        val song: SongItem,
    ) : NotInterested {
        override val title get() = song.title
    }

    /** Upstream's artist menu is titled with the alias-resolved name, so the latch must match that. */
    data class Artist(
        val artist: ArtistItem,
    ) : NotInterested {
        override val title get() = artist.displayTitle
    }

    /** One of Turtle's mixes on Home ([homeKey] "turtle:<shelf id>"). */
    data class Mix(
        val homeKey: String,
        override val title: String,
    ) : NotInterested
}

enum class HiddenKind { SONG, ARTIST, MIX }

/** One row of the Hidden page. [key]: TrackKey value, ArtistKey value or Reason.key. [artist]: a song's primary artist. */
data class HiddenEntry(
    val kind: HiddenKind,
    val key: String,
    val title: String,
    val artist: String? = null,
    val thumbnail: String? = null,
)

/** What Home must not show right now (YouTube ids, artist keys, Turtle row keys). Compose state, read during composition. */
data class HiddenSet(
    val songIds: Set<String> = emptySet(),
    val artists: Set<ArtistKey> = emptySet(),
    val homeShelfKeys: Set<String> = emptySet(),
) {
    fun hides(item: YTItem): Boolean =
        when (item) {
            is SongItem -> item.id in songIds || item.artists.firstOrNull()?.let { ArtistKey.of(it.name) in artists } == true
            is ArtistItem -> ArtistKey.of(item.title) in artists
            else -> false
        }

    fun hidesShelf(homeKey: String): Boolean = homeKey in homeShelfKeys
}

/**
 * Display data for hidden entries, saved in our pref table ("hidden_items"). First line "turtle-hidden 1",
 * then one entry per line: kind, key, title, artist, thumbnail, tab-separated ("" for null). Tabs and
 * newlines inside fields become spaces. Never throws: an unknown header gives empty; unknown kinds skip the line.
 */
internal object HiddenRecords {
    private const val HEADER = "turtle-hidden 1"
    private const val FIELDS = 5

    fun encode(entries: List<HiddenEntry>): String =
        buildString {
            append(HEADER)
            for (e in entries) {
                append('\n')
                append(listOf(e.kind.name, e.key, e.title, e.artist.orEmpty(), e.thumbnail.orEmpty()).joinToString("\t") { clean(it) })
            }
        }

    fun decode(text: String?): List<HiddenEntry> {
        val lines = text?.split('\n') ?: return emptyList()
        if (lines.firstOrNull() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != FIELDS || f[1].isEmpty()) return@mapNotNull null
            val kind = HiddenKind.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
            HiddenEntry(kind, f[1], f[2], f[3].ifEmpty { null }, f[4].ifEmpty { null })
        }
    }

    private fun clean(field: String) = field.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/** Which Home item the menu about to open belongs to, so Shell's menu header offers "Not interested" for it only. */
class HomeMenuLatch(
    private val nowMs: () -> Long,
    private val windowMs: Long = WINDOW_MS,
) {
    private class Armed(
        val target: NotInterested,
        val atMs: Long,
    )

    @Volatile
    private var armed: Armed? = null

    fun arm(target: NotInterested) {
        armed = Armed(target, nowMs())
    }

    fun disarm() {
        armed = null
    }

    /** The armed target if a menu titled [title] opens within [windowMs]; consumed either way, so no later menu gets it. */
    fun claim(title: String): NotInterested? {
        val a = armed ?: return null
        armed = null
        return a.target.takeIf { it.title == title && nowMs() - a.atMs <= windowMs }
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}

/** The Hidden page's rows; published by the runtime. */
internal val hiddenEntriesState = MutableStateFlow<List<HiddenEntry>>(emptyList())

/** What Home hides; Compose state, so Home recomposes when it changes. */
internal var hiddenSetState: HiddenSet by mutableStateOf(HiddenSet())
