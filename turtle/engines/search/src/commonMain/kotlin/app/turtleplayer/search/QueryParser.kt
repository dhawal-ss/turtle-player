package app.turtleplayer.search

import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.VersionTag

/** What the user is most likely looking for. */
enum class Intent {
    ARTIST,
    SONG,
    ALBUM,

    /** "x by y" or "y - x". */
    SONG_BY_ARTIST,

    /** A fragment of lyrics. */
    LYRICS,

    /** A mood or activity ("rainy night indie", "workout"): answered with a generated mix. */
    MOOD,
    UNKNOWN,
}

/** Plain-word filters typed into the query ("live", "acoustic", "2024", "90s"). */
data class QueryFilters(
    val versions: Set<VersionTag> = emptySet(),
    /** Inclusive release-year range. */
    val years: IntRange? = null,
) {
    val isEmpty: Boolean get() = versions.isEmpty() && years == null
}

/** Mood, activity and genre words of a MOOD query; seeds the generated mix. */
data class MoodTerms(val moods: List<String>, val activities: List<String>, val genres: List<String>)

/** A known name matched by a [NameLexicon]. */
data class LexiconMatch(
    /** Stable key: ArtistKey value, album key or TrackKey value. */
    val key: String,
    val name: String,
    val exact: Boolean,
    /** In (0, 1]; 1 for exact matches, lower for typos, prefixes and partial names. */
    val quality: Float,
)

/** Known names of one kind (artists, albums or titles) from the library and history. */
fun interface NameLexicon {
    /**
     * Best known name matching [tokens] as a whole, token by token within typo tolerance; the last
     * token as a prefix when [lastIsPrefix]. Null when nothing matches.
     */
    fun lookup(tokens: List<String>, lastIsPrefix: Boolean): LexiconMatch?

    companion object {
        val Empty = NameLexicon { _, _ -> null }
    }
}

/** Known artist names: the main signal for telling artist queries apart from song queries. */
fun interface ArtistLexicon : NameLexicon

/** Lexicons available to query understanding. */
class QueryLexicons(
    val artists: NameLexicon = NameLexicon.Empty,
    val albums: NameLexicon = NameLexicon.Empty,
    val titles: NameLexicon = NameLexicon.Empty,
) {
    companion object {
        val Empty = QueryLexicons()
    }
}

/** Fixed list of names, e.g. artists from imported history that are not in the index. */
class StaticArtistLexicon(names: Collection<String>) : ArtistLexicon {
    private val index = LexicalIndex().apply {
        names.forEachIndexed { i, name ->
            upsert(TrackMeta(TrackKey("lexicon:$i"), title = name, artists = listOf(name)))
        }
    }

    override fun lookup(tokens: List<String>, lastIsPrefix: Boolean): LexiconMatch? = index.artistLexicon.lookup(tokens, lastIsPrefix)
}

/** Everything query understanding knows about the input. */
data class ParsedQuery(
    val raw: String,
    /** All normalized tokens of the input. */
    val normalizedTokens: List<String>,
    /** Tokens to match: filters and cue words ("album", "lyrics") removed. */
    val tokens: List<String>,
    /** Words that were read as filters; still used as optional match tokens. */
    val filterTokens: List<String>,
    val filters: QueryFilters,
    val intent: Intent,
    val confidence: Float,
    val intentScores: Map<Intent, Float>,
    /** For SONG_BY_ARTIST: the song and artist parts. Empty otherwise. */
    val songTokens: List<String> = emptyList(),
    val artistTokens: List<String> = emptyList(),
    val mood: MoodTerms? = null,
    /** True while the last token is still being typed (no trailing space). */
    val asYouType: Boolean = true,
) {
    val isBlank: Boolean get() = tokens.isEmpty() && filterTokens.isEmpty()
    val text: String get() = tokens.joinToString(" ")

    /** The token being typed, if any. */
    val typedToken: String? get() = if (asYouType) normalizedTokens.lastOrNull()?.takeIf { it !in filterTokens } else null

    fun toLexicalQuery(): LexicalQuery {
        val main = if (intent == Intent.SONG_BY_ARTIST && songTokens.isNotEmpty() && artistTokens.isNotEmpty()) {
            songTokens.map { QueryToken(it, preferredField = Field.TITLE) } + artistTokens.map { QueryToken(it, preferredField = Field.ARTIST) }
        } else {
            tokens.map { QueryToken(it) }
        }
        val typed = typedToken
        val lastTypedIndex = if (typed == null) -1 else main.indexOfLast { it.text == typed }
        val required = main.mapIndexed { i, t -> if (i == lastTypedIndex) t.copy(prefix = true) else t }
        return LexicalQuery(required + filterTokens.map { QueryToken(it, optional = true) })
    }

    companion object {
        fun empty(raw: String = "") = ParsedQuery(raw, emptyList(), emptyList(), emptyList(), QueryFilters(), Intent.UNKNOWN, 0f, emptyMap())
    }
}

