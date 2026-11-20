package app.turtleplayer.search.golden

/** What a golden query should find. */
sealed interface Expect {
    data class Track(val id: String) : Expect

    data class Artist(val name: String) : Expect

    data class Album(val title: String, val artist: String) : Expect

    data object Mix : Expect
}

enum class Category { EXACT, TYPO, PARTIAL, SONG_BY_ARTIST, ACCENTS, SCRIPTS, VERSION, LYRICS, PERSONAL, ALBUM, MOOD, YEAR }

data class GoldenQuery(val query: String, val expect: Expect, val category: Category)

/**
 * The golden query set. Grows toward the plan's 500 real queries: add rows here (and fixture rows
 * to [GoldenCatalog] when needed); thresholds live in the test.
 */
object GoldenQueries {
    private fun t(q: String, id: String, c: Category) = GoldenQuery(q, Expect.Track(id), c)
    private fun a(q: String, name: String, c: Category) = GoldenQuery(q, Expect.Artist(name), c)

    val all: List<GoldenQuery> = listOf(
        // Exact titles and names
        t("yellow", "coldplay-yellow", Category.PERSONAL),
        a("coldplay", "Coldplay", Category.EXACT),
        t("bohemian rhapsody", "queen-bohemian", Category.EXACT),
        t("dont stop me now", "queen-dont-stop", Category.EXACT),
        t("hotel california", "eagles-hotel", Category.EXACT),
        t("the less i know the better", "tame-less", Category.EXACT),
        t("stand by me", "ben-e-stand", Category.EXACT),
        t("1999", "prince-1999", Category.EXACT),
        t("live forever", "oasis-live-forever", Category.EXACT),
        a("acdc", "AC/DC", Category.EXACT),

        // Version filters typed as words
        t("hotel california live", "eagles-hotel-live", Category.VERSION),
        t("yellow live", "coldplay-yellow-live", Category.VERSION),
        t("yellow karaoke", "yellow-karaoke", Category.VERSION),
        t("espresso sped up", "espresso-sped", Category.VERSION),
        t("espresso slowed + reverb", "espresso-slowed", Category.VERSION),
        t("creep acoustic", "creep-acoustic", Category.VERSION),
        t("blinding lights remix", "blinding-remix", Category.VERSION),
        t("smells like teen spirit instrumental", "teen-spirit-instr", Category.VERSION),

        // Typos
        t("bohemain rapsody", "queen-bohemian", Category.TYPO),
        t("wonderwal", "oasis-wonderwall", Category.TYPO),
        a("radiohed", "Radiohead", Category.TYPO),
        a("billie eilsh", "Billie Eilish", Category.TYPO),
        t("smels like teen sprit", "nirvana-teen", Category.TYPO),
        t("stairway to heven", "ledzep-stairway", Category.TYPO),
        a("artic monkeys", "Arctic Monkeys", Category.TYPO),
        a("teh weeknd", "The Weeknd", Category.TYPO),
        t("fleetwod mac dreams", "fleetwood-dreams", Category.TYPO),
        t("levitaitng", "dua-levitating", Category.TYPO),

        // Partial, as you type
        t("bohem", "queen-bohemian", Category.PARTIAL),
        t("blinding li", "weeknd-blinding", Category.PARTIAL),
        t("here comes the", "beatles-here-comes", Category.PARTIAL),
        t("viva la", "coldplay-viva", Category.PARTIAL),
        t("levitat", "dua-levitating", Category.PARTIAL),
        t("smells like te", "nirvana-teen", Category.PARTIAL),

        // Song by artist
        t("yellow by coldplay", "coldplay-yellow", Category.SONG_BY_ARTIST),
        t("creep by radiohead", "radiohead-creep", Category.SONG_BY_ARTIST),
        t("creep tlc", "tlc-creep", Category.SONG_BY_ARTIST),
        t("coldplay - fix you", "coldplay-fix-you", Category.SONG_BY_ARTIST),
        t("fix you - coldplay", "coldplay-fix-you", Category.SONG_BY_ARTIST),
        t("hurt johnny cash", "cash-hurt", Category.SONG_BY_ARTIST),
        t("hurt nine inch nails", "nin-hurt", Category.SONG_BY_ARTIST),
        t("dreams fleetwood mac", "fleetwood-dreams", Category.SONG_BY_ARTIST),
        t("stay justin bieber", "laroi-stay", Category.SONG_BY_ARTIST),
        t("humble kendrick", "kendrick-humble", Category.SONG_BY_ARTIST),
        t("aha take on me", "aha-take", Category.SONG_BY_ARTIST),
        t("guns n roses sweet child", "gnr-sweet", Category.SONG_BY_ARTIST),
        t("pink + white frank ocean", "frank-pink", Category.SONG_BY_ARTIST),

        // Personal ranking among equal titles
        t("dreams", "cranberries-dreams", Category.PERSONAL),
        t("hello", "adele-hello", Category.PERSONAL),
        t("stay", "laroi-stay", Category.PERSONAL),
        t("wonderwall", "oasis-wonderwall", Category.PERSONAL),

        // Accents
        t("beyonce halo", "beyonce-halo", Category.ACCENTS),
        t("sigur ros hoppipolla", "sigur-hoppipolla", Category.ACCENTS),
        a("motorhead", "Motörhead", Category.ACCENTS),
        t("celine dion my heart will go on", "celine-heart", Category.ACCENTS),
        t("rosalia malamente", "rosalia-malamente", Category.ACCENTS),
        t("edith piaf la vie en rose", "piaf-vie", Category.ACCENTS),
        t("maneskin beggin", "maneskin-beggin", Category.ACCENTS),
        t("titi me pregunto", "bad-bunny-titi", Category.ACCENTS),

        // Other scripts and transliteration
        a("кино", "Кино", Category.SCRIPTS),
        t("kino gruppa krovi", "kino-gruppa", Category.SCRIPTS),
        t("группа крови", "kino-gruppa", Category.SCRIPTS),
        a("zemfira", "Земфира", Category.SCRIPTS),
        t("zvezda po imeni solntse", "kino-zvezda", Category.SCRIPTS),
        t("aidoru", "yoasobi-idol", Category.SCRIPTS),
        t("usseewa", "ado-usseewa", Category.SCRIPTS),
        t("夜に駆ける", "yoasobi-yoru", Category.SCRIPTS),
        a("aimyon", "あいみょん", Category.SCRIPTS),
        t("mariigoorudo", "aimyon-marigold", Category.SCRIPTS),

        // Lyrics fragments (fixture lyrics are invented)
        t("salt is in the letters that you folded", "lanterns-harbor", Category.LYRICS),
        t("every gull abvoe the water knows", "lanterns-harbor", Category.LYRICS),
        t("understands neon on the pavement", "owls-static", Category.LYRICS),
        t("grandmothers hymns are rusting", "marrow-copper", Category.LYRICS),
        t("lyrics counting satellites from the roof", "atlas-orbit", Category.LYRICS),
        t("fold the mornig into paper", "quell-boats", Category.LYRICS),
        t("nobody is waiting at the other side", "quell-boats", Category.LYRICS),

        // Albums
        GoldenQuery("thriller album", Expect.Album("Thriller", "Michael Jackson"), Category.ALBUM),
        GoldenQuery("random access memories", Expect.Album("Random Access Memories", "Daft Punk"), Category.ALBUM),
        GoldenQuery("rumours album", Expect.Album("Rumours", "Fleetwood Mac"), Category.ALBUM),

        // Moods and activities
        GoldenQuery("rainy night indie", Expect.Mix, Category.MOOD),
        GoldenQuery("workout", Expect.Mix, Category.MOOD),
        GoldenQuery("chill lofi beats", Expect.Mix, Category.MOOD),
        GoldenQuery("sad songs for driving", Expect.Mix, Category.MOOD),

        // Years
        t("taylor swift 2014", "taylor-shake", Category.YEAR),
        t("billie eilish 2024", "billie-birds", Category.YEAR),
    )
}
