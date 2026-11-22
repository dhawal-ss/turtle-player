package app.turtleplayer.core

/** A recording variant that a listener may or may not want ("live", "acoustic", ...). */
enum class VersionTag(vararg val words: String) {
    LIVE("live"),
    ACOUSTIC("acoustic", "unplugged"),
    REMIX("remix", "rmx", "bootleg"),
    COVER("cover"),
    INSTRUMENTAL("instrumental"),
    KARAOKE("karaoke"),
    SLOWED("slowed", "reverb"),
    SPED_UP("sped", "nightcore"),
    DEMO("demo"),
    ORCHESTRAL("orchestral", "symphonic"),
}

data class CanonicalTitle(
    /** Normalized title with upload noise, featured artists and version markers removed. */
    val base: String,
    val versions: Set<VersionTag>,
    val featured: List<String>,
    /** True when the upload says it is the official audio or video. */
    val official: Boolean,
)

/**
 * Turns upload titles like "Artist - Song (feat. X) [Official Video] - Live at Wembley" into a
 * stable base title plus version tags, so different uploads of one recording deduplicate and so
 * "live" or "remix" can be preferred or penalized deliberately.
 */
object TitleCanonicalizer {
    /** Upload and release noise. Only removed from bracketed and dash-separated segments, never from the title itself. */
    private val noisePhrases = listOf(
        "official music video", "official video", "official audio", "official lyric video",
        "official visualizer", "official visualiser", "official mv", "official", "music video",
        "lyric video", "lyrics video", "with lyrics", "lyrics", "lyric", "audio", "video", "visualizer",
        "visualiser", "hd", "hq", "4k", "mv", "explicit", "clean", "color coded", "eng sub", "romanized",
        "digitally remastered", "digital remaster", "remastered", "remaster", "digital", "mono", "stereo",
        "radio edit", "album version", "single version", "original mix", "extended mix", "extended",
        "full version", "version", "deluxe", "expanded", "special edition", "anniversary", "edition",
        "bonus track", "bonus",
    )

    /**
     * Upload markers that are unambiguous even outside brackets ("Yellow Official Video"), so they
     * are removed from the end of the title. Single words such as "clean" or "video" are not: they
     * end real titles ("Come Clean").
     */
    private val trailingMarkers = listOf(
        "official music video", "official video", "official audio", "official lyric video", "official visualizer",
        "official visualiser", "official mv", "music video", "lyric video", "lyrics video", "with lyrics",
        "color coded", "eng sub",
    )

    /** Words that may follow a leading version word in a dash segment: "Live at X", "Acoustic Session". */
    private val versionContinuations = setOf("at", "from", "in", "on", "session", "sessions", "version", "edit", "mix")

    private val bracketed = Regex("""[(\[{【「]([^)\]}】」]*)[)\]}】」]""")
    private val featPattern = Regex("""(?i)\s(?:feat\.?|ft\.?|featuring)\s+([^()\[\]|]+?)(?=\s+-\s|$)""")
    private val yearPattern = Regex("""^(?:19|20)\d\d$""")
    private val ordinalPattern = Regex("""^\d+(?:st|nd|rd|th)$""")
    private val dashSplit = Regex("""\s+[-–—|]\s+""")

    /**
     * @param artist when given, a leading "Artist - " prefix (common in video uploads) is removed.
     */
    fun canonicalize(title: String, artist: String? = null): CanonicalTitle {
        val versions = mutableSetOf<VersionTag>()
        val featured = mutableListOf<String>()
        var official = false

        fun noteFeatured(segmentAfterKeyword: String) {
            featured += splitArtists(segmentAfterKeyword)
        }

        // Bracketed segments: "(Live)", "[Official Video]", "(feat. X)" are markers, not title words.
        var working = bracketed.replace(title) { m ->
            val inner = m.groupValues[1]
            val norm = TextNormalizer.normalize(inner)
            when {
                norm.tokens.isEmpty() -> " "
                // "(With Lyrics)" is upload noise, "(with Kygo)" a featured artist.
                norm.tokens.first() == "feat" ||
                    (norm.tokens.first() == "with" && !isMarkerRest(removeNoise(norm.tokens.drop(1).joinToString(" ")))) -> {
                    noteFeatured(inner.trim().substringAfter(' ', ""))
                    " "
                }
                else -> {
                    if ("official" in norm.tokens) official = true
                    val tags = tagsIn(norm.tokens)
                    versions += tags
                    val rest = removeNoise(norm.folded)
                    if (tags.isNotEmpty() || isMarkerRest(rest)) " " else m.value
                }
            }
        }

        // Dash segments: "Artist - Song - Live at X". Only drop a segment that is clearly a marker.
        val parts = working.split(dashSplit).toMutableList()
        if (parts.size > 1 && artist != null && TextNormalizer.key(parts.first()) == TextNormalizer.key(artist)) {
            parts.removeAt(0)
            working = parts.joinToString(" - ")
        }
        if (parts.size > 1) {
            val kept = mutableListOf(parts.first())
            for (part in parts.drop(1)) {
                val norm = TextNormalizer.normalize(part)
                if ("official" in norm.tokens) official = true
                val rest = removeNoise(norm.folded)
                val tags = tagsIn(norm.tokens)
                val markerShaped = norm.tokens.isNotEmpty() && (
                    isMarkerRest(rest) ||
                        (tags.isNotEmpty() && (tagsIn(listOf(norm.tokens.last())).isNotEmpty() ||
                            (tagsIn(listOf(norm.tokens.first())).isNotEmpty() &&
                                (norm.tokens.size == 1 || norm.tokens[1] in versionContinuations || yearPattern.matches(norm.tokens[1])))))
                    )
                if (markerShaped) versions += tags else kept += part
            }
            working = kept.joinToString(" - ")
        }

        featPattern.find(" $working")?.let { m ->
            noteFeatured(m.groupValues[1])
            working = (" $working").removeRange(m.range)
        }

        val base = removeTrailingMarkers(TextNormalizer.normalize(working).folded)
        return CanonicalTitle(
            base = base.ifBlank { TextNormalizer.key(title) },
            versions = versions,
            featured = featured.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            official = official,
        )
    }

    /** Splits "A, B & C" / "A x B" / "A feat. B" into names. */
    fun splitArtists(text: String): List<String> =
        text.split(Regex("""\s*(?:,|&|\s+x\s+|\s+and\s+|\s+feat\.?\s+|\s+ft\.?\s+|/|;)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun tagsIn(tokens: List<String>): Set<VersionTag> {
        val set = tokens.toSet()
        return VersionTag.entries.filterTo(mutableSetOf()) { tag -> tag.words.any { it in set } }
    }

    /** What is left of a segment after noise removal is empty or only years and ordinals ("2003", "25th"). */
    private fun isMarkerRest(rest: String): Boolean =
        rest.isBlank() || rest.split(' ').all { yearPattern.matches(it) || ordinalPattern.matches(it) }

    private fun removeTrailingMarkers(folded: String): String {
        var s = folded
        while (true) {
            val marker = trailingMarkers.firstOrNull { s.endsWith(" $it") } ?: return s
            s = s.dropLast(marker.length + 1)
        }
    }

    private fun removeNoise(folded: String): String {
        var s = " $folded "
        for (phrase in noisePhrases) s = s.replace(" $phrase ", " ")
        return s.trim().replace(Regex(" +"), " ")
    }
}
