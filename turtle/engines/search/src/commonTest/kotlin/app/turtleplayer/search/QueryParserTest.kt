package app.turtleplayer.search

import app.turtleplayer.core.VersionTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryParserTest {
    private val index = LexicalIndex().apply {
        upsert(track("y", "Coldplay", "Yellow", "Parachutes"))
        upsert(track("f", "Coldplay", "Fix You", "X&Y"))
        upsert(track("c", "Radiohead", "Creep", "Pablo Honey"))
        upsert(track("s", "Ben E. King", "Stand by Me"))
        upsert(track("l", "Oasis", "Live Forever", "Definitely Maybe"))
        upsert(track("t", "Michael Jackson", "Billie Jean", "Thriller"))
        upsert(track("w", "The Weeknd", "Blinding Lights", "After Hours"))
    }
    private val parser = QueryParser(index.lexicons())

    @Test
    fun knownArtistIsArtistIntent() {
        val q = parser.parse("coldplay")
        assertEquals(Intent.ARTIST, q.intent)
        assertTrue(q.confidence >= 0.9f)
        assertEquals(Intent.ARTIST, parser.parse("radiohed ").intent, "typo in a known artist")
        assertEquals(Intent.ARTIST, parser.parse("coldp").intent, "artist being typed")
        assertEquals(Intent.ARTIST, parser.parse("teh weeknd").intent, "swapped letters in a short word")
    }

    @Test
    fun songByArtistShapes() {
        val by = parser.parse("yellow by coldplay")
        assertEquals(Intent.SONG_BY_ARTIST, by.intent)
        assertEquals(listOf("yellow"), by.songTokens)
        assertEquals(listOf("coldplay"), by.artistTokens)

        val dashArtistFirst = parser.parse("Coldplay - Fix You")
        assertEquals(Intent.SONG_BY_ARTIST, dashArtistFirst.intent)
        assertEquals(listOf("fix", "you"), dashArtistFirst.songTokens)

        val dashSongFirst = parser.parse("fix you - coldplay")
        assertEquals(listOf("coldplay"), dashSongFirst.artistTokens)

        val bare = parser.parse("creep radiohead")
        assertEquals(Intent.SONG_BY_ARTIST, bare.intent)
        assertEquals(listOf("creep"), bare.songTokens)
        assertEquals(listOf("radiohead"), bare.artistTokens)
    }

    @Test
    fun typoArtistWithUnknownSongWordsIsNotSongByArtist() {
        // A phone library with the artist "SwitchOTR" read "taylor swift" (last word still being typed) as
        // the song "taylor" by "SwitchOTR", which hid the Taylor Swift artist result.
        val library = LexicalIndex().apply {
            upsert(track("so", "SwitchOTR", "Hunnid"))
            upsert(track("ty", "Tyler, The Creator", "Earfquake"))
        }
        val parser = QueryParser(library.lexicons())
        for (query in listOf("taylor swift", "taylor swift ")) {
            val q = parser.parse(query)
            assertTrue(q.intent != Intent.SONG_BY_ARTIST, "'$query' was ${q.intent} at ${q.confidence}: ${q.songTokens} by ${q.artistTokens}")
        }
        // An exact artist still splits off song words the library does not know.
        assertEquals(Intent.SONG_BY_ARTIST, parser.parse("hunnid switchotr").intent)
        assertEquals(Intent.SONG_BY_ARTIST, parser.parse("switchotr new tune").intent)
        // A partly typed artist still splits off a known title.
        assertEquals(Intent.SONG_BY_ARTIST, parser.parse("hunnid switc").intent)
    }

    @Test
    fun songByArtistDoesNotSplitKnownTitlesContainingBy() {
        val q = parser.parse("stand by me")
        assertEquals(Intent.SONG, q.intent)
        assertEquals(listOf("stand", "by", "me"), q.tokens)
    }

    @Test
    fun versionWordsAndYearsBecomeFilters() {
        val live = parser.parse("hotel california live")
        assertEquals(setOf(VersionTag.LIVE), live.filters.versions)
        assertEquals(listOf("hotel", "california"), live.tokens)
        assertEquals(listOf("live"), live.filterTokens)

        assertEquals(setOf(VersionTag.SPED_UP), parser.parse("espresso sped up").filters.versions)
        val slowed = parser.parse("espresso slowed + reverb")
        assertEquals(setOf(VersionTag.SLOWED), slowed.filters.versions)
        assertEquals(listOf("espresso"), slowed.tokens)
        assertEquals(setOf(VersionTag.KARAOKE, VersionTag.ACOUSTIC), parser.parse("yellow karaoke acoustic").filters.versions)

        assertEquals(2024..2024, parser.parse("pop 2024").filters.years)
        assertEquals(1990..1999, parser.parse("90s rock").filters.years)
        assertEquals(2010..2019, parser.parse("2010s").filters.years)
        assertEquals(1980..1999, parser.parse("80s 90s").filters.years)
    }

    @Test
    fun knownTitlesKeepTheirVersionWords() {
        val q = parser.parse("live forever")
        assertTrue(q.filters.isEmpty)
        assertEquals(listOf("live", "forever"), q.tokens)
    }

    @Test
    fun moodAndActivityQueries() {
        val rainy = parser.parse("rainy night indie")
        assertEquals(Intent.MOOD, rainy.intent)
        assertEquals(listOf("rainy", "night"), rainy.mood!!.moods)
        assertEquals(listOf("indie"), rainy.mood.genres)
        assertEquals(Intent.MOOD, parser.parse("workout").intent)
        assertEquals(listOf("workout"), parser.parse("music for workout").mood!!.activities)
        // A title that merely contains a mood word is not a mood query.
        assertEquals(Intent.SONG, parser.parse("chill bill").intent)
    }

    @Test
    fun longNaturalLanguageFragmentsAreLyrics() {
        assertEquals(Intent.LYRICS, parser.parse("salt is in the letters that you folded").intent)
        assertEquals(Intent.LYRICS, parser.parse("lyrics counting satellites").intent, "cue word")
        assertEquals(listOf("counting", "satellites"), parser.parse("lyrics counting satellites").tokens)
    }

    @Test
    fun albumCueWord() {
        val q = parser.parse("thriller album")
        assertEquals(Intent.ALBUM, q.intent)
        assertEquals(listOf("thriller"), q.tokens)
    }

    @Test
    fun asYouTypeMarksTheLastTokenAsPrefix() {
        val typing = parser.parse("fix yo").toLexicalQuery().tokens
        assertEquals(listOf(false, true), typing.map { it.prefix })
        val done = parser.parse("fix you ").toLexicalQuery().tokens
        assertEquals(listOf(false, false), done.map { it.prefix })
        val filtered = parser.parse("yellow live").toLexicalQuery().tokens
        assertEquals(listOf(QueryToken("yellow"), QueryToken("live", optional = true)), filtered, "a filter is never the typed prefix")
    }

    @Test
    fun songByArtistQueryPrefersFields() {
        val tokens = parser.parse("yellow by coldplay").toLexicalQuery().tokens
        assertEquals(listOf(Field.TITLE, Field.ARTIST), tokens.map { it.preferredField })
    }

    @Test
    fun blankInputIsUnknown() {
        val q = parser.parse("  !! ")
        assertTrue(q.isBlank)
        assertEquals(Intent.UNKNOWN, q.intent)
        assertNull(q.mood)
    }

    @Test
    fun classifierIsReplaceable() {
        val custom = QueryParser(classifier = { input -> IntentPrediction(Intent.ALBUM, 0.99f, mapOf(Intent.ALBUM to 0.99f), input.tokens) })
        assertEquals(Intent.ALBUM, custom.parse("anything").intent)
    }
}
