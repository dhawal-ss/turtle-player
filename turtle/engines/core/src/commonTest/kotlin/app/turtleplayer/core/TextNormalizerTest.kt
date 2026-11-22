package app.turtleplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextNormalizerTest {
    @Test
    fun foldsAccentsPunctuationAndAbbreviations() {
        assertEquals("beyonce feat jay z", TextNormalizer.key("Beyoncé ft. JAY-Z"))
        assertEquals("dont stop me now", TextNormalizer.key("Don't Stop Me Now!"))
        assertEquals("simon and garfunkel", TextNormalizer.key("Simon & Garfunkel"))
        assertEquals("sigur ros", TextNormalizer.key("Sigur Rós"))
        assertEquals("motorhead", TextNormalizer.key("Motörhead"))
    }

    @Test
    fun transliteratesCyrillicAndKana() {
        assertEquals("kino", TextNormalizer.key("Кино"))
        assertEquals("yoasobi", TextNormalizer.key("ヨアソビ"))
        assertEquals("tokyo", TextNormalizer.key("とうきょう").replace("ou", "o"))
        assertEquals("kyou", TextNormalizer.key("きょう"))
        assertEquals("matte", TextNormalizer.key("まって"))
    }

    @Test
    fun katakanaLoanwordDigraphs() {
        assertEquals("fainarufantajii", TextNormalizer.key("ファイナルファンタジー"))
        assertEquals("paatii", TextNormalizer.key("パーティー"))
        assertEquals("disuko", TextNormalizer.key("ディスコ"))
        assertEquals("shefu", TextNormalizer.key("シェフ"))
        assertEquals("wisukii", TextNormalizer.key("ウィスキー"))
        assertEquals("aa", TextNormalizer.key("アァ"), "a vowel and a small vowel stay two vowels")
    }

    @Test
    fun combiningMarksDoNotSplitWords() {
        assertEquals(listOf("दिल"), TextNormalizer.normalize("दिल").tokens, "Devanagari vowel signs are part of the word")
        assertEquals(listOf("तुम", "ही", "हो"), TextNormalizer.normalize("तुम ही हो").tokens)
        // Arabic harakat and Hebrew points are optional: pointed and unpointed spellings match.
        assertEquals(TextNormalizer.key("محمد"), TextNormalizer.key("مُحَمَّد"))
        assertEquals(listOf("محمد"), TextNormalizer.normalize("مُحَمَّد").tokens)
        assertEquals(TextNormalizer.key("שלום"), TextNormalizer.key("שָׁלוֹם"))
    }

    @Test
    fun foldsVietnameseAndFullWidthLatin() {
        assertEquals("son tung m tp", TextNormalizer.key("Sơn Tùng M-TP"))
        assertEquals("lac troi", TextNormalizer.key("Lạc Trôi"))
        assertEquals("hay trao cho anh", TextNormalizer.key("Hãy Trao Cho Anh"))
        assertEquals("nguoi am phu", TextNormalizer.key("Người Âm Phủ"))
        assertEquals("music 1999", TextNormalizer.key("Ｍｕｓｉｃ　１９９９"))
    }

    @Test
    fun letterLikeSymbolsInNamesStayOneWord() {
        assertEquals("pink", TextNormalizer.key("P!nk"))
        assertEquals("kesha", TextNormalizer.key("Ke\$ha"))
        assertEquals("asap rocky", TextNormalizer.key("A\$AP Rocky"))
        assertEquals("panic at the disco", TextNormalizer.key("Panic! at the Disco"))
        assertEquals("help", TextNormalizer.key("Help!"))
    }

    @Test
    fun canonicalTitleStripsUploadNoiseAndKeepsVersions() {
        val official = TitleCanonicalizer.canonicalize("Blinding Lights (Official Video)")
        assertEquals("blinding lights", official.base)
        assertTrue(official.official)
        assertTrue(official.versions.isEmpty())

        val live = TitleCanonicalizer.canonicalize("Yellow - Live at Glastonbury 2016")
        assertEquals("yellow", live.base)
        assertEquals(setOf(VersionTag.LIVE), live.versions)

        // A title that merely starts with a version word is not a version marker.
        val notLive = TitleCanonicalizer.canonicalize("Live Forever - Remastered", artist = "Oasis")
        assertEquals("live forever", notLive.base)
        assertTrue(notLive.versions.isEmpty())

        val prefixed = TitleCanonicalizer.canonicalize("Oasis - Live Forever (Official Video)", artist = "Oasis")
        assertEquals("live forever", prefixed.base)

        val feat = TitleCanonicalizer.canonicalize("Stay (feat. Justin Bieber) [Lyrics]")
        assertEquals("stay", feat.base)
        assertEquals(listOf("Justin Bieber"), feat.featured)

        val remix = TitleCanonicalizer.canonicalize("Levels - Skrillex Remix")
        assertEquals("levels", remix.base)
        assertEquals(setOf(VersionTag.REMIX), remix.versions)
    }

    @Test
    fun noiseWordsInTheTitleItselfAreKept() {
        assertEquals("video games", TitleCanonicalizer.canonicalize("Video Games").base)
        assertEquals("stereo love", TitleCanonicalizer.canonicalize("Stereo Love").base)
        assertEquals("come clean", TitleCanonicalizer.canonicalize("Come Clean").base)
        assertEquals("video killed the radio star", TitleCanonicalizer.canonicalize("Video Killed the Radio Star (Official Video)").base)
        assertEquals("video games", TitleCanonicalizer.canonicalize("Lana Del Rey - Video Games (Official Music Video)", "Lana Del Rey").base)
        assertEquals("yellow", TitleCanonicalizer.canonicalize("Coldplay - Yellow Official Video", "Coldplay").base, "unambiguous trailing marker")
    }

    @Test
    fun withLyricsIsNoiseNotAFeaturedArtist() {
        for (title in listOf("Yellow (With Lyrics)", "Yellow [with lyrics HD]", "Yellow (w/ Lyrics)")) {
            val c = TitleCanonicalizer.canonicalize(title)
            assertEquals("yellow", c.base, title)
            assertTrue(c.featured.isEmpty(), "$title: ${c.featured}")
        }
        assertEquals(listOf("Kygo"), TitleCanonicalizer.canonicalize("Higher Love (with Kygo)").featured)
    }

    @Test
    fun releaseMarkersAreNotPartOfTheTitle() {
        for (title in listOf(
            "Song – 2003 Digital Remaster", "Song - Remastered 2011", "Song (Deluxe Edition)", "Song (Bonus Track)",
            "Song [2009 Remaster]", "Song (25th Anniversary Edition)", "Song - Digitally Remastered",
        )) {
            val c = TitleCanonicalizer.canonicalize(title)
            assertEquals("song", c.base, title)
            assertTrue(c.versions.isEmpty(), title)
        }
    }

    @Test
    fun metaKeysCollapseDifferentUploads() {
        assertEquals(
            TrackKey.fromMeta("The Weeknd", "Blinding Lights"),
            TrackKey.fromMeta("the weeknd", "Blinding Lights [Official Audio]"),
        )
        assertTrue(TrackKey.fromMeta("Coldplay", "Yellow") != TrackKey.fromMeta("Coldplay", "Yellow (Live)"))
        // Titles that contain noise words are different songs, not uploads of one.
        assertTrue(TrackKey.fromMeta("Lana Del Rey", "Video Games") != TrackKey.fromMeta("Lana Del Rey", "Games"))
        assertTrue(TrackKey.fromMeta("Inna", "Stereo Love") != TrackKey.fromMeta("Inna", "Love"))
        assertEquals(TrackKey.fromMeta("Lana Del Rey", "Video Games"), TrackKey.fromMeta("Lana Del Rey", "Video Games - Remastered 2012"))
    }
}

class TrackIdentityTest {
    @Test
    fun videoUploadsMergeWithAudioTracks() {
        val audio = TrackKey.fromMeta("Coldplay", "Yellow")
        assertEquals(audio, TrackKey.fromMeta("Coldplay - Topic", "Yellow"))
        assertEquals(audio, TrackKey.fromMeta("ColdplayVEVO", "Coldplay - Yellow (Official Video)"))
        assertEquals(ArtistKey.of("Coldplay"), ArtistKey.of("Coldplay - Topic"))
    }
}