/** Input to an [IntentClassifier]. */
data class IntentInput(
    val raw: String,
    /** Normalized tokens with filters removed. */
    val tokens: List<String>,
    val filters: QueryFilters,
    /** Normalized sides of "a - b" in the raw input (filters removed), when present. */
    val dashSides: Pair<List<String>, List<String>>?,
    val lastIsPrefix: Boolean,
    val lexicons: QueryLexicons,
)

data class IntentPrediction(
    val intent: Intent,
    val confidence: Float,
    val scores: Map<Intent, Float>,
    /** Tokens to match, with cue words such as "album" or "lyrics" removed. */
    val matchTokens: List<String>,
    val songTokens: List<String> = emptyList(),
    val artistTokens: List<String> = emptyList(),
    val mood: MoodTerms? = null,
)

/** Labels query intent. v1 is [RuleBasedIntentClassifier]; a learned on-device model can replace it. */
fun interface IntentClassifier {
    fun classify(input: IntentInput): IntentPrediction
}

/**
 * Normalizes raw input, pulls out plain-word filters, and asks the [IntentClassifier] what the
 * user is looking for.
 */
class QueryParser(
    private val lexicons: QueryLexicons = QueryLexicons.Empty,
    private val classifier: IntentClassifier = RuleBasedIntentClassifier(),
) {
    fun parse(raw: String): ParsedQuery {
        val all = TextNormalizer.normalize(raw).tokens
        if (all.isEmpty()) return ParsedQuery.empty(raw)
        val asYouType = raw.isNotEmpty() && !raw.last().isWhitespace()

        // A query that is exactly a known title or artist ("Live Forever", the band "Live") has no filters.
        val isKnownName = lexicons.titles.lookup(all, false)?.exact == true || lexicons.artists.lookup(all, false)?.exact == true
        val extracted = if (isKnownName) FilterExtractor.Result(all, emptyList(), QueryFilters()) else FilterExtractor.extract(all)

        val dash = dashSplit.split(raw, limit = 2).takeIf { it.size == 2 }?.let { parts ->
            val removed = extracted.removed.toSet()
            val left = TextNormalizer.normalize(parts[0]).tokens.filter { it !in removed }
            val right = TextNormalizer.normalize(parts[1]).tokens.filter { it !in removed }
            if (left.isNotEmpty() && right.isNotEmpty()) left to right else null
        }

        val prediction = classifier.classify(IntentInput(raw, extracted.kept, extracted.filters, dash, asYouType, lexicons))
        return ParsedQuery(
            raw = raw,
            normalizedTokens = all,
            tokens = prediction.matchTokens,
            filterTokens = extracted.removed,
            filters = extracted.filters,
            intent = prediction.intent,
            confidence = prediction.confidence,
            intentScores = prediction.scores,
            songTokens = prediction.songTokens,
            artistTokens = prediction.artistTokens,
            mood = prediction.mood,
            asYouType = asYouType,
        )
    }

    private companion object {
        val dashSplit = Regex("""\s+[-–—]\s+""")
    }
}

