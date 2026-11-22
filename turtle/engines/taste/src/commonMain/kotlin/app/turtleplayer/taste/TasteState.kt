package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey

/**
 * What the engine learned outside the play log and must keep across restarts: the listener's
 * direct controls ("less like this", mutes, dismissed shelves, onboarding), the exploration
 * posterior and the recently recommended tracks. The discovery dial is persisted by the app on its
 * own and is not part of this state.
 *
 * [encode] produces a small line-based text that the app stores as it likes; [decode] never throws
 * and returns null for text it cannot read, so a damaged value costs the state, not the app.
 */
data class TasteState(
    val controls: UserControls = UserControls(),
    val exploration: ExplorationState = ExplorationState(),
    val recent: List<TrackKey> = emptyList(),
) {
    fun encode(): String = buildString {
        append(VERSION)
        line("muted", controls.mutedArtists.map { it.value })
        line("boosted", controls.boostedArtists.map { it.value })
        line("lessTracks", controls.lessLikeThisTracks.map { it.value })
        line("lessArtists", controls.lessLikeThisArtists.filterKeys { PAIR !in it.value }.map { (a, n) -> "${a.value}$PAIR$n" })
        line("suppressed", controls.suppressedReasons.toList())
        line("onboarding", controls.onboardingArtists.map { it.value })
        line("mutedClusters", controls.mutedClusters.map { a -> a.centroid.joinToString(",") })
        line("boostedClusters", controls.boostedClusters.map { a -> a.centroid.joinToString(",") })
        line("exploration", listOf(exploration.alpha.toString(), exploration.beta.toString()))
        line("recent", recent.map { it.value })
    }

    private fun StringBuilder.line(key: String, items: List<String>) {
        append('\n').append(key).append('\t')
        items.filter { it.none { ch -> ch == '\n' || ch == '\t' || ch == ITEM } }.joinTo(this, ITEM.toString())
    }

    companion object {
        private const val VERSION = "turtle-taste-state 1"
        private const val ITEM = '\u001f'
        private const val PAIR = '\u001d'

        fun decode(text: String): TasteState? = runCatching {
            val lines = text.split('\n')
            if (lines.firstOrNull() != VERSION) return null
            val fields = lines.drop(1).associate { l ->
                val tab = l.indexOf('\t')
                if (tab < 0) l to emptyList() else l.substring(0, tab) to l.substring(tab + 1).split(ITEM).filter { it.isNotEmpty() }
            }
            fun items(key: String) = fields[key].orEmpty()
            fun anchors(key: String) = items(key).map { a -> ClusterAnchor(a.split(',').map { it.toFloat() }) }
            val controls = UserControls(
                mutedArtists = items("muted").mapTo(LinkedHashSet(), ::ArtistKey),
                boostedArtists = items("boosted").mapTo(LinkedHashSet(), ::ArtistKey),
                lessLikeThisTracks = items("lessTracks").mapTo(LinkedHashSet(), ::TrackKey),
                lessLikeThisArtists = items("lessArtists").associate { p ->
                    val (a, n) = p.split(PAIR)
                    ArtistKey(a) to n.toInt()
                },
                suppressedReasons = items("suppressed").toSet(),
                onboardingArtists = items("onboarding").map(::ArtistKey),
                mutedClusters = anchors("mutedClusters"),
                boostedClusters = anchors("boostedClusters"),
            )
            val exploration = items("exploration").takeIf { it.size == 2 }
                ?.let { (a, b) -> ExplorationState(a.toDouble(), b.toDouble()) }
                ?: ExplorationState()
            TasteState(controls, exploration, items("recent").map(::TrackKey))
        }.getOrNull()
    }
}
