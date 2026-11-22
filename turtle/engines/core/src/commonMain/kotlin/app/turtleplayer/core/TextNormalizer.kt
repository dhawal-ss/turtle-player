package app.turtleplayer.core

/**
 * Text normalized for matching: lowercase, accents folded, Cyrillic and kana transliterated to
 * Latin, punctuation removed, and common music abbreviations expanded to one canonical token.
 *
 * Pure Kotlin tables (no java.text.Normalizer) so Android, desktop and iOS produce identical output,
 * which keeps search indexes and track keys portable between devices.
 */
data class NormalizedText(
    /** Tokens joined by single spaces. */
    val folded: String,
    val tokens: List<String>,
)

object TextNormalizer {
    private val abbreviations: Map<String, String> = mapOf(
        "ft" to "feat",
        "featuring" to "feat",
        "feat" to "feat",
        "pt" to "part",
        "vol" to "volume",
        "w" to "with",
    )

    fun normalize(input: String): NormalizedText {
        val sb = StringBuilder(input.length + 8)
        val lower = input.lowercase()
        var i = 0
        while (i < lower.length) {
            val c = widen(lower[i])
            when {
                c == '&' || c == '+' -> sb.append(" and ")
                // Apostrophes join words: "don't" -> "dont".
                c == '\'' || c == '’' || c == '`' || c == '´' -> Unit
                // Letter-like symbols inside a name: "P!nk" -> "pink", "Ke$ha" -> "kesha", "$uicideboy$".
                c == '!' && isLetterAt(lower, i - 1) && isLetterAt(lower, i + 1) -> sb.append('i')
                c == '$' && (isLetterAt(lower, i - 1) || isLetterAt(lower, i + 1)) -> sb.append('s')
                isMark(c) -> {
                    // Vowel signs and viramas are part of Indic words ("दिल"); other marks (Latin accents left
                    // over from lowercase(), Arabic harakat, Hebrew points) are optional and dropped.
                    if (isEssentialMark(c) && sb.isNotEmpty() && sb.last() != ' ') sb.append(c)
                }
                c in '぀'..'ヿ' -> {
                    i = Kana.romanize(lower, i, sb)
                    continue
                }
                else -> {
                    val folded = fold(c)
                    if (folded != null) {
                        sb.append(folded)
                    } else if (c.isLetterOrDigit()) {
                        // Scripts without spaces (CJK ideographs, Thai, ...) index per character.
                        if (isUnspacedScript(c)) sb.append(' ').append(c).append(' ') else sb.append(c)
                    } else {
                        sb.append(' ')
                    }
                }
            }
            i++
        }
        val tokens = sb.split(' ').filter { it.isNotEmpty() }.map { abbreviations[it] ?: it }
        return NormalizedText(tokens.joinToString(" "), tokens)
    }

    /** Full-width ASCII ("Ｍｕｓｉｃ１９９９") to plain ASCII. */
    private fun widen(c: Char): Char = if (c in '\uFF01'..'\uFF5E') (c.code - 0xFEE0).toChar().lowercaseChar() else c

    private fun isLetterAt(s: String, i: Int): Boolean = i in s.indices && widen(s[i]).isLetter()

    private fun isMark(c: Char): Boolean = when (c.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }

    /** Marks that change the letter in abugidas written with spaces (Devanagari to Sinhala, Tibetan, Myanmar). */
    private fun isEssentialMark(c: Char): Boolean = c in '\u0900'..'\u0DFF' || c in '\u0F00'..'\u0FFF' || c in '\u1000'..'\u109F'

    /** Convenience for keys and comparisons. */
    fun key(input: String): String = normalize(input).folded

