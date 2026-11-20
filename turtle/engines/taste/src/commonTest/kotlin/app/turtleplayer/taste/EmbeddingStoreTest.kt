package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingStoreTest {
    @Test
    fun vectorMath() {
        val a = floatArrayOf(3f, 4f)
        assertEquals(5f, a.norm(), 1e-6f)
        assertEquals(1f, a.normalized().norm(), 1e-6f)
        assertEquals(0f, floatArrayOf(0f, 0f).cosine(a))
        assertEquals(-1f, a.cosine(floatArrayOf(-3f, -4f)), 1e-6f)
        val mean = weightedMeanDirection(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 10f)), listOf(3.0, 1.0), dim = 2)!!
        // Inputs are normalized first, so weight (not magnitude) decides the direction.
        assertTrue(mean[0] > mean[1])
        assertNull(weightedMeanDirection(emptyList(), dim = 2))
    }

    @Test
    fun nearestMatchesBruteForceSort() {
        val r = Random(11)
        val store = InMemoryEmbeddingStore()
        val vectors = List(500) { i -> TrackKey("yt:v$i") to FloatArray(EMBEDDING_DIM) { r.nextFloat() - 0.5f } }
        vectors.forEach { (k, v) -> store.putTrack(k, v) }
        repeat(5) {
            val q = FloatArray(EMBEDDING_DIM) { r.nextFloat() - 0.5f }
            val expected = vectors.sortedByDescending { it.second.cosine(q) }.take(25).map { it.first }
            val got = store.nearestTracks(q, 25)
            assertEquals(expected, got.map { it.key })
            assertTrue(got.zipWithNext().all { (x, y) -> x.similarity >= y.similarity })
        }
    }

    @Test
    fun nearestHonoursExclusionAndSmallStores() {
        val store = InMemoryEmbeddingStore(dim = 2)
        store.putArtist(ArtistKey("a"), floatArrayOf(1f, 0f))
        store.putArtist(ArtistKey("b"), floatArrayOf(0.9f, 0.1f))
        store.putArtist(ArtistKey("c"), floatArrayOf(0f, 1f))
        assertEquals(listOf("b", "c"), store.nearestArtists(floatArrayOf(1f, 0f), 10) { it.value == "a" }.map { it.key.value })
        assertTrue(store.nearestArtists(floatArrayOf(0f, 0f), 3).isEmpty())
        // Overwrite keeps one entry.
        store.putArtist(ArtistKey("a"), floatArrayOf(0f, 1f))
        assertEquals(3, store.artistCount)
        assertEquals(1f, store.artistVector(ArtistKey("a"))!!.cosine(floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun representationCombinesAvailablePartsAndFallsBackToArtist() {
        val store = InMemoryEmbeddingStore(dim = 2)
        val artist = ArtistKey.of("Someone")
        store.putArtist(artist, floatArrayOf(1f, 0f))
        val known = TrackMeta(TrackKey("yt:known"), "Known", listOf("Someone"))
        val unknownArtist = TrackMeta(TrackKey("yt:nobody"), "Nobody", listOf("Nobody"))
        store.putTrack(known.key, floatArrayOf(0f, 1f))

        val coOnly = TrackRepresentation(StoreCoListeningVectors(store), dim = 2)
        assertEquals(1f, coOnly.vectorFor(known)!!.cosine(floatArrayOf(0f, 1f)), 1e-6f)
        assertNull(coOnly.vectorFor(unknownArtist))
        val viaArtist = TrackMeta(TrackKey("yt:other"), "Other", listOf("Someone"))
        assertEquals(1f, coOnly.vectorFor(viaArtist)!!.cosine(floatArrayOf(1f, 0f)), 1e-6f)

        // Sound-only track still gets a vector; with both parts the result lies between them.
        val sound = SoundVectors { if (it.value == "yt:nobody" || it.value == "yt:known") PartVector(floatArrayOf(1f, 0f)) else null }
        val combined = TrackRepresentation(StoreCoListeningVectors(store), sound = sound, dim = 2)
        assertEquals(1f, combined.vectorFor(unknownArtist)!!.cosine(floatArrayOf(1f, 0f)), 1e-6f)
        val mixed = combined.vectorFor(known)!!
        assertTrue(mixed[0] > 0f && mixed[1] > mixed[0], "co-listening (0.6) outweighs sound (0.25): ${mixed.toList()}")
    }
}
