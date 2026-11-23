package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/** One cadence's kept shelves: the period they were built in (a local day, or the Monday of a week) and the shelves as built. */
data class KeptShelves(val cadence: ShelfCadence, val period: Long, val shelves: List<Shelf>)

/**
 * The kept shelves' lines in [TasteState]'s text, after its own lines:
 *
 * ```
 * shelves\t1
 * kept\t<cadence>␟<period>
 * shelf\t<id>␟<kind>␟<reason>
 * item\t<trackKey>␟<title>␟<artists>␟<album>␟<durationMs>␟<releasedAtMs>␟<youtubeId>␟<isrc>␟<explicit>␟<popularity>␟<source>␟<slot>␟<score>␟<reason>
 * ```
 *
 * A `shelf` belongs to the latest `kept`, an `item` to the latest `shelf`. Fields are positional,
 * so an item (or shelf) whose text contains a reserved character is skipped whole rather than
 * written with a field missing. Reading drops only the lines it cannot read, and their children.
 * Enum names and reason tags are persisted; never rename them.
 */
internal object KeptShelvesCodec {
    private const val PAYLOAD = "shelves"
    private const val PAYLOAD_VERSION = "1"
    private const val KEPT = "kept"
    private const val SHELF = "shelf"
    private const val ITEM_LINE = "item"

    /** Separates a line's fields (the same separator as [TasteState]'s lists). */
    private const val ITEM = '\u001f'
    /** Separates the parts of a reason. */
    private const val PAIR = '\u001d'
    /** Separates entries of a list inside one field. */
    private const val LIST = '\u001e'
    /** A whole field that is null. */
    private const val NULL = '\u001c'
    private const val NULL_FIELD = NULL.toString()

    private fun safe(text: String) = text.none { it == '\n' || it == '\t' || it == ITEM || it == PAIR || it == LIST || it == NULL }

    fun encode(kept: List<KeptShelves>): List<String> = buildList {
        add("$PAYLOAD\t$PAYLOAD_VERSION")
        for (group in kept) {
            add(line(KEPT, listOf(group.cadence.name, group.period.toString())))
            for (shelf in group.shelves) {
                val reason = encodeReason(shelf.reason) ?: continue
                if (!safe(shelf.id)) continue
                add(line(SHELF, listOf(shelf.id, shelf.kind.name, reason)))
                shelf.items.forEach { item -> encodeItem(item)?.let { add(line(ITEM_LINE, it)) } }
            }
        }
    }

    fun decode(lines: List<String>): List<KeptShelves> {
        val start = lines.indexOfFirst { it.substringBefore('\t') == PAYLOAD }
        if (start < 0 || lines[start] != "$PAYLOAD\t$PAYLOAD_VERSION") return emptyList()
        val groups = ArrayList<GroupBuilder>()
        var group: GroupBuilder? = null
        var shelf: ShelfBuilder? = null
        for (l in lines.subList(start + 1, lines.size)) {
            val tab = l.indexOf('\t')
            if (tab < 0) continue
            val fields = l.substring(tab + 1).split(ITEM)
            when (l.substring(0, tab)) {
                KEPT -> {
                    shelf = null
                    group = runCatching { decodeKept(fields) }.getOrNull()?.also { groups += it }
                }
                SHELF -> {
                    val g = group
                    shelf = if (g == null) null else runCatching { decodeShelf(fields) }.getOrNull()?.also { g.shelves += it }
                }
                ITEM_LINE -> {
                    val s = shelf ?: continue
                    runCatching { decodeItem(fields) }.getOrNull()?.let { s.items += it }
                }
            }
        }
        // A shelf with no items is kept: whether an empty day counts is the engine's call.
        return groups.filter { it.shelves.isNotEmpty() }.map { g ->
            KeptShelves(g.cadence, g.period, g.shelves.map { Shelf(it.id, it.kind, it.reason, it.items.toList()) })
        }
    }

    private class GroupBuilder(val cadence: ShelfCadence, val period: Long) {
        val shelves = ArrayList<ShelfBuilder>()
    }

    private class ShelfBuilder(val id: String, val kind: ShelfKind, val reason: Reason) {
        val items = ArrayList<QueueItem>()
    }

    private fun line(key: String, fields: List<String>) = key + '\t' + fields.joinToString(ITEM.toString())

    private fun decodeKept(f: List<String>): GroupBuilder {
        require(f.size == 2)
        return GroupBuilder(ShelfCadence.entries.first { it.name == f[0] }, f[1].toLong())
    }

    private fun decodeShelf(f: List<String>): ShelfBuilder {
        require(f.size == 3)
        return ShelfBuilder(f[0], ShelfKind.entries.first { it.name == f[1] }, decodeReason(f[2]))
    }

    private fun encodeItem(item: QueueItem): List<String>? {
        val t = item.track
        val reason = encodeReason(item.reason) ?: return null
        val text = listOf(t.key.value, t.title) + t.artists + listOfNotNull(t.album, t.youtubeId, t.isrc)
        if (!text.all(::safe)) return null
        return listOf(
            t.key.value, t.title, t.artists.joinToString(LIST.toString()), nullable(t.album),
            nullable(t.durationMs?.toString()), nullable(t.releasedAtMs?.toString()), nullable(t.youtubeId), nullable(t.isrc),
            if (t.explicit) "1" else "0", nullable(t.popularity?.toString()),
            item.source.tag, item.slot.tag, item.score.toString(), reason,
        )
    }

