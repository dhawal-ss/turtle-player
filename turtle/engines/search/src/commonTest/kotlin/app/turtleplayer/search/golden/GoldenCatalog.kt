package app.turtleplayer.search.golden

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.search.OnlineTrack
import app.turtleplayer.search.yearMs

/**
 * Fixture world for the golden query set: an online catalog (stand-in for YouTube Music) with
 * duplicate uploads, covers, karaoke and live versions, and a user library/history that is a subset
 * of it. Real artist and title names are facts; every lyric line here is invented for the fixture.
 *
 * Row format: `id | artists (;-separated) | title | album | year | flags | popularity`
 * Flags: L = in library, H<n> = played n times, OFF = official upload, CAT = online only.
 */
object GoldenCatalog {
    private val rows = """
        coldplay-yellow|Coldplay|Yellow|Parachutes|2000|L H6|0.9
        coldplay-yellow-ov|Coldplay|Coldplay - Yellow (Official Video)||2000|CAT OFF|0.95
        coldplay-yellow-lyrics|Coldplay|Yellow (Lyrics)||2000|CAT|0.5
        coldplay-yellow-topic|Coldplay - Topic|Yellow|Parachutes|2000|CAT OFF|0.8
        coldplay-yellow-live|Coldplay|Yellow (Live at Glastonbury 2016)|Live Sessions|2016|CAT|0.4
        yellow-karaoke|Sing King|Yellow (Karaoke Version)|Karaoke Hits|2016|CAT|0.3
        yellow-cover|Boyce Avenue|Yellow (Coldplay Cover)|Acoustic Sessions|2012|CAT|0.35
        glass-orchard-yellow|Glass Orchard|Yellow|Paper Moons|2019|CAT|0.1
        coldplay-fix-you|Coldplay|Fix You|X&Y|2005|L|0.85
        coldplay-viva|Coldplay|Viva la Vida|Viva la Vida or Death and All His Friends|2008|L|0.85
        coldplay-clocks|Coldplay|Clocks|A Rush of Blood to the Head|2002|L|0.8
        coldplay-sky|Coldplay|A Sky Full of Stars|Ghost Stories|2014|CAT|0.8
        queen-bohemian|Queen|Bohemian Rhapsody|A Night at the Opera|1975|L H3|0.95
        queen-bohemian-ov|Queen Official|Queen – Bohemian Rhapsody (Official Video Remastered)||1975|CAT OFF|0.97
        bohemian-karaoke|Sing King|Bohemian Rhapsody (Karaoke Version)|Karaoke Hits|2015|CAT|0.3
        queen-dont-stop|Queen|Don't Stop Me Now|Jazz|1978|L|0.85
        queen-under-pressure|Queen;David Bowie|Under Pressure|Hot Space|1981|L|0.8
        bowie-heroes|David Bowie|"Heroes"|"Heroes"|1977|L|0.75
        bowie-heroes-live|David Bowie|Heroes - Live|Stage|1978|CAT|0.3
        bowie-starman|David Bowie|Starman|The Rise and Fall of Ziggy Stardust|1972|CAT|0.7
        beatles-yesterday|The Beatles|Yesterday|Help!|1965|L|0.85
        beatles-let-it-be|The Beatles|Let It Be|Let It Be|1970|L|0.85
        beatles-here-comes|The Beatles|Here Comes the Sun|Abbey Road|1969|L H4|0.9
        beatles-yellow-sub|The Beatles|Yellow Submarine|Revolver|1966|CAT|0.7
        oasis-wonderwall|Oasis|Wonderwall|(What's the Story) Morning Glory?|1995|L|0.9
        wonderwall-cover|Ryan Adams|Wonderwall|Love Is Hell|2004|CAT|0.4
        oasis-live-forever|Oasis|Live Forever|Definitely Maybe|1994|L|0.7
        oasis-dont-look-back|Oasis|Don't Look Back in Anger|(What's the Story) Morning Glory?|1995|CAT|0.8
        radiohead-creep|Radiohead|Creep|Pablo Honey|1993|L|0.9
        creep-acoustic|Radiohead|Creep (Acoustic)|My Iron Lung|1994|CAT|0.4
        tlc-creep|TLC|Creep|CrazySexyCool|1994|CAT|0.6
        radiohead-karma|Radiohead|Karma Police|OK Computer|1997|L H5|0.85
        radiohead-nude|Radiohead|Nude|In Rainbows|2007|L|0.6
        nirvana-teen|Nirvana|Smells Like Teen Spirit|Nevermind|1991|L|0.95
        teen-spirit-instr|Nirvana|Smells Like Teen Spirit (Instrumental)|Karaoke Hits|2010|CAT|0.2
        nirvana-come|Nirvana|Come as You Are|Nevermind|1991|CAT|0.85
        weeknd-blinding|The Weeknd|Blinding Lights|After Hours|2019|L H8|0.97
        blinding-remix|The Weeknd|Blinding Lights (Chromatics Remix)|After Hours (Remixes)|2020|CAT|0.4
        weeknd-save|The Weeknd|Save Your Tears|After Hours|2020|L|0.9
        weeknd-starboy|The Weeknd;Daft Punk|Starboy|Starboy|2016|CAT|0.9
        daftpunk-get-lucky|Daft Punk;Pharrell Williams|Get Lucky|Random Access Memories|2013|L|0.9
        daftpunk-around|Daft Punk|Around the World|Homework|1997|CAT|0.7
        adele-hello|Adele|Hello|25|2015|L|0.9
        lionel-hello|Lionel Richie|Hello|Can't Slow Down|1984|CAT|0.75
        adele-someone|Adele|Someone Like You|21|2011|L|0.9
        adele-rolling|Adele|Rolling in the Deep|21|2010|CAT|0.9
        taylor-shake|Taylor Swift|Shake It Off|1989|2014|L|0.9
        taylor-antihero|Taylor Swift|Anti-Hero|Midnights|2022|L|0.9
        taylor-22|Taylor Swift|22|Red|2012|CAT|0.7
        billie-bad-guy|Billie Eilish|bad guy|When We All Fall Asleep, Where Do We Go?|2019|L|0.95
        billie-ocean|Billie Eilish|ocean eyes|Don't Smile at Me|2016|L|0.8
        billie-birds|Billie Eilish|BIRDS OF A FEATHER|Hit Me Hard and Soft|2024|L|0.95
        sabrina-espresso|Sabrina Carpenter|Espresso|Short n' Sweet|2024|L H7|0.95
        espresso-ov|Sabrina Carpenter|Sabrina Carpenter - Espresso (Official Video)||2024|CAT OFF|0.97
        espresso-slowed|Sabrina Carpenter|Espresso (Slowed + Reverb)||2024|CAT|0.4
        espresso-sped|Sabrina Carpenter|Espresso (Sped Up)||2024|CAT|0.45
        chappell-pink-pony|Chappell Roan|Pink Pony Club|The Rise and Fall of a Midwest Princess|2020|CAT|0.85
        dua-levitating|Dua Lipa|Levitating|Future Nostalgia|2020|L|0.9
        dua-dont-start|Dua Lipa|Don't Start Now|Future Nostalgia|2019|CAT|0.9
        ed-shape|Ed Sheeran|Shape of You|÷|2017|L|0.95
        ed-perfect|Ed Sheeran|Perfect|÷|2017|CAT|0.9
        laroi-stay|The Kid LAROI;Justin Bieber|Stay|F*ck Love 3|2021|L H2|0.9
        rihanna-stay|Rihanna;Mikky Ekko|Stay|Unapologetic|2012|CAT|0.8
        bieber-peaches|Justin Bieber|Peaches|Justice|2021|CAT|0.85
        harry-as-it-was|Harry Styles|As It Was|Harry's House|2022|L|0.95
        arctic-505|Arctic Monkeys|505|Favourite Worst Nightmare|2007|L H5|0.9
        arctic-r-u-mine|Arctic Monkeys|R U Mine?|AM|2012|L|0.8
        arctic-do-i|Arctic Monkeys|Do I Wanna Know?|AM|2013|L|0.9
        tame-less|Tame Impala|The Less I Know the Better|Currents|2015|L|0.9
        tame-borderline|Tame Impala|Borderline|The Slow Rush|2019|CAT|0.7
        fleetwood-dreams|Fleetwood Mac|Dreams|Rumours|1977|L|0.9
        cranberries-dreams|The Cranberries|Dreams|Everybody Else Is Doing It, So Why Can't We?|1993|L H9|0.75
        fleetwood-chain|Fleetwood Mac|The Chain|Rumours|1977|CAT|0.8
        eagles-hotel|Eagles|Hotel California|Hotel California|1976|L|0.9
        eagles-hotel-live|Eagles|Hotel California (Live on MTV, 1994)|Hell Freezes Over|1994|CAT|0.6
        acdc-back|AC/DC|Back in Black|Back in Black|1980|L|0.9
        acdc-highway|AC/DC|Highway to Hell|Highway to Hell|1979|CAT|0.85
        ledzep-stairway|Led Zeppelin|Stairway to Heaven|Led Zeppelin IV|1971|L|0.9
        pinkfloyd-wish|Pink Floyd|Wish You Were Here|Wish You Were Here|1975|L|0.85
        pinkfloyd-time|Pink Floyd|Time|The Dark Side of the Moon|1973|CAT|0.75
        mj-billie-jean|Michael Jackson|Billie Jean|Thriller|1982|L|0.95
        mj-thriller|Michael Jackson|Thriller|Thriller|1982|L|0.9
        mj-beat-it|Michael Jackson|Beat It|Thriller|1982|CAT|0.9
        whitney-dance|Whitney Houston|I Wanna Dance with Somebody (Who Loves Me)|Whitney|1987|L|0.85
        prince-1999|Prince|1999|1999|1982|L|0.7
        prince-purple|Prince|Purple Rain|Purple Rain|1984|CAT|0.8
        simon-sound|Simon & Garfunkel|The Sound of Silence|Sounds of Silence|1965|L|0.85
        ben-e-stand|Ben E. King|Stand by Me|Don't Play That Song!|1961|L|0.85
        outkast-hey-ya|OutKast|Hey Ya!|Speakerboxxx/The Love Below|2003|L|0.85
        kendrick-humble|Kendrick Lamar|HUMBLE.|DAMN.|2017|L|0.9
        kendrick-not-like|Kendrick Lamar|Not Like Us|Not Like Us|2024|CAT|0.95
        drake-gods|Drake|God's Plan|Scorpion|2018|CAT|0.9
        eminem-lose|Eminem|Lose Yourself|8 Mile|2002|L|0.9
        frank-pink|Frank Ocean|Pink + White|Blonde|2016|L|0.8
        frank-nights|Frank Ocean|Nights|Blonde|2016|L|0.8
        sza-kill|SZA|Kill Bill|SOS|2022|L|0.9
        lorde-royals|Lorde|Royals|Pure Heroine|2013|CAT|0.85
        lorde-green|Lorde|Green Light|Melodrama|2017|CAT|0.8
        chappell-hot-to-go|Chappell Roan|HOT TO GO!|The Rise and Fall of a Midwest Princess|2023|CAT|0.85
        mia-paper|M.I.A.|Paper Planes|Kala|2007|L|0.85
        rem-religion|R.E.M.|Losing My Religion|Out of Time|1991|CAT|0.9
        pink-so-what|P!nk|So What|Funhouse|2008|L|0.85
        kesha-tiktok|Ke${"$"}ha|TiK ToK|Animal|2009|CAT|0.9
        maya-stereo|Edward Maya;Vika Jigulina|Stereo Love|Stereo Love|2009|L|0.8
        son-tung-lac-troi|Sơn Tùng M-TP|Lạc Trôi|Lạc Trôi|2017|CAT|0.7
        moritaka-ame|森高千里|雨|古今東西|1990|CAT|0.6
        lana-video|Lana Del Rey|Video Games|Born to Die|2011|L|0.8
        lana-summertime|Lana Del Rey|Summertime Sadness|Born to Die|2012|CAT|0.85
        phoebe-motion|Phoebe Bridgers|Motion Sickness|Stranger in the Alps|2017|L|0.6
        mitski-nobody|Mitski|Nobody|Be the Cowboy|2018|L|0.75
        bon-iver-skinny|Bon Iver|Skinny Love|For Emma, Forever Ago|2008|L|0.8
        cash-hurt|Johnny Cash|Hurt|American IV: The Man Comes Around|2002|L|0.85
        nin-hurt|Nine Inch Nails|Hurt|The Downward Spiral|1994|CAT|0.7
        beyonce-halo|Beyoncé|Halo|I Am... Sasha Fierce|2008|L|0.9
        beyonce-crazy|Beyoncé;JAY-Z|Crazy in Love|Dangerously in Love|2003|L|0.9
        sigur-hoppipolla|Sigur Rós|Hoppípolla|Takk...|2005|L|0.6
        motorhead-ace|Motörhead|Ace of Spades|Ace of Spades|1980|L|0.8
        celine-heart|Céline Dion|My Heart Will Go On|Let's Talk About Love|1997|L|0.9
        rosalia-malamente|Rosalía|Malamente|El Mal Querer|2018|L|0.75
        rosalia-despecha|Rosalía|Despechá|Motomami +|2022|CAT|0.8
        stromae-alors|Stromae|Alors on danse|Cheese|2010|L|0.8
        stromae-papaoutai|Stromae|Papaoutai|Racine carrée|2013|CAT|0.85
        piaf-vie|Édith Piaf|La Vie en rose|La Vie en rose|1947|L|0.8
        bjork-army|Björk|Army of Me|Post|1995|L|0.6
        mylene-desenchantee|Mylène Farmer|Désenchantée|L'Autre...|1991|CAT|0.6
        bad-bunny-titi|Bad Bunny|Tití Me Preguntó|Un Verano Sin Ti|2022|L|0.9
        bad-bunny-dakiti|Bad Bunny;Jhay Cortez|Dákiti|El Último Tour del Mundo|2020|CAT|0.85
        shakira-hips|Shakira|Hips Don't Lie|Oral Fixation, Vol. 2|2006|L|0.85
        fonsi-despacito|Luis Fonsi;Daddy Yankee|Despacito|Vida|2017|CAT|0.95
        maneskin-beggin|Måneskin|Beggin'|Chosen|2017|L|0.85
        aurora-runaway|AURORA|Runaway|All My Demons Greeting Me as a Friend|2015|L|0.8
        kino-gruppa|Кино|Группа крови|Группа крови|1988|L H4|0.7
        kino-zvezda|Кино|Звезда по имени Солнце|Звезда по имени Солнце|1989|L|0.65
        kino-peremen|Кино|Хочу перемен|Последний герой|1989|CAT|0.6
        zemfira-iskala|Земфира|Искала|Прости меня моя любовь|2000|L|0.55
        monetochka-raz|Монеточка|Каждый раз|Раскраски для взрослых|2018|L|0.5
        tatu-all|t.A.T.u.|All the Things She Said|200 km/h in the Wrong Lane|2002|L|0.8
        yoasobi-idol|YOASOBI|アイドル|アイドル|2023|L H3|0.9
        yoasobi-yoru|YOASOBI|夜に駆ける|THE BOOK|2019|L|0.9
        aimyon-marigold|あいみょん|マリーゴールド|瞬間的シックスセンス|2018|L|0.75
        kenshi-lemon|米津玄師|Lemon|Lemon|2018|L|0.85
        ado-usseewa|Ado|うっせぇわ|狂言|2020|L|0.8
        radwimps-zenzenzense|RADWIMPS|前前前世|君の名は。|2016|L|0.8
        bts-dynamite|BTS|Dynamite|BE|2020|L|0.95
        newjeans-hype|NewJeans|Hype Boy|New Jeans|2022|L|0.85
        blackpink-venom|BLACKPINK|Pink Venom|Born Pink|2022|CAT|0.9
        katebush-running|Kate Bush|Running Up That Hill (A Deal with God)|Hounds of Love|1985|L|0.85
        toto-africa|Toto|Africa|Toto IV|1982|L|0.9
        aha-take|a-ha|Take On Me|Hunting High and Low|1985|L|0.9
        journey-dont-stop|Journey|Don't Stop Believin'|Escape|1981|CAT|0.9
        gnr-sweet|Guns N' Roses|Sweet Child o' Mine|Appetite for Destruction|1987|CAT|0.9
        gorillaz-feel|Gorillaz|Feel Good Inc.|Demon Days|2005|L|0.85
        mgmt-kids|MGMT|Kids|Oracular Spectacular|2007|L|0.8
        strokes-last|The Strokes|Last Nite|Is This It|2001|L|0.75
        killers-brightside|The Killers|Mr. Brightside|Hot Fuss|2004|L H3|0.9
        hozier-church|Hozier|Take Me to Church|Hozier|2013|CAT|0.9
        glass-animals-heat|Glass Animals|Heat Waves|Dreamland|2020|L|0.9
        cas-apocalypse|Cigarettes After Sex|Apocalypse|Cigarettes After Sex|2017|L|0.8
        beach-house-space|Beach House|Space Song|Depression Cherry|2015|L|0.8
        lanterns-harbor|Paper Lanterns|Harbor Lights|Low Tide|2021|L H6|0.05
        owls-static|The Velvet Owls|Static Hearts|Night Market|2019|L H4|0.05
        marrow-copper|Marrow & Pine|Copper Sky|Field Notes|2023|L H3|0.05
        atlas-orbit|June Atlas|Slow Orbit|Satellite Days|2024|L H2|0.05
        quell-boats|Nadia Quell|Paper Boats|Tidelines|2022|L H2|0.05
    """.trimIndent()

