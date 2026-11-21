package app.turtleplayer.mix.brain

import app.turtleplayer.mix.Familiarity
import app.turtleplayer.mix.TrackFacts
import kotlin.random.Random

private var nextId = 0

fun track(
    id: String = "t${nextId++}",
    artist: String = "artist-$id",
    genre: String = "house",
    bpm: Float = 124f,
    energy: Float = 0.6f,
    familiarity: Familiarity = Familiarity.Known,
    popularity: Float = 0.5f,
    dance: Boolean = genre in DANCE_GENRES,
    durationMs: Long = 300_000,
    bpmMeasured: Boolean = true,
    energyMeasured: Boolean = true,
    requested: Boolean = false,
) = TrackFacts(
    id = id, artist = artist, genre = genre, bpm = bpm, bpmMeasured = bpmMeasured, energy = energy,
    energyMeasured = energyMeasured, durationMs = durationMs, familiarity = familiarity, popularity = popularity,
    dance = dance, requested = requested,
)

val DANCE_GENRES = setOf("house", "techno", "disco", "dnb")

/** Genre -> (BPM range, energy range, typical duration). */
private val GENRE_SHAPES = mapOf(
    "house" to Triple(118f..128f, 0.45f..0.9f, 330_000L),
    "techno" to Triple(124f..138f, 0.55f..0.98f, 380_000L),
    "disco" to Triple(108f..124f, 0.4f..0.85f, 280_000L),
    "dnb" to Triple(170f..176f, 0.6f..0.98f, 300_000L),
    "pop" to Triple(90f..130f, 0.3f..0.85f, 210_000L),
    "hiphop" to Triple(84f..100f, 0.35f..0.8f, 200_000L),
    "acoustic" to Triple(70f..120f, 0.1f..0.45f, 230_000L),
)

/**
 * A synthetic candidate pool: [size] tracks over [genres], artists with 1-6 tracks each, a familiarity
 * mix (about 35 % familiar) and popularity skewed so favourites are more popular.
 */
fun syntheticPool(seed: Int, size: Int = 400, genres: List<String> = GENRE_SHAPES.keys.toList()): List<TrackFacts> {
    val rnd = Random(seed)
    val out = ArrayList<TrackFacts>(size)
    var artist = 0
    var left = 0
    var artistGenre = genres.first()
    for (i in 0 until size) {
        if (left == 0) {
            artist++
            left = 1 + rnd.nextInt(6)
            artistGenre = genres[rnd.nextInt(genres.size)]
        }
        left--
        val (bpmR, eR, dur) = GENRE_SHAPES.getValue(artistGenre)
        val fam = when (rnd.nextInt(100)) {
            in 0..9 -> Familiarity.Favorite
            in 10..19 -> Familiarity.Liked
            in 20..34 -> Familiarity.Known
            else -> Familiarity.New
        }
        val pop = (if (fam == Familiarity.New) rnd.nextFloat() * 0.6f else 0.3f + rnd.nextFloat() * 0.7f)
        out += track(
            id = "p$seed-$i",
            artist = "artist$artist",
            genre = artistGenre,
            bpm = bpmR.start + rnd.nextFloat() * (bpmR.endInclusive - bpmR.start),
            energy = eR.start + rnd.nextFloat() * (eR.endInclusive - eR.start),
            familiarity = fam,
            popularity = pop,
            durationMs = dur + rnd.nextLong(-60_000, 60_000),
            energyMeasured = rnd.nextInt(4) != 0,
        )
    }
    return out
}