    private fun decodeItem(f: List<String>): QueueItem {
        require(f.size == 14)
        val track = TrackMeta(
            key = TrackKey(f[0]),
            title = f[1],
            artists = list(f[2]),
            album = orNull(f[3]),
            durationMs = orNull(f[4])?.toLong(),
            releasedAtMs = orNull(f[5])?.toLong(),
            youtubeId = orNull(f[6]),
            isrc = orNull(f[7]),
            explicit = when (f[8]) {
                "1" -> true
                "0" -> false
                else -> error("explicit")
            },
            popularity = orNull(f[9])?.toFloat(),
        )
        val source = CandidateSource.fromTag(f[10]) ?: error("source")
        val slot = SlotKind.entries.first { it.tag == f[11] }
        return QueueItem(track, source, decodeReason(f[13]), slot, f[12].toFloat())
    }

    private fun nullable(text: String?) = text ?: NULL_FIELD

    private fun orNull(field: String) = if (field == NULL_FIELD) null else field

    private fun list(field: String) = if (field.isEmpty()) emptyList() else field.split(LIST)

    /** The keys joined by [LIST], or null when one cannot be written. */
    private fun artists(list: List<ArtistKey>) = list.map { it.value }.takeIf { it.all(::safe) }?.joinToString(LIST.toString())

    private fun text(value: String) = value.takeIf(::safe)

    /** Null for reasons that never appear on Home (so are not kept) or whose text cannot be written. */
    private fun encodeReason(reason: Reason): String? {
        val parts: List<String> = when (reason) {
            is Reason.BecauseYouListened -> listOf("listened", text(reason.track.value) ?: return null)
            is Reason.BecauseYouReplayed -> listOf("replayed", text(reason.track.value) ?: return null)
            is Reason.BecauseYouLiked -> listOf("liked", text(reason.track.value) ?: return null)
            is Reason.SimilarTo -> listOf("similar", text(reason.artist.value) ?: return null)
            is Reason.ListenersAlsoPlay -> listOf("cf", text(reason.artist.value) ?: return null)
            is Reason.FromYourTaste -> listOf("cluster", reason.clusterId.toString(), artists(reason.topArtists) ?: return null)
            is Reason.FreshFrom -> listOf("fresh", text(reason.artist.value) ?: return null)
            is Reason.Rediscover -> listOf("rediscover", text(reason.track.value) ?: return null, reason.lastPlayedMs.toString())
            is Reason.UpstreamRadio -> listOf("upstream", text(reason.seed?.value ?: return null) ?: return null)
            is Reason.BecauseYouPicked -> listOf("picked", text(reason.artist.value) ?: return null)
            is Reason.DailyMix -> listOf(
                "mix",
                reason.clusterId.toString(),
                artists(reason.topArtists) ?: return null,
                reason.seedArtist?.let { text(it.value) ?: return null } ?: NULL_FIELD,
            )
            Reason.OnRepeat -> listOf("on_repeat")
            Reason.NewForYou -> listOf("new_for_you")
            Reason.FreshForYou -> listOf("fresh_for_you")
            Reason.RediscoverShelf -> listOf("rediscover_shelf")
            // Never on Home shelves, so never kept.
            is Reason.RadioFrom, Reason.UpNext -> return null
        }
        return parts.joinToString(PAIR.toString())
    }

    private fun decodeReason(text: String): Reason {
        val p = text.split(PAIR)
        fun arity(n: Int) = require(p.size == n)
        return when (p[0]) {
            "listened" -> arity(2).let { Reason.BecauseYouListened(TrackKey(p[1])) }
            "replayed" -> arity(2).let { Reason.BecauseYouReplayed(TrackKey(p[1])) }
            "liked" -> arity(2).let { Reason.BecauseYouLiked(TrackKey(p[1])) }
            "similar" -> arity(2).let { Reason.SimilarTo(ArtistKey(p[1])) }
            "cf" -> arity(2).let { Reason.ListenersAlsoPlay(ArtistKey(p[1])) }
            "cluster" -> arity(3).let { Reason.FromYourTaste(p[1].toInt(), list(p[2]).map(::ArtistKey)) }
            "fresh" -> arity(2).let { Reason.FreshFrom(ArtistKey(p[1])) }
            "rediscover" -> arity(3).let { Reason.Rediscover(TrackKey(p[1]), p[2].toLong()) }
            "upstream" -> arity(2).let { Reason.UpstreamRadio(TrackKey(p[1])) }
            "picked" -> arity(2).let { Reason.BecauseYouPicked(ArtistKey(p[1])) }
            "mix" -> arity(4).let { Reason.DailyMix(p[1].toInt(), list(p[2]).map(::ArtistKey), orNull(p[3])?.let(::ArtistKey)) }
            "on_repeat" -> arity(1).let { Reason.OnRepeat }
            "new_for_you" -> arity(1).let { Reason.NewForYou }
            "fresh_for_you" -> arity(1).let { Reason.FreshForYou }
            "rediscover_shelf" -> arity(1).let { Reason.RediscoverShelf }
            else -> error("unknown reason")
        }
    }
}