/** Finds version words and years typed as plain words. */
internal object FilterExtractor {
    data class Result(val kept: List<String>, val removed: List<String>, val filters: QueryFilters)

    private val versionWords: Map<String, VersionTag> = buildMap {
        for (tag in VersionTag.entries) for (w in tag.words) put(w, tag)
        put("covers", VersionTag.COVER)
        put("remixed", VersionTag.REMIX)
        put("remixes", VersionTag.REMIX)
        put("instrumentals", VersionTag.INSTRUMENTAL)
        remove("sped") // only "sped up" is a filter; handled below
    }

    private val fullYear = Regex("""^(19[5-9]\d|20[0-3]\d)$""")
    private val shortDecade = Regex("""^(\d)0s$""")
    private val fullDecade = Regex("""^(19|20)(\d)0s$""")

    fun yearRange(token: String): IntRange? {
        fullYear.matchEntire(token)?.let { val y = token.toInt(); return y..y }
        fullDecade.matchEntire(token)?.let { val start = token.take(4).toInt(); return start..start + 9 }
        shortDecade.matchEntire(token)?.let { m ->
            val d = m.groupValues[1].toInt() * 10
            val start = if (d >= 30) 1900 + d else 2000 + d
            return start..start + 9
        }
        return null
    }

    fun extract(tokens: List<String>): Result {
        val kept = ArrayList<String>()
        val removed = ArrayList<String>()
        val versions = LinkedHashSet<VersionTag>()
        var years: IntRange? = null
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                (t == "sped" || t == "speed") && next == "up" -> {
                    versions += VersionTag.SPED_UP
                    removed += listOf(t, "up")
                    i += 2
                    continue
                }
                t == "slowed" && next == "and" && tokens.getOrNull(i + 2) == "reverb" -> {
                    versions += VersionTag.SLOWED
                    removed += listOf("slowed", "and", "reverb")
                    i += 3
                    continue
                }
                versionWords[t] != null -> {
                    versions += versionWords.getValue(t)
                    removed += t
                }
                yearRange(t) != null -> {
                    val r = yearRange(t)!!
                    years = years?.let { minOf(it.first, r.first)..maxOf(it.last, r.last) } ?: r
                    removed += t
                }
                else -> kept += t
            }
            i++
        }
        return Result(kept, removed, QueryFilters(versions, years))
    }
}

/** Small mood/activity/genre vocabulary for v1 intent rules. */
object MoodLexicon {
    val moods: Set<String> = setOf(
        "chill", "sad", "happy", "rainy", "rain", "night", "late", "morning", "summer", "winter", "autumn",
        "mellow", "dreamy", "melancholy", "melancholic", "angry", "hype", "cozy", "calm", "relaxing", "relax",
        "upbeat", "energetic", "romantic", "moody", "dark", "nostalgic", "peaceful", "sunny", "uplifting",
        "emotional", "heartbreak", "feelgood", "lazy", "sunday", "rainy", "stormy", "spring", "evening",
    )
    val activities: Set<String> = setOf(
        "workout", "gym", "running", "run", "study", "studying", "focus", "sleep", "sleeping", "party",
        "driving", "drive", "roadtrip", "cooking", "dinner", "reading", "meditation", "yoga", "commute",
        "cleaning", "coding", "work", "working", "gaming", "dancing", "training", "cardio", "lifting",
    )
    val genres: Set<String> = setOf(
        "indie", "rock", "pop", "jazz", "lofi", "lo", "fi", "hip", "hop", "hiphop", "rap", "edm", "house",
        "techno", "classical", "piano", "ambient", "metal", "punk", "folk", "country", "rnb", "soul", "blues",
        "kpop", "jpop", "synthwave", "electronic", "reggae", "latin", "disco", "funk", "trap", "drill",
        "grunge", "emo", "shoegaze", "alternative", "alt", "chillhop", "bossa", "nova", "acoustic", "instrumental",
    )
    val filler: Set<String> = setOf(
        "music", "songs", "playlist", "mix", "vibes", "vibe", "for", "to", "a", "the", "of", "beats", "tracks",
        "and", "with", "in", "at", "on", "some", "me", "my", "while", "when", "list", "hits", "best", "radio",
    )

