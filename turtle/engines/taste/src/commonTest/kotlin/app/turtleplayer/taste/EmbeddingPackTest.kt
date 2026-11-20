package app.turtleplayer.taste

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddingPackTest {
    private val random = Random(3)
    private fun randomVector(dim: Int = EMBEDDING_DIM) = FloatArray(dim) { random.nextFloat() * 2 - 1 }

    private val entries = List(50) { "mbid:track-$it" to randomVector() } + ("meta:björk|jóga" to randomVector())

    @Test
    fun roundTripPreservesIdsMetadataAndDirections() {
        val bytes = EmbeddingPackWriter.write(EmbeddingKind.TRACK, EMBEDDING_DIM, entries, buildId = 20261101)
        val pack = EmbeddingPackReader.read(bytes, EMBEDDING_DIM, EmbeddingKind.TRACK)
        assertEquals(EmbeddingKind.TRACK, pack.kind)
        assertEquals(EMBEDDING_DIM, pack.dim)
        assertEquals(20261101, pack.buildId)
        assertEquals(entries.map { it.first }, pack.ids)
        for ((i, e) in entries.withIndex()) {
            val restored = pack.vector(i)
            assertTrue(restored.cosine(e.second) > 0.999f, "entry $i cosine ${restored.cosine(e.second)}")
            // Symmetric 8-bit quantization: error per component is at most half a step.
            val step = e.second.maxOf { abs(it) } / 127f
            for (j in restored.indices) assertTrue(abs(restored[j] - e.second[j]) <= step / 2 + 1e-6f)
        }
        // About one byte per dimension: 64 codes + 4 scale + id per vector.
        assertTrue(bytes.size < entries.size * (EMBEDDING_DIM + 4 + 2 + 24) + 32)
    }

    @Test
    fun loadsIntoStoreForNeighbourSearch() {
        val bytes = EmbeddingPackWriter.write(EmbeddingKind.ARTIST, EMBEDDING_DIM, entries.take(10))
        val store = InMemoryEmbeddingStore()
        store.load(EmbeddingPackReader.read(bytes, expectedKind = EmbeddingKind.ARTIST))
        assertEquals(10, store.artistCount)
        val first = entries[0]
        val nearest = store.nearestArtists(first.second, 1)
        assertEquals(first.first, nearest.single().key.value)
    }

    @Test
    fun zeroVectorRoundTrips() {
        val bytes = EmbeddingPackWriter.write(EmbeddingKind.ARTIST, 4, listOf("silent" to FloatArray(4)))
        val pack = EmbeddingPackReader.read(bytes, expectedDim = 4)
        assertContentEquals(FloatArray(4), pack.vector(0))
    }

    @Test
    fun crcMatchesStandardCheckValue() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray(), 0, 9))
    }

    /** Pins the byte layout: a reader in another language must agree with this fixture. */
    @Test
    fun fixtureLayout() {
        val bytes = EmbeddingPackWriter.write(EmbeddingKind.ARTIST, 2, listOf("a" to floatArrayOf(1f, -0.5f)), buildId = 7)
        val scaleBits = (1f / 127f).toRawBits()
        val body = byteArrayOf(
            0x54, 0x54, 0x45, 0x50, // "TTEP"
            1, 0, // version
            1, // kind artist
            0, // flags
            2, 0, // dim
            0, 0, // reserved
            1, 0, 0, 0, // count
            7, 0, 0, 0, 0, 0, 0, 0, // buildId
            1, 0, 0x61, // id "a"
            scaleBits.toByte(), (scaleBits ushr 8).toByte(), (scaleBits ushr 16).toByte(), (scaleBits ushr 24).toByte(),
            127, -63, // codes: 1.0 -> 127, -0.5 -> round(-63.5) = -63
        )
        val crc = Crc32.of(body, 0, body.size)
        val expected = body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte())
        assertContentEquals(expected, bytes)
        val v = EmbeddingPackReader.read(expected, expectedDim = 2).vector(0)
        assertEquals(1f, v[0], 1e-6f)
        assertEquals(-63f / 127f, v[1], 1e-6f)
    }

    // ---- Corruption ---------------------------------------------------------------------------

    private fun valid() = EmbeddingPackWriter.write(EmbeddingKind.TRACK, EMBEDDING_DIM, entries.take(5))

    /** Applies [edit] and recomputes the checksum, so structural validation (not the CRC) must catch it. */
    private fun patched(bytes: ByteArray = valid(), edit: (ByteArray) -> ByteArray): ByteArray {
        val body = edit(bytes.copyOf(bytes.size - 4))
        val crc = Crc32.of(body, 0, body.size)
        return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte())
    }

    @Test
    fun rejectsBadMagic() {
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(valid().also { it[0] = 'X'.code.toByte() }) }
    }

    @Test
    fun rejectsFlippedByte() {
        val b = valid()
        b[b.size / 2] = (b[b.size / 2].toInt() xor 0x10).toByte()
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(b) }
    }

    @Test
    fun rejectsTruncation() {
        val b = valid()
        for (cut in listOf(0, 10, 27, b.size / 2, b.size - 1)) {
            assertFailsWith<PackFormatException>("cut at $cut") { EmbeddingPackReader.read(b.copyOf(cut)) }
        }
        // Truncated body with a recomputed checksum must still fail structurally.
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(patched { it.copyOf(it.size - 10) }) }
    }

    @Test
    fun rejectsUnsupportedVersionAndKind() {
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(patched { it.also { b -> b[4] = 2 } }) }
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(patched { it.also { b -> b[6] = 9 } }) }
    }

    @Test
    fun rejectsDimAndKindMismatch() {
        val b = valid()
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(b, expectedDim = 32) }
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(b, expectedKind = EmbeddingKind.ARTIST) }
    }

    @Test
    fun rejectsImpossibleCountWithoutAllocating() {
        // count = 0xFFFFFFFF claims ~280 GB; the reader must refuse before allocating.
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(patched { it.also { b -> for (i in 12..15) b[i] = -1 } }) }
    }

    @Test
    fun rejectsTrailingBytes() {
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(patched { it + byteArrayOf(0, 0, 0) }) }
    }

    @Test
    fun rejectsBadScaleAndDuplicateIds() {
        // First entry's scale sits after the 24-byte header and the id ("mbid:track-0", 12 bytes + 2 length).
        val scaleAt = 24 + 2 + 12
        assertFailsWith<PackFormatException> {
            EmbeddingPackReader.read(patched { it.also { b -> b[scaleAt + 3] = 0x7F; b[scaleAt + 2] = 0xC0.toByte() } }) // NaN
        }
        val two = EmbeddingPackWriter.write(EmbeddingKind.TRACK, 2, listOf("aa" to floatArrayOf(1f, 0f), "ab" to floatArrayOf(0f, 1f)))
        // Rename the second id "ab" -> "aa".
        val dup = patched(two) { b -> b.also { val second = 24 + (2 + 2 + 4 + 2); b[second + 3] = 'a'.code.toByte() } }
        assertFailsWith<PackFormatException> { EmbeddingPackReader.read(dup, expectedDim = 2) }
    }

    @Test
    fun writerRejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> { EmbeddingPackWriter.write(EmbeddingKind.TRACK, 2, listOf("a" to floatArrayOf(1f, 0f), "a" to floatArrayOf(0f, 1f))) }
        assertFailsWith<IllegalArgumentException> { EmbeddingPackWriter.write(EmbeddingKind.TRACK, 2, listOf("a" to floatArrayOf(Float.NaN, 0f))) }
        assertFailsWith<IllegalArgumentException> { EmbeddingPackWriter.write(EmbeddingKind.TRACK, 3, listOf("a" to floatArrayOf(1f, 0f))) }
    }
}