    private fun isUnspacedScript(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '฀'..'๿' || c in '豈'..'﫿'

    private fun fold(c: Char): String? {
        if (c < 'À') return null
        latin[c]?.let { return it }
        if (c in '\u1EA0'..'\u1EF9') return vietnamese(c)
        cyrillic[c]?.let { return it }
        greek[c]?.let { return it }
        return null
    }

    /** Latin Extended Additional block of Vietnamese letters with stacked diacritics ("ậ", "ữ"). */
    private fun vietnamese(c: Char): String = when (c) {
        in '\u1EA0'..'\u1EB7' -> "a"
        in '\u1EB8'..'\u1EC7' -> "e"
        in '\u1EC8'..'\u1ECB' -> "i"
        in '\u1ECC'..'\u1EE3' -> "o"
        in '\u1EE4'..'\u1EF1' -> "u"
        else -> "y"
    }

    private fun table(vararg groups: Pair<String, String>): Map<Char, String> {
        val map = HashMap<Char, String>()
        for ((chars, value) in groups) for (ch in chars) map[ch] = value
        return map
    }

    // Lowercase only: input is lowercased before folding.
    private val latin = table(
        "àáâãäåāăąǎ" to "a", "çćĉċč" to "c", "ďđ" to "d", "èéêëēĕėęěẽ" to "e",
        "ĝğġģ" to "g", "ĥħ" to "h", "ìíîïĩīĭįıǐ" to "i", "ĵ" to "j", "ķ" to "k",
        "ĺļľŀł" to "l", "ñńņňŉ" to "n", "òóôõöøōŏőǒơ" to "o", "ŕŗř" to "r",
        "śŝşšș" to "s", "ţťŧț" to "t", "ùúûüũūŭůűųǔưǖǘǚǜ" to "u", "ŵẁẃẅ" to "w",
        "ýÿŷỳẏ" to "y", "źżžẓ" to "z", "ḍ" to "d", "ḥ" to "h", "ḷ" to "l", "ṁṃ" to "m", "ṅṇ" to "n",
        "ṛṝ" to "r", "ṡṣ" to "s", "ṭ" to "t", "ß" to "ss", "æ" to "ae", "œ" to "oe",
        "þ" to "th", "ð" to "d",
    )

    private val cyrillic = table(
        "а" to "a", "б" to "b", "в" to "v", "г" to "g", "ґ" to "g", "д" to "d", "е" to "e",
        "ё" to "e", "є" to "ye", "ж" to "zh", "з" to "z", "и" to "i", "і" to "i", "ї" to "yi",
        "й" to "y", "к" to "k", "л" to "l", "м" to "m", "н" to "n", "о" to "o", "п" to "p",
        "р" to "r", "с" to "s", "т" to "t", "у" to "u", "ф" to "f", "х" to "kh", "ц" to "ts",
        "ч" to "ch", "ш" to "sh", "щ" to "shch", "ъ" to "", "ы" to "y", "ь" to "", "э" to "e",
        "ю" to "yu", "я" to "ya",
    )

    private val greek = table(
        "αά" to "a", "β" to "v", "γ" to "g", "δ" to "d", "εέ" to "e", "ζ" to "z", "ηή" to "i",
        "θ" to "th", "ιίϊΐ" to "i", "κ" to "k", "λ" to "l", "μ" to "m", "ν" to "n", "ξ" to "x",
        "οό" to "o", "π" to "p", "ρ" to "r", "σς" to "s", "τ" to "t", "υύϋΰ" to "y", "φ" to "f",
        "χ" to "ch", "ψ" to "ps", "ωώ" to "o",
    )
}

/** Hepburn romanization for hiragana and katakana. Kanji pass through unchanged. */
internal object Kana {
    private val base: Map<Char, String> = buildMap {
        val rows = listOf(
            "あいうえお" to listOf("a", "i", "u", "e", "o"),
            "かきくけこ" to listOf("ka", "ki", "ku", "ke", "ko"),
            "がぎぐげご" to listOf("ga", "gi", "gu", "ge", "go"),
            "さしすせそ" to listOf("sa", "shi", "su", "se", "so"),
            "ざじずぜぞ" to listOf("za", "ji", "zu", "ze", "zo"),
            "たちつてと" to listOf("ta", "chi", "tsu", "te", "to"),
            "だぢづでど" to listOf("da", "ji", "zu", "de", "do"),
            "なにぬねの" to listOf("na", "ni", "nu", "ne", "no"),
            "はひふへほ" to listOf("ha", "hi", "fu", "he", "ho"),
            "ばびぶべぼ" to listOf("ba", "bi", "bu", "be", "bo"),
            "ぱぴぷぺぽ" to listOf("pa", "pi", "pu", "pe", "po"),
            "まみむめも" to listOf("ma", "mi", "mu", "me", "mo"),
            "やゆよ" to listOf("ya", "yu", "yo"),
            "らりるれろ" to listOf("ra", "ri", "ru", "re", "ro"),
            "わをん" to listOf("wa", "o", "n"),
            "ぁぃぅぇぉ" to listOf("a", "i", "u", "e", "o"),
            "ゔ" to listOf("vu"),
        )
        for ((chars, romaji) in rows) chars.forEachIndexed { idx, ch -> put(ch, romaji[idx]) }
    }

    private val smallY = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")

    private val smallVowel = mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o")

    /**
     * Loanword digraphs written with a small vowel: ファ fa, ティ ti, ドゥ du, シェ she, ウィ wi, イェ ye.
     * Null when the pair is not a digraph (a vowel followed by a small vowel is just two vowels).
     */
    private fun digraph(syllable: String, vowel: String): String? = when {
        syllable.length >= 2 -> syllable.dropLast(1) + vowel
        syllable == "u" && vowel != "u" -> "w$vowel"
        syllable == "i" && vowel == "e" -> "ye"
        else -> null
    }

    private fun toHiragana(c: Char): Char = if (c in 'ァ'..'ヶ') c - 0x60 else c

    /** Appends romaji for the kana run starting at [start]; returns the index after it. */
    fun romanize(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        var geminate = false
        while (i < text.length) {
            val raw = text[i]
            if (raw !in '぀'..'ヿ') break
            if (raw == 'ー') { // long vowel mark: repeat the previous vowel
                out.lastOrNull()?.takeIf { it in "aeiou" }?.let { out.append(it) }
                i++
                continue
            }
            val c = toHiragana(raw)
            if (c == 'っ') {
                geminate = true
                i++
                continue
            }
            var syllable = base[c]
            if (syllable == null) {
                i++
                continue
            }
            val next = text.getOrNull(i + 1)?.let(::toHiragana)
            val yVowel = next?.let { smallY[it] }
            val small = next?.let { smallVowel[it] }
            val combined = if (small != null) digraph(syllable, small) else null
            if (yVowel != null && syllable.endsWith("i") && syllable.length >= 2) {
                val stem = syllable.dropLast(1)
                syllable = if (stem.endsWith("sh") || stem.endsWith("ch") || stem == "j") stem + yVowel else stem + "y" + yVowel
                i++
            } else if (combined != null) {
                syllable = combined
                i++
            }
            if (geminate) {
                out.append(if (syllable.startsWith("ch")) 't' else syllable[0])
                geminate = false
            }
            out.append(syllable)
            i++
        }
        return i
    }
}