    /** Invented lyrics for the fictional tracks above. */
    val lyrics: Map<String, String> = mapOf(
        "lanterns-harbor" to """
            we left the porch light burning for the boats that never came
            salt is in the letters that you folded with my name
            every gull above the water knows the way you used to sing
            and I keep the winter coat you wore through every spring
        """.trimIndent(),
        "owls-static" to """
            radio hums a secret only midnight understands
            neon on the pavement like a fortune on our hands
            static hearts are beating out of time
            tell me that the frequency is mine
        """.trimIndent(),
        "marrow-copper" to """
            the tractor sleeps beside the creek where we buried all our plans
            grandmother's hymns are rusting in the pockets of my hands
            under copper sky we never learned to pray
        """.trimIndent(),
        "atlas-orbit" to """
            counting satellites from the roof of your car
            every signal fading like a cigarette star
            we were weightless for a while
        """.trimIndent(),
        "quell-boats" to """
            fold the morning into paper boats
            send them down the gutter where the river goes
            nobody is waiting at the other side
        """.trimIndent(),
    )

    class Row(val meta: TrackMeta, val inLibrary: Boolean, val plays: Int, val official: Boolean, val onlineOnly: Boolean)

    val all: List<Row> = rows.lines().filter { it.isNotBlank() }.map { line ->
        val c = line.split('|').map { it.trim() }
        require(c.size == 7) { "Bad fixture row: $line" }
        val flags = c[5].split(' ')
        Row(
            meta = TrackMeta(
                key = TrackKey.youtube(c[0]),
                title = c[2],
                artists = c[1].split(';'),
                album = c[3].ifEmpty { null },
                releasedAtMs = c[4].toIntOrNull()?.let(::yearMs),
                youtubeId = c[0],
                popularity = c[6].toFloat(),
            ),
            inLibrary = "L" in flags,
            plays = flags.firstOrNull { it.startsWith("H") }?.drop(1)?.toInt() ?: 0,
            official = "OFF" in flags,
            onlineOnly = "CAT" in flags,
        )
    }

    val library: List<Row> get() = all.filter { it.inLibrary || it.plays > 0 }

    /** The online catalog has everything, including the library's own uploads. */
    val online: List<OnlineTrack> get() = all.map { OnlineTrack(it.meta, official = it.official, popularity = it.meta.popularity) }
}
