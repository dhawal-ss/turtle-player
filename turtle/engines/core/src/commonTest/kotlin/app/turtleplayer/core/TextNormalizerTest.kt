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
    fun metaKeysCollapseDifferentUploads() {
        assertEquals(
            TrackKey.fromMeta("The Weeknd", "Blinding Lights"),
            TrackKey.fromMeta("the weeknd", "Blinding Lights [Official Audio]"),
        )
        assertTrue(TrackKey.fromMeta("Coldplay", "Yellow") != TrackKey.fromMeta("Coldplay", "Yellow (Live)"))
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
