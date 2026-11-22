package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class LexicalIndexTest {
    private fun LexicalIndex.trackIds(query: String, asYouType: Boolean = false) =
        search(LexicalQuery.of(query, asYouType), types = setOf(DocType.TRACK)).map { (it.item as LocalItem.Track).track.meta.youtubeId }

    private fun index(vararg tracks: app.turtleplayer.core.TrackMeta) = LexicalIndex().apply { tracks.forEach { upsert(it) } }

    @Test
    fun exactBeatsPrefixBeatsFuzzy() {
        val idx = index(
            track("fuzzy", "A", "Lightz"),
            track("prefix", "B", "Lightsaber"),
            track("exact", "C", "Lights"),
        )
        assertEquals(listOf("exact", "prefix", "fuzzy"), idx.trackIds("lights", asYouType = true))
    }

    @Test
    fun titleBeatsArtistBeatsAlbum() {
        val idx = index(
            track("album", "Someone", "Other Song", album = "Paradise"),
            track("artist", "Paradise", "Unrelated"),
            track("title", "Nobody", "Paradise"),
        )
        assertEquals(listOf("title", "artist", "album"), idx.trackIds("paradise"))
    }

    @Test
    fun wholeTitleMatchBeatsLongerTitle() {
        val idx = index(track("sub", "The Beatles", "Yellow Submarine"), track("yellow", "Coldplay", "Yellow"))
        assertEquals("yellow", idx.trackIds("yellow").first())
    }

    @Test
    fun allTokensMatchedAndOrderAreRewarded() {
        val idx = index(
            track("partial", "X", "Love Story"),
            track("reversed", "Y", "Me Love Song"),
            track("ordered", "Z", "Song Love Me"),
        )
        // Both full matches beat the partial one; the title with the query's word order wins.
        assertEquals(listOf("ordered", "reversed", "partial"), idx.trackIds("love me"))
    }

    @Test
    fun typosWithinToleranceOnly() {
        val idx = index(track("w", "Oasis", "Wonderwall"), track("c", "Radiohead", "Creep"))
        assertEquals(listOf("w"), idx.trackIds("wondrewall"), "transposition")
        assertEquals(listOf("w"), idx.trackIds("wonderwal"), "deletion")
        assertEquals(listOf("w"), idx.trackIds("wonnderwalll"), "two edits allowed for long tokens")
        assertEquals(emptyList(), idx.trackIds("wonnderwallll"), "three edits are too many")
        assertEquals(listOf("c"), idx.trackIds("crep"), "one edit for 4-7 characters")
        assertEquals(emptyList(), idx.trackIds("crip"))
    }

    @Test
    fun shortTokensMustMatchExactly() {
        val idx = index(track("a", "X", "Cat"), track("b", "Y", "Car"))
        assertEquals(listOf("a"), idx.trackIds("cat"))
        assertEquals(emptyList(), idx.trackIds("cot"))
    }

    @Test
    fun shortTokensAllowOneAdjacentSwap() {
        val idx = index(track("w", "The Weeknd", "Blinding Lights"), track("c", "X", "Cat"))
        assertEquals(listOf("w"), idx.trackIds("teh weeknd"))
        assertEquals(listOf("c"), idx.trackIds("cta"))
        assertEquals(listOf("c"), idx.trackIds("cta", asYouType = true))
        assertEquals(emptyList(), idx.trackIds("tca"), "only adjacent characters swap")
        assertNotNull(idx.artistLexicon.lookup(listOf("teh", "weeknd"), false))
    }

    @Test
    fun initialismsMatchWithoutDots() {
        val idx = index(track("m", "M.I.A.", "Paper Planes"), track("r", "R.E.M.", "Losing My Religion"), track("t", "t.A.T.u.", "All the Things She Said"))
        fun artist(q: String) = (idx.search(LexicalQuery.of(q, asYouType = false), types = setOf(DocType.ARTIST)).firstOrNull()?.item as? LocalItem.Artist)?.artist?.name
        assertEquals("M.I.A.", artist("mia"))
        assertEquals("R.E.M.", artist("rem"))
        assertEquals("t.A.T.u.", artist("tatu"))
        assertEquals(listOf("m"), idx.trackIds("mia paper planes"))
    }

    @Test
    fun joinedTokensFindCompactSpellings() {
        val idx = index(track("acdc", "AC/DC", "Back in Black"))
        val artist = idx.search(LexicalQuery.of("acdc", asYouType = false), types = setOf(DocType.ARTIST)).single()
        assertEquals(ArtistKey.of("AC/DC"), (artist.item as LocalItem.Artist).artist.key)
    }

    @Test
    fun artistsAndAlbumsAreReferenceCountedDocuments() {
        val idx = LexicalIndex()
        idx.upsert(track("1", "Coldplay", "Yellow", "Parachutes"))
        idx.upsert(track("2", "Coldplay", "Shiver", "Parachutes"))
        assertEquals(1, idx.artistCount)
        assertEquals(1, idx.albumCount)
        val artist = idx.search(LexicalQuery.of("coldplay"), types = setOf(DocType.ARTIST)).single().item as LocalItem.Artist
        assertEquals(2, artist.trackCount)

        idx.remove(TrackKey.youtube("1"))
        assertEquals(1, idx.artistCount)
        idx.remove(TrackKey.youtube("2"))
        assertEquals(0, idx.artistCount)
        assertEquals(0, idx.albumCount)
        assertTrue(idx.search(LexicalQuery.of("coldplay")).isEmpty())
    }

    @Test
    fun upsertReplacesMetadataAndSources() {
        val idx = LexicalIndex()
        idx.upsert(track("1", "Coldplay", "Yelow"), setOf(LocalSource.HISTORY))
        idx.upsert(track("1", "Coldplay", "Yellow"), setOf(LocalSource.LIBRARY))
        assertEquals(1, idx.trackCount)
        assertEquals(setOf(LocalSource.LIBRARY), idx.track(TrackKey.youtube("1"))!!.sources)
        val hit = idx.search(LexicalQuery.of("yellow", asYouType = false)).first { it.item is LocalItem.Track }
        assertEquals(MatchKind.EXACT, hit.matches.single { it.field == Field.TITLE }.kind)

        // Unsaved from the library but played before: no longer a library track.
        idx.upsert(track("1", "Coldplay", "Yellow"), setOf(LocalSource.HISTORY))
        assertEquals(setOf(LocalSource.HISTORY), idx.track(TrackKey.youtube("1"))!!.sources)

        idx.upsert(track("1", "Coldplay", "Yellow"), setOf(LocalSource.LIBRARY, LocalSource.HISTORY))
        idx.removeSource(TrackKey.youtube("1"), LocalSource.LIBRARY)
        assertNotNull(idx.track(TrackKey.youtube("1")))
        idx.removeSource(TrackKey.youtube("1"), LocalSource.HISTORY)
        assertNull(idx.track(TrackKey.youtube("1")))
    }

    @Test
    fun matchesExplainWhyADocumentMatched() {
        val idx = index(track("y", "Coldplay", "Yellow (Live at Glastonbury)", "Parachutes"))
        val hit = idx.search(LexicalQuery(listOf(QueryToken("yelow"), QueryToken("coldplay"), QueryToken("live", optional = true))))
            .first { it.item is LocalItem.Track }
        val byToken = hit.matches.associateBy { it.queryToken }
        assertEquals(Field.TITLE, byToken.getValue("yelow").field)
        assertEquals(MatchKind.FUZZY, byToken.getValue("yelow").kind)
        assertEquals(Field.ARTIST, byToken.getValue("coldplay").field)
        assertEquals(Field.VERSION, byToken.getValue("live").field)
        assertTrue(byToken.getValue("live").optional)
    }

    @Test
    fun lexiconFindsArtistsByTypoAndPrefix() {
        val idx = index(track("1", "Arctic Monkeys", "505"))
        assertEquals(true, idx.artistLexicon.lookup(listOf("arctic", "monkeys"), false)?.exact)
        assertNotNull(idx.artistLexicon.lookup(listOf("artic", "monkeys"), false))
        assertNotNull(idx.artistLexicon.lookup(listOf("arctic", "mon"), true))
        assertNull(idx.artistLexicon.lookup(listOf("arctic", "mon"), false))
        assertNull(idx.artistLexicon.lookup(listOf("monkeys"), false), "names match from the first token")
    }

    @Test
    fun fiftyThousandTracksSearchWithinBudget() {
        val random = Random(2026)
        val syllables = listOf(
            "ka", "lo", "mi", "ra", "ne", "so", "ta", "vi", "ze", "du", "fo", "gi", "ha", "ju", "ke", "li", "mo", "nu",
            "pa", "qui", "ro", "sa", "tu", "ve", "wo", "xa", "yo", "zu", "bel", "cor", "dan", "el", "fin", "gor", "lin",
        )
        fun word() = buildString { repeat(2 + random.nextInt(3)) { append(syllables[random.nextInt(syllables.size)]) } }
        val words = List(12_000) { word() }
        fun phrase(n: Int) = List(n) { words[random.nextInt(words.size)] }.joinToString(" ")
        val artists = List(4_000) { phrase(1 + random.nextInt(2)) }
        val albums = List(8_000) { phrase(1 + random.nextInt(3)) }
        val idx = LexicalIndex()
        val buildStart = TimeSource.Monotonic.markNow()
        repeat(50_000) { i ->
            idx.upsert(track("t$i", artists[random.nextInt(artists.size)], phrase(1 + random.nextInt(4)), albums[random.nextInt(albums.size)]))
        }
        val buildTime = buildStart.elapsedNow()

        val queries = List(48) { i ->
            val w = words[random.nextInt(words.size)]
            when (i % 6) {
                0 -> w // exact
                1 -> w.dropLast(1) // as-you-type prefix
                2 -> w.substring(0, w.length / 2) + "x" + w.substring(w.length / 2) // typo
                3 -> "$w ${words[random.nextInt(words.size)].take(3)}" // two tokens, second being typed
                4 -> phrase(4) // long query: many lexicon span lookups during parsing
                else -> w.take(1) // first keystroke
            }
        }
        // Parsing consults the same index (artist/title/album lexicons), so time both together.
        val parser = QueryParser(idx.lexicons())
        fun run(q: String) = idx.search(parser.parse(q))
        repeat(3) { queries.forEach { run(it) } } // warm up the JIT
        var worst = kotlin.time.Duration.ZERO
        var total = kotlin.time.Duration.ZERO
        for (q in queries) {
            val mark = TimeSource.Monotonic.markNow()
            run(q)
            val t = mark.elapsedNow()
            total += t
            if (t > worst) worst = t
        }
        println("50k tracks: build $buildTime, ${idx.artistCount} artists / ${idx.albumCount} albums, parse+search mean ${total / queries.size}, worst $worst")
        assertTrue(worst.inWholeMilliseconds < 200, "worst query took $worst")
    }
}