    /** Mood terms when every token is mood, activity, genre or filler and at least one is not filler. */
    fun analyze(tokens: List<String>): MoodTerms? {
        if (tokens.isEmpty()) return null
        if (tokens.any { it !in moods && it !in activities && it !in genres && it !in filler }) return null
        val terms = MoodTerms(tokens.filter { it in moods }, tokens.filter { it in activities }, tokens.filter { it in genres })
        return terms.takeIf { it.moods.isNotEmpty() || it.activities.isNotEmpty() || it.genres.isNotEmpty() }
    }
}

/**
 * v1 intent rules. Signals, strongest first: cue words ("album", "lyrics"), an explicit
 * "x by y" / "y - x" shape, known names from the lexicons (whole query or a leading/trailing
 * span), the mood vocabulary, and query length/function-word ratio for lyrics.
 */
class RuleBasedIntentClassifier : IntentClassifier {
    override fun classify(input: IntentInput): IntentPrediction {
        var tokens = input.tokens
        if (tokens.isEmpty()) {
            val c = if (input.filters.isEmpty) 0f else 0.3f
            return IntentPrediction(Intent.UNKNOWN, c, mapOf(Intent.UNKNOWN to c), emptyList())
        }
        val lex = input.lexicons
        val prefix = input.lastIsPrefix

        // Cue words at either end: "thriller album", "lyrics counting satellites".
        var cue: Intent? = null
        if (tokens.size > 1) {
            for ((words, intent) in cueWords) {
                if (tokens.first() in words) {
                    cue = intent; tokens = tokens.drop(1); break
                }
                if (tokens.last() in words) {
                    cue = intent; tokens = tokens.dropLast(1); break
                }
            }
        }
        if (cue != null) return IntentPrediction(cue, 0.8f, mapOf(cue to 0.8f), tokens)

        val scores = HashMap<Intent, Float>()
        fun bump(intent: Intent, s: Float) {
            if (s > (scores[intent] ?: 0f)) scores[intent] = s
        }
        var song = emptyList<String>()
        var artist = emptyList<String>()

        // "Artist - Song" or "Song - Artist".
        input.dashSides?.let { (left, right) ->
            val l = lex.artists.lookup(left, false)
            val r = lex.artists.lookup(right, prefix)
            val artistOnRight = r != null && (l == null || r.quality > l.quality)
            if (artistOnRight) {
                song = left; artist = right
            } else {
                song = right; artist = left
            }
            val known = if (artistOnRight) r else l
            bump(Intent.SONG_BY_ARTIST, if (known != null) 0.75f + 0.15f * known.quality else 0.6f)
        }

        // "Song by Artist", unless the whole thing is a known title ("Stand by Me").
        val byIndex = tokens.indexOf("by")
        val wholeTitle = lex.titles.lookup(tokens, prefix)
        if (song.isEmpty() && byIndex in 1 until tokens.lastIndex && wholeTitle?.exact != true) {
            val a = tokens.subList(byIndex + 1, tokens.size)
            lex.artists.lookup(a, prefix)?.let { m ->
                song = tokens.subList(0, byIndex)
                artist = a
                bump(Intent.SONG_BY_ARTIST, 0.6f + 0.3f * m.quality)
            }
        }

        val wholeArtist = lex.artists.lookup(tokens, prefix)
        wholeArtist?.let { bump(Intent.ARTIST, if (it.exact) 0.92f else 0.5f + 0.3f * it.quality) }
        wholeTitle?.let { bump(Intent.SONG, if (it.exact) 0.8f else 0.45f + 0.3f * it.quality) }
        lex.albums.lookup(tokens, prefix)?.let { bump(Intent.ALBUM, if (it.exact) 0.6f else 0.3f + 0.25f * it.quality) }

        // Artist name plus song words in either order: "coldplay yellow", "yellow coldplay".
        if (song.isEmpty() && tokens.size >= 2 && wholeArtist?.exact != true) {
            var bestSplit: Triple<List<String>, List<String>, Float>? = null
            for (i in 1 until tokens.size) {
                val lead = tokens.subList(0, i)
                val trail = tokens.subList(i, tokens.size)
                lex.artists.lookup(lead, false)?.let { m ->
                    val s = m.quality * lead.size / tokens.size.toFloat() + if (lex.titles.lookup(trail, prefix) != null) 0.3f else 0f
                    if (bestSplit == null || s > bestSplit.third) bestSplit = Triple(trail, lead, s)
                }
                lex.artists.lookup(trail, prefix)?.let { m ->
                    val s = m.quality * trail.size / tokens.size.toFloat() + if (lex.titles.lookup(lead, false) != null) 0.3f else 0f
                    if (bestSplit == null || s > bestSplit.third) bestSplit = Triple(lead, trail, s)
                }
            }
            bestSplit?.let { (s, a, strength) ->
                song = s
                artist = a
                bump(Intent.SONG_BY_ARTIST, (0.55f + 0.3f * strength).coerceAtMost(0.9f))
            }
        }

        val mood = MoodLexicon.analyze(tokens)
        if (mood != null) {
            bump(Intent.MOOD, if (mood.moods.isNotEmpty() || mood.activities.isNotEmpty()) 0.8f else 0.55f)
        }

        val functionShare = tokens.count { it in functionWords }.toFloat() / tokens.size
        when {
            tokens.size >= 6 -> bump(Intent.LYRICS, if (functionShare >= 0.3f) 0.75f else 0.6f)
            tokens.size >= 4 && functionShare >= 0.4f -> bump(Intent.LYRICS, 0.5f)
        }
        bump(Intent.SONG, if (tokens.size == 1 && tokens[0].length <= 2) 0.3f else 0.45f)
        bump(Intent.UNKNOWN, 0.35f)

        val winner = scores.entries.maxWith(compareBy<Map.Entry<Intent, Float>> { it.value }.thenBy { -priority.indexOf(it.key) })
        return IntentPrediction(
            intent = winner.key,
            confidence = winner.value,
            scores = scores,
            matchTokens = tokens,
            songTokens = if (winner.key == Intent.SONG_BY_ARTIST) song else emptyList(),
            artistTokens = if (winner.key == Intent.SONG_BY_ARTIST) artist else emptyList(),
            mood = if (winner.key == Intent.MOOD) mood else null,
        )
    }

    private companion object {
        val cueWords = listOf(
            setOf("album", "ep", "lp") to Intent.ALBUM,
            setOf("lyrics", "lyric") to Intent.LYRICS,
            setOf("song", "track") to Intent.SONG,
        )

        /** Tie-break order when two intents score the same. */
        val priority = listOf(Intent.ARTIST, Intent.SONG_BY_ARTIST, Intent.SONG, Intent.ALBUM, Intent.MOOD, Intent.LYRICS, Intent.UNKNOWN)

        val functionWords = setOf(
            "i", "you", "me", "my", "your", "we", "us", "our", "he", "she", "they", "the", "a", "an", "and", "to", "in",
            "of", "it", "is", "on", "that", "all", "im", "dont", "cant", "be", "so", "oh", "just", "like", "when",
            "what", "never", "know", "cause", "with", "for", "this", "at", "are", "was", "were", "from", "up",
            "down", "into", "out", "but", "if", "no", "not", "every", "where",
        )
    }
}
